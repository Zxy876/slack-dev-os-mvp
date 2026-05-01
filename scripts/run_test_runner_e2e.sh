#!/usr/bin/env bash
# run_test_runner_e2e.sh — Slack Dev OS B-019 Test Command Runner E2E (local)
#
# Flow:
#   1. POST /devos/run-test with "bash scripts/secret_scan.sh" → PASSED
#   2. POST /devos/run-test with disallowed command → 400
#   3. POST /devos/run-test with nonexistent repoPath → 400
#   4. POST /devos/run-test in fixture repo with failing scan.sh → FAILED (HTTP 200)
#   5. POST /devos/run-test with oversized timeout → clamped, still PASSED
#
# Prerequisites:
#   - Backend running at $ASYNCAIFLOW_URL (default http://localhost:8080)
#   - Set SKIP_BACKEND=1 to reuse an already-running backend
#
# Usage:
#   SKIP_BACKEND=1 bash scripts/run_test_runner_e2e.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_URL="${ASYNCAIFLOW_URL:-http://localhost:8080}"
MAX_WAIT=120
BACKEND_LOG="/tmp/devos-run-test-backend.log"
BACKEND_PID=""
THREAD_ID="C-RUN-TEST-E2E/$(date +%s).000001"

echo "==== Slack Dev OS — Test Command Runner E2E (B-019) ===="
echo ""

# ───────────────────────── cleanup ──────────────────────────
cleanup() {
  if [ -n "$BACKEND_PID" ]; then
    kill "$BACKEND_PID" 2>/dev/null && echo "Backend stopped" || true
  fi
}
trap cleanup EXIT

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

# ───────────────────────── Step 1: PASSED ───────────────────
echo ""
echo "[1] POST /devos/run-test — bash scripts/secret_scan.sh → PASSED"
RESP=$(curl -s -X POST "${BACKEND_URL}/devos/run-test" \
  -H "Content-Type: application/json" \
  -d "{
    \"repoPath\": \"${REPO_ROOT}\",
    \"slackThreadId\": \"${THREAD_ID}\",
    \"command\": \"bash scripts/secret_scan.sh\",
    \"timeoutSeconds\": 30
  }")
echo "    Response: $RESP"

STATUS=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('status',''))" 2>/dev/null || true)
EXIT_CODE=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('exitCode',''))" 2>/dev/null || true)

if [ "$STATUS" != "PASSED" ]; then
  echo "FAIL: expected PASSED, got: ${STATUS}"
  exit 1
fi
if [ "$EXIT_CODE" != "0" ]; then
  echo "FAIL: expected exitCode=0, got: ${EXIT_CODE}"
  exit 1
fi
echo "    PASS: status=PASSED exitCode=0"

# ───────────────────────── Step 2: disallowed command → 400 ─
echo ""
echo "[2] POST /devos/run-test — disallowed command → 400"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BACKEND_URL}/devos/run-test" \
  -H "Content-Type: application/json" \
  -d "{
    \"repoPath\": \"${REPO_ROOT}\",
    \"slackThreadId\": \"${THREAD_ID}\",
    \"command\": \"rm -rf /tmp/evil\"
  }")
echo "    HTTP status: $HTTP_STATUS"
if [ "$HTTP_STATUS" != "400" ]; then
  echo "FAIL: expected 400, got: ${HTTP_STATUS}"
  exit 1
fi
echo "    PASS: disallowed command → 400"

# ───────────────────────── Step 3: nonexistent repoPath → 400 ─
echo ""
echo "[3] POST /devos/run-test — nonexistent repoPath → 400"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BACKEND_URL}/devos/run-test" \
  -H "Content-Type: application/json" \
  -d "{
    \"repoPath\": \"/no/such/path/does/not/exist\",
    \"slackThreadId\": \"${THREAD_ID}\",
    \"command\": \"bash scripts/secret_scan.sh\"
  }")
echo "    HTTP status: $HTTP_STATUS"
if [ "$HTTP_STATUS" != "400" ]; then
  echo "FAIL: expected 400, got: ${HTTP_STATUS}"
  exit 1
fi
echo "    PASS: nonexistent repoPath → 400"

# ───────────────────────── Step 4: failing command → FAILED ─
echo ""
echo "[4] POST /devos/run-test — fixture repo with failing scan → FAILED (HTTP 200)"
FIXTURE_REPO="/tmp/devos-run-test-fixture"
rm -rf "$FIXTURE_REPO"
mkdir -p "${FIXTURE_REPO}/scripts"
cat > "${FIXTURE_REPO}/scripts/secret_scan.sh" << 'FAIL_SCRIPT'
#!/usr/bin/env bash
echo "FAIL: simulated secret found in fixture"
exit 1
FAIL_SCRIPT
chmod +x "${FIXTURE_REPO}/scripts/secret_scan.sh"

RESP=$(curl -s -X POST "${BACKEND_URL}/devos/run-test" \
  -H "Content-Type: application/json" \
  -d "{
    \"repoPath\": \"${FIXTURE_REPO}\",
    \"slackThreadId\": \"${THREAD_ID}\",
    \"command\": \"bash scripts/secret_scan.sh\",
    \"timeoutSeconds\": 15
  }")
echo "    Response: $RESP"

STATUS=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('status',''))" 2>/dev/null || true)
SUCCESS=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('success',''))" 2>/dev/null || true)
EXIT_CODE=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('exitCode',''))" 2>/dev/null || true)

if [ "$STATUS" != "FAILED" ]; then
  echo "FAIL: expected status=FAILED, got: ${STATUS}"
  exit 1
fi
if [ "$SUCCESS" != "True" ] && [ "$SUCCESS" != "true" ]; then
  echo "FAIL: expected success=true even when test fails, got: ${SUCCESS}"
  exit 1
fi
if [ "$EXIT_CODE" != "1" ]; then
  echo "FAIL: expected exitCode=1, got: ${EXIT_CODE}"
  exit 1
fi
echo "    PASS: status=FAILED exitCode=1, success=true (HTTP 200)"

# ───────────────────────── Step 5: oversized timeout clamped ─
echo ""
echo "[5] POST /devos/run-test — timeoutSeconds=999 (clamped) → still PASSED"
RESP=$(curl -s -X POST "${BACKEND_URL}/devos/run-test" \
  -H "Content-Type: application/json" \
  -d "{
    \"repoPath\": \"${REPO_ROOT}\",
    \"slackThreadId\": \"${THREAD_ID}\",
    \"command\": \"bash scripts/secret_scan.sh\",
    \"timeoutSeconds\": 999
  }")
echo "    Response: $RESP"

STATUS=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('status',''))" 2>/dev/null || true)
if [ "$STATUS" != "PASSED" ]; then
  echo "FAIL: expected PASSED even with oversized timeout, got: ${STATUS}"
  exit 1
fi
echo "    PASS: oversized timeout clamped, command still ran normally"

# ───────────────────────── secret scan ──────────────────────
echo ""
echo "[6] Running secret scan..."
if bash "${REPO_ROOT}/scripts/secret_scan.sh"; then
  echo "    PASS: no secrets detected"
else
  echo "FAIL: secret scan found issues"
  exit 1
fi

# ───────────────────────── Done ─────────────────────────────
echo ""
echo "===================================================="
echo "B-019 Test Command Runner E2E: ALL ASSERTIONS PASSED"
echo "  - allowed command (secret_scan.sh): PASSED exitCode=0"
echo "  - disallowed command: 400 rejected"
echo "  - nonexistent repoPath: 400 rejected"
echo "  - fixture failing scan: FAILED (HTTP 200, not 500)"
echo "  - oversized timeout: clamped, command completed normally"
echo "  - secret scan: PASS"
echo "===================================================="
