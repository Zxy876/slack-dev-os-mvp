#!/usr/bin/env bash
# run_page_fault_e2e.sh — Slack Dev OS B-005 Page Fault E2E (local)
#
# Validates the minimal Page Fault / Repository File Retrieval proof:
#   - Creates a fixture repo at /tmp/devos-fixture-repo/
#   - POST /devos/start with repoPath + filePath
#   - Worker reads file, injects [PAGE_IN] into response
#   - Assert result.response contains [PAGE_IN]
#   - Assert result.notepad contains [page-in:README.md]
#
# Usage:
#   ./scripts/run_page_fault_e2e.sh
#
# Prerequisites:
#   - Java 21 (mvn in PATH)
#   - Python 3 (python3 in PATH)
#   - Redis running on localhost:6379
#
# Set SKIP_BACKEND=1 to reuse an already-running backend.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_URL="${ASYNCAIFLOW_URL:-http://localhost:8080}"
WORKER_ID="${DEVOS_WORKER_ID:-devos-page-fault-e2e}"
POLL_INTERVAL="${POLL_INTERVAL_S:-1.0}"
MAX_WAIT=120
BACKEND_LOG="/tmp/devos-pf-backend.log"
WORKER_LOG="/tmp/devos-pf-worker.log"
BACKEND_PID=""
WORKER_PID=""

# ───────────────────────── fixture ──────────────────────────
FIXTURE_REPO="/tmp/devos-fixture-repo"
FIXTURE_FILE="${FIXTURE_REPO}/README.md"

echo "==== Slack Dev OS — Page Fault E2E (B-005) ===="
echo ""
echo "[0] Creating fixture repo at ${FIXTURE_REPO}..."
mkdir -p "$FIXTURE_REPO"
cat > "$FIXTURE_FILE" << 'EOF'
# DevOS Fixture Repository

This is a test fixture for Slack Dev OS B-005 Page Fault proof.

## Purpose
Validates that the devos_chat worker can safely read a file from a local
Git repository path and inject its content into the response as a page-in
context (OS Disk → Page In → L2 Notepad cache).

## Architecture
- repo_path  = Disk address
- file_path  = Page address
- worker.py  = MMU (safe_read_repo_file)
- notepad    = L2 cache (page-in recorded)
EOF
echo "    Fixture created: ${FIXTURE_FILE}"

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

# ───────────────────────── Round 3: Page Fault ──────────────
echo ""
echo "[1] POST /devos/start — Round 3 (Page Fault: repoPath=${FIXTURE_REPO}, filePath=README.md)"
RESPONSE=$(curl -s -X POST "${BACKEND_URL}/devos/start" \
  -H "Content-Type: application/json" \
  -d "{\"text\":\"Explain this file\",\"slackThreadId\":\"CDEMO/1714500000.999999\",\"repoPath\":\"${FIXTURE_REPO}\",\"filePath\":\"README.md\"}")
echo "    Response: $RESPONSE"

ACTION_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['actionId'])" 2>/dev/null || true)
if [ -z "$ACTION_ID" ] || [ "$ACTION_ID" = "None" ]; then
  echo "FAIL: could not extract actionId"
  exit 1
fi
echo "    actionId: $ACTION_ID"

# ───────────────────────── poll until COMPLETED ─────────────
echo ""
echo "[2] Polling GET /action/${ACTION_ID}..."
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
  echo "FAIL: timed out waiting for COMPLETED"
  exit 1
fi

# ───────────────────────── assert Page Fault ────────────────
echo ""
echo "[3] Asserting Page Fault result..."
RESULT=$(curl -s "${BACKEND_URL}/action/${ACTION_ID}")
RESPONSE_TEXT=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['result']['response'])" 2>/dev/null || echo "")
NOTEPAD=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['result'].get('notepad',''))" 2>/dev/null || echo "")

echo "    response: $RESPONSE_TEXT"
echo "    notepad:  $NOTEPAD"

PASSED=true

if echo "$RESPONSE_TEXT" | grep -q "\[PAGE_IN\]"; then
  echo "    PASS: response contains [PAGE_IN] — page fault executed"
else
  echo "    FAIL: response does not contain [PAGE_IN]"
  PASSED=false
fi

if echo "$RESPONSE_TEXT" | grep -q "README.md"; then
  echo "    PASS: response contains file name README.md"
else
  echo "    FAIL: response does not mention README.md"
  PASSED=false
fi

if echo "$NOTEPAD" | grep -q "\[page-in:README.md\]"; then
  echo "    PASS: notepad contains [page-in:README.md] — page-in recorded in L2 cache"
else
  echo "    FAIL: notepad does not contain [page-in:README.md]"
  PASSED=false
fi

# ───────────────────────── result ───────────────────────────
echo ""
if [ "$PASSED" = "true" ]; then
  echo "=============================================="
  echo " Page Fault E2E PASSED"
  echo "   [Round 3] POST /devos/start (repoPath+filePath) → QUEUED"
  echo "   [Round 3] devos_chat_worker safe_read_repo_file → [PAGE_IN] ✓"
  echo "   [Round 3] result.response → [PAGE_IN] Loaded file: README.md ✓"
  echo "   [Round 3] result.notepad  → [page-in:README.md] ✓"
  echo "   Stage 4 Page Fault Proof: VERIFIED ✓"
  echo "=============================================="
  exit 0
else
  echo "Page Fault E2E FAILED"
  echo "=== backend.log (last 20 lines) ==="
  tail -20 "$BACKEND_LOG" 2>/dev/null || true
  echo ""
  echo "=== worker.log ==="
  cat "$WORKER_LOG" 2>/dev/null || true
  exit 1
fi
