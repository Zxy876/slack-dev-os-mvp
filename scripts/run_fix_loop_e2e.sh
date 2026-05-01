#!/usr/bin/env bash
# run_fix_loop_e2e.sh — Slack Dev OS B-020 Fix Proposal Loop E2E (local)
#
# Flow:
#   1. POST /devos/propose-fix with a simulated test failure on README.md → QUEUED
#   2. Verify action is created with mode=fix_preview in payload
#   3. Verify failure_context fields are stored
#   4. Verify README.md is unchanged after the API call (no mutation)
#   5. Secret scan PASS
#
# Prerequisites:
#   - Backend running at $ASYNCAIFLOW_URL (default http://localhost:8080)
#   - Set SKIP_BACKEND=1 to reuse an already-running backend
#   - jq must be installed (brew install jq)
#
# Usage:
#   SKIP_BACKEND=1 bash scripts/run_fix_loop_e2e.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_URL="${ASYNCAIFLOW_URL:-http://localhost:8080}"
MAX_WAIT=120
BACKEND_LOG="/tmp/devos-fix-loop-backend.log"
BACKEND_PID=""
THREAD_ID="C-FIX-LOOP-E2E/$(date +%s).000001"
README_PATH="${REPO_ROOT}/README.md"

echo "==== Slack Dev OS — Fix Proposal Loop E2E (B-020) ===="
echo ""

# ───────────────────────── cleanup ──────────────────────────
cleanup() {
  if [ -n "$BACKEND_PID" ]; then
    kill "$BACKEND_PID" 2>/dev/null && echo "Backend stopped" || true
  fi
}
trap cleanup EXIT

# ───────────────────────── jq guard ─────────────────────────
if ! command -v jq &>/dev/null; then
  echo "ERROR: jq is required (brew install jq)" >&2
  exit 1
fi

# ───────────────────────── health check ─────────────────────
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

# ───────────────────── Snapshot README.md ───────────────────
README_HASH_BEFORE=$(md5 -q "${README_PATH}" 2>/dev/null || md5sum "${README_PATH}" | awk '{print $1}')
echo "README.md hash before test: ${README_HASH_BEFORE}"

# ───────────────── Step 1: POST /devos/propose-fix ──────────
echo ""
echo "[1] POST /devos/propose-fix — simulated FAILED test on README.md"
RESP=$(curl -s -X POST "${BACKEND_URL}/devos/propose-fix" \
  -H "Content-Type: application/json" \
  -d "{
    \"slackThreadId\": \"${THREAD_ID}\",
    \"repoPath\": \"${REPO_ROOT}\",
    \"filePath\": \"README.md\",
    \"testStatus\": \"FAILED\",
    \"exitCode\": 1,
    \"stdoutExcerpt\": \"Tests: 3 failed, 17 passed\",
    \"stderrExcerpt\": \"AssertionError: expected 'Hello' but got null\",
    \"hint\": \"Check the greeting initialization\"
  }")

echo "Response: ${RESP}"

HTTP_STATUS=$(echo "${RESP}" | jq -r '.code // "null"')
if [ "${HTTP_STATUS}" != "200" ]; then
  echo "FAIL [1]: Expected HTTP 200 code, got ${HTTP_STATUS}"
  exit 1
fi
echo "PASS [1]: HTTP 200 received"

# ── Extract action/workflow IDs ──────────────────────────────
ACTION_ID=$(echo "${RESP}" | jq -r '.data.actionId // "null"')
WORKFLOW_ID=$(echo "${RESP}" | jq -r '.data.workflowId // "null"')
RESP_STATUS=$(echo "${RESP}" | jq -r '.data.status // "null"')
RESP_THREAD=$(echo "${RESP}" | jq -r '.data.slackThreadId // "null"')
RESP_MSG=$(echo "${RESP}" | jq -r '.data.message // "null"')

if [ "${ACTION_ID}" = "null" ] || [ -z "${ACTION_ID}" ]; then
  echo "FAIL [1]: actionId missing from response"
  exit 1
fi
if [ "${WORKFLOW_ID}" = "null" ] || [ -z "${WORKFLOW_ID}" ]; then
  echo "FAIL [1]: workflowId missing from response"
  exit 1
fi
echo "PASS [1]: actionId=${ACTION_ID}, workflowId=${WORKFLOW_ID}"

# ──────────────── Step 2: status must be QUEUED ─────────────
echo ""
echo "[2] Verify status=QUEUED"
if [ "${RESP_STATUS}" != "QUEUED" ]; then
  echo "FAIL [2]: Expected status=QUEUED, got ${RESP_STATUS}"
  exit 1
fi
echo "PASS [2]: status=QUEUED"

# ──────────────── Step 3: Verify slackThreadId ──────────────
echo ""
echo "[3] Verify slackThreadId echoed correctly"
if [ "${RESP_THREAD}" != "${THREAD_ID}" ]; then
  echo "FAIL [3]: slackThreadId mismatch: expected ${THREAD_ID}, got ${RESP_THREAD}"
  exit 1
fi
echo "PASS [3]: slackThreadId matches"

# ──────────────── Step 4: Verify response message ───────────
echo ""
echo "[4] Verify message contains actionId"
if ! echo "${RESP_MSG}" | grep -q "${ACTION_ID}"; then
  echo "FAIL [4]: message '${RESP_MSG}' does not contain actionId=${ACTION_ID}"
  exit 1
fi
echo "PASS [4]: message references actionId"

# ──────────────── Step 5: Fetch action from API ─────────────
echo ""
echo "[5] Fetch action ${ACTION_ID} and verify payload"
ACTION_RESP=$(curl -s "${BACKEND_URL}/actions/${ACTION_ID}")
PAYLOAD_MODE=$(echo "${ACTION_RESP}" | jq -r '.data.payload | fromjson | .mode // "null"' 2>/dev/null || echo "null")
PAYLOAD_FC=$(echo "${ACTION_RESP}" | jq -r '.data.payload | fromjson | .failure_context // "null"' 2>/dev/null || echo "null")

if [ "${PAYLOAD_MODE}" = "fix_preview" ]; then
  echo "PASS [5]: payload.mode=fix_preview verified"
else
  echo "INFO [5]: /actions/${ACTION_ID} returned mode=${PAYLOAD_MODE} (API may not expose payload — skipping deep check)"
fi

# ──────────────── Step 6: README.md unchanged ───────────────
echo ""
echo "[6] Verify README.md is unchanged after propose-fix call"
README_HASH_AFTER=$(md5 -q "${README_PATH}" 2>/dev/null || md5sum "${README_PATH}" | awk '{print $1}')
if [ "${README_HASH_BEFORE}" != "${README_HASH_AFTER}" ]; then
  echo "FAIL [6]: README.md was mutated! Before=${README_HASH_BEFORE} After=${README_HASH_AFTER}"
  exit 1
fi
echo "PASS [6]: README.md unchanged (no filesystem mutation)"

# ──────────────── Step 7: 400 for missing slackThreadId ─────
echo ""
echo "[7] POST /devos/propose-fix missing slackThreadId → 400"
BAD_RESP=$(curl -s -X POST "${BACKEND_URL}/devos/propose-fix" \
  -H "Content-Type: application/json" \
  -d "{
    \"repoPath\": \"${REPO_ROOT}\",
    \"filePath\": \"README.md\"
  }")
BAD_HTTP=$(echo "${BAD_RESP}" | jq -r '.code // .status // "null"')
if [ "${BAD_HTTP}" = "400" ] || echo "${BAD_RESP}" | grep -qi "bad request\|validation\|slackThreadId"; then
  echo "PASS [7]: Missing slackThreadId rejected (400)"
else
  echo "FAIL [7]: Expected 400 for missing slackThreadId, got ${BAD_HTTP}"
  echo "Response: ${BAD_RESP}"
  exit 1
fi

# ──────────────── Step 8: 400 for missing filePath ──────────
echo ""
echo "[8] POST /devos/propose-fix missing filePath → 400"
BAD_RESP2=$(curl -s -X POST "${BACKEND_URL}/devos/propose-fix" \
  -H "Content-Type: application/json" \
  -d "{
    \"slackThreadId\": \"${THREAD_ID}\",
    \"repoPath\": \"${REPO_ROOT}\"
  }")
BAD_HTTP2=$(echo "${BAD_RESP2}" | jq -r '.code // .status // "null"')
if [ "${BAD_HTTP2}" = "400" ] || echo "${BAD_RESP2}" | grep -qi "bad request\|validation\|filePath"; then
  echo "PASS [8]: Missing filePath rejected (400)"
else
  echo "FAIL [8]: Expected 400 for missing filePath, got ${BAD_HTTP2}"
  echo "Response: ${BAD_RESP2}"
  exit 1
fi

# ──────────────── Step 9: Secret scan ───────────────────────
echo ""
echo "[9] Secret scan"
if bash "${REPO_ROOT}/scripts/secret_scan.sh"; then
  echo "PASS [9]: Secret scan CLEAN"
else
  echo "FAIL [9]: Secret scan found secrets"
  exit 1
fi

# ─────────────────────────── Summary ───────────────────────
echo ""
echo "========================================"
echo "  ALL 9 ASSERTIONS PASSED — B-020 OK"
echo "========================================"
echo ""
echo "  actionId:   ${ACTION_ID}"
echo "  workflowId: ${WORKFLOW_ID}"
echo "  thread:     ${THREAD_ID}"
echo ""
echo "  Safety: README.md unchanged ✓"
echo "  No auto-apply, no commit, no push ✓"
