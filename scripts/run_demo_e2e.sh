#!/usr/bin/env bash
# run_demo_e2e.sh — Slack Dev OS DEMO E2E (local)
#
# Runs the full kernel instruction cycle locally in DEMO_MODE.
# Includes Stage 2 Context Restore: two-round sequential execution.
# No LLM keys or Slack tokens required.
#
# Usage:
#   ./scripts/run_demo_e2e.sh
#
# Prerequisites:
#   - Java 21 (mvn in PATH)
#   - Python 3 (python3 in PATH)
#   - Redis running on localhost:6379
#
# The script will start the backend if not already running.
# Set SKIP_BACKEND=1 to skip starting the backend.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_URL="${ASYNCAIFLOW_URL:-http://localhost:8080}"
WORKER_ID="${DEVOS_WORKER_ID:-devos-e2e-local}"
POLL_INTERVAL="${POLL_INTERVAL_S:-1.0}"
MAX_WAIT=120
BACKEND_LOG="/tmp/devos-backend-e2e.log"
WORKER_LOG="/tmp/devos-worker-e2e.log"
BACKEND_PID=""
WORKER_PID=""

# ───────────────────────── cleanup ──────────────────────────
cleanup() {
  echo ""
  if [ -n "$WORKER_PID" ]; then
    kill "$WORKER_PID" 2>/dev/null && echo "Worker stopped" || true
  fi
  if [ -n "$BACKEND_PID" ]; then
    kill "$BACKEND_PID" 2>/dev/null && echo "Backend stopped" || true
  fi
}
trap cleanup EXIT

# ───────────────────────── check backend ────────────────────
health_check() {
  curl -s -o /dev/null -w "%{http_code}" "${BACKEND_URL}/health"
}

echo "==== Slack Dev OS — DEMO E2E ===="
echo ""

if [ "${SKIP_BACKEND:-0}" = "1" ]; then
  echo "SKIP_BACKEND=1 — assuming backend is already running"
else
  STATUS=$(health_check || true)
  if [ "$STATUS" = "200" ]; then
    echo "Backend already running at ${BACKEND_URL}"
  else
    echo "Starting backend (local profile — H2)..."
    cd "$REPO_ROOT"
    mvn spring-boot:run -Dspring-boot.run.profiles=local > "$BACKEND_LOG" 2>&1 &
    BACKEND_PID=$!
    echo "Backend PID: $BACKEND_PID (log: $BACKEND_LOG)"
    echo "Waiting for /health..."
    for i in $(seq 1 $MAX_WAIT); do
      STATUS=$(health_check || true)
      if [ "$STATUS" = "200" ]; then
        echo "Backend ready after ${i}s"
        break
      fi
      if [ "$i" = "$MAX_WAIT" ]; then
        echo "FAIL: backend not ready after ${MAX_WAIT}s"
        echo "=== backend.log ==="
        tail -50 "$BACKEND_LOG"
        exit 1
      fi
      sleep 1
    done
  fi
fi

# ───────────────────────── install deps ─────────────────────
WORKER_DIR="${REPO_ROOT}/python-workers/devos_chat_worker"
echo ""
echo "Installing worker dependencies..."
pip3 install -q --break-system-packages -r "${WORKER_DIR}/requirements.txt" 2>/dev/null \
  || pip3 install -q -r "${WORKER_DIR}/requirements.txt" 2>/dev/null \
  || true

# ───────────────────────── start worker ─────────────────────
echo "Starting devos_chat_worker (DEMO_MODE=true)..."
env -u OPENAI_API_KEY -u GLM_API_KEY \
  ASYNCAIFLOW_URL="$BACKEND_URL" \
  DEVOS_WORKER_ID="$WORKER_ID" \
  DEMO_MODE=true \
  POLL_INTERVAL_S="$POLL_INTERVAL" \
  python3 "${WORKER_DIR}/worker.py" > "$WORKER_LOG" 2>&1 &
WORKER_PID=$!
echo "Worker PID: $WORKER_PID (log: $WORKER_LOG)"
sleep 2

# ───────────────────────── POST /devos/start ────────────────
echo ""
echo "[1] POST /devos/start — Round 1 (no prevActionId)"
RESPONSE=$(curl -s -X POST "${BACKEND_URL}/devos/start" \
  -H "Content-Type: application/json" \
  -d '{"text":"How do I reset a build?","slackThreadId":"CDEMO/1714500000.123456"}')
echo "    Response: $RESPONSE"

ACTION_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['actionId'])" 2>/dev/null || true)
if [ -z "$ACTION_ID" ] || [ "$ACTION_ID" = "None" ]; then
  echo "FAIL: could not extract actionId"
  exit 1
fi
echo "    actionId: $ACTION_ID"

# ───────────────────────── poll until COMPLETED (Round 1) ───
echo ""
echo "[2] Polling GET /action/${ACTION_ID} (Round 1)..."
COMPLETED=false
for i in $(seq 1 60); do
  RESULT=$(curl -s "${BACKEND_URL}/action/${ACTION_ID}")
  STATUS=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['status'])" 2>/dev/null || echo "UNKNOWN")
  echo "    [$i] status=$STATUS"
  if [ "$STATUS" = "COMPLETED" ]; then
    COMPLETED=true
    break
  fi
  if [ "$STATUS" = "FAILED" ]; then
    echo "FAIL: action status is FAILED"
    echo "$RESULT"
    exit 1
  fi
  sleep 2
done

if [ "$COMPLETED" = "false" ]; then
  echo "FAIL: timed out waiting for COMPLETED (Round 1)"
  exit 1
fi

# ───────────────────────── assert Round 1 ───────────────────
echo ""
echo "[3] Asserting Round 1 result..."
RESULT=$(curl -s "${BACKEND_URL}/action/${ACTION_ID}")
RESPONSE_TEXT=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['result']['response'])" 2>/dev/null || echo "")
NOTEPAD=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['result'].get('notepad',''))" 2>/dev/null || echo "")

echo "    response: $RESPONSE_TEXT"
echo "    notepad:  $NOTEPAD"

PASSED=true
if echo "$RESPONSE_TEXT" | grep -q "\[DEMO\]"; then
  echo "    PASS: response contains [DEMO]"
else
  echo "    FAIL: response does not contain [DEMO]"
  PASSED=false
fi

if [ -n "$NOTEPAD" ] && [ "$NOTEPAD" != "None" ] && [ "$NOTEPAD" != "null" ]; then
  echo "    PASS: notepad is present (Round 1)"
else
  echo "    FAIL: notepad is empty or null (Round 1)"
  PASSED=false
fi

# ───────────────────────── Round 2: Context Restore ─────────
echo ""
echo "[4] POST /devos/start — Round 2 (prevActionId=${ACTION_ID}, Context Restore)"
RESPONSE2=$(curl -s -X POST "${BACKEND_URL}/devos/start" \
  -H "Content-Type: application/json" \
  -d "{\"text\":\"Now follow up on the previous step\",\"slackThreadId\":\"CDEMO/1714500000.123456\",\"prevActionId\":${ACTION_ID}}")
echo "    Response: $RESPONSE2"

ACTION_ID2=$(echo "$RESPONSE2" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['actionId'])" 2>/dev/null || true)
if [ -z "$ACTION_ID2" ] || [ "$ACTION_ID2" = "None" ]; then
  echo "FAIL: could not extract actionId (Round 2)"
  exit 1
fi
echo "    actionId (Round 2): $ACTION_ID2"

# ───────────────────────── poll until COMPLETED (Round 2) ───
echo ""
echo "[5] Polling GET /action/${ACTION_ID2} (Round 2 — Context Restore)..."
COMPLETED2=false
for i in $(seq 1 60); do
  RESULT2=$(curl -s "${BACKEND_URL}/action/${ACTION_ID2}")
  STATUS2=$(echo "$RESULT2" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['status'])" 2>/dev/null || echo "UNKNOWN")
  echo "    [$i] status=$STATUS2"
  if [ "$STATUS2" = "COMPLETED" ]; then
    COMPLETED2=true
    break
  fi
  if [ "$STATUS2" = "FAILED" ]; then
    echo "FAIL: action status is FAILED (Round 2)"
    echo "$RESULT2"
    exit 1
  fi
  sleep 2
done

if [ "$COMPLETED2" = "false" ]; then
  echo "FAIL: timed out waiting for COMPLETED (Round 2)"
  exit 1
fi

# ───────────────────────── assert Round 2 ───────────────────
echo ""
echo "[6] Asserting Round 2 result (Context Restore)..."
RESULT2=$(curl -s "${BACKEND_URL}/action/${ACTION_ID2}")
RESPONSE_TEXT2=$(echo "$RESULT2" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['result']['response'])" 2>/dev/null || echo "")
NOTEPAD2=$(echo "$RESULT2" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['result'].get('notepad',''))" 2>/dev/null || echo "")

echo "    response (Round 2): $RESPONSE_TEXT2"
echo "    notepad  (Round 2): $NOTEPAD2"

if echo "$RESPONSE_TEXT2" | grep -q "\[DEMO\]"; then
  echo "    PASS: Round 2 response contains [DEMO]"
else
  echo "    FAIL: Round 2 response does not contain [DEMO]"
  PASSED=false
fi

if echo "$RESPONSE_TEXT2" | grep -q "\[Notepad context was present\]"; then
  echo "    PASS: Round 2 response contains [Notepad context was present] — Context Restore verified"
else
  echo "    FAIL: Round 2 response missing [Notepad context was present]"
  PASSED=false
fi

if [ -n "$NOTEPAD2" ] && [ "$NOTEPAD2" != "None" ] && [ "$NOTEPAD2" != "null" ]; then
  echo "    PASS: notepad is present (Round 2)"
else
  echo "    FAIL: notepad is empty or null (Round 2)"
  PASSED=false
fi

# ───────────────────────── result ───────────────────────────
echo ""
if [ "$PASSED" = "true" ]; then
  echo "================================================="
  echo " E2E PASSED: Full kernel instruction cycle done"
  echo "   [Round 1] POST /devos/start → QUEUED"
  echo "   [Round 1] devos_chat_worker → DEMO → SUCCEEDED"
  echo "   [Round 1] GET /action/${ACTION_ID} → COMPLETED"
  echo "   [Round 1] result.response → [DEMO] ✓"
  echo "   [Round 1] result.notepad  → present ✓"
  echo "   [Round 2] POST /devos/start (prevActionId) → QUEUED"
  echo "   [Round 2] devos_chat_worker → Context Restore → SUCCEEDED"
  echo "   [Round 2] GET /action/${ACTION_ID2} → COMPLETED"
  echo "   [Round 2] result.response → [Notepad context was present] ✓"
  echo "   Stage 2 Context Restore: VERIFIED ✓"
  echo "================================================="
  exit 0
else
  echo "E2E FAILED"
  echo "=== backend.log (last 30 lines) ==="
  tail -30 "$BACKEND_LOG" 2>/dev/null || true
  echo ""
  echo "=== worker.log ==="
  cat "$WORKER_LOG" 2>/dev/null || true
  exit 1
fi
