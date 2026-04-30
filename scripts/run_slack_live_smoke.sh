#!/usr/bin/env bash
# run_slack_live_smoke.sh — B-010-live-personal: Personal Slack Live Smoke Test
#
# Assumes AsyncAIFlow backend is already running on ASYNCAIFLOW_URL.
# To start the backend:  mvn spring-boot:run -Dspring-boot.run.profiles=local
#
# Required env:
#   ASYNCAIFLOW_URL              (default: http://localhost:8080)
#   SLACK_BOT_TOKEN              xoxb-... (real Slack bot token)
#   DEVOS_LIVE_SLACK_THREAD_ID   C08XXXXXX/1234567890.123456  (preferred)
#     OR
#   DEVOS_LIVE_SLACK_CHANNEL     C08XXXXXX  (post to channel top-level)
#   GLM_API_KEY or OPENAI_API_KEY, unless DEMO_MODE=true
#
# Usage:
#   source python-workers/devos_chat_worker/.env   # load your .env
#   bash scripts/run_slack_live_smoke.sh
#
# This script NEVER prints SLACK_BOT_TOKEN, GLM_API_KEY, or OPENAI_API_KEY.
# CI must NOT run this script (it requires real secrets).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASYNCAIFLOW_URL="${ASYNCAIFLOW_URL:-http://localhost:8080}"
WORKER_DIR="${REPO_ROOT}/python-workers/devos_chat_worker"

echo "================================================="
echo " Slack Dev OS — Personal Slack Live Smoke (B-010-live-personal)"
echo "================================================="
echo ""

# ── 1. Preflight: check required env ────────────────────────

PREFLIGHT_OK=true

if [[ -z "${SLACK_BOT_TOKEN:-}" ]]; then
  echo "[FAIL] SLACK_BOT_TOKEN is not set."
  echo "       Create a Slack App with scope 'chat:write', install it to your workspace,"
  echo "       then set: export SLACK_BOT_TOKEN=xoxb-..."
  PREFLIGHT_OK=false
fi

SLACK_TARGET=""
if [[ -n "${DEVOS_LIVE_SLACK_THREAD_ID:-}" ]]; then
  SLACK_TARGET="${DEVOS_LIVE_SLACK_THREAD_ID}"
  echo "[OK]  SLACK_TARGET (thread): ${SLACK_TARGET}"
elif [[ -n "${DEVOS_LIVE_SLACK_CHANNEL:-}" ]]; then
  SLACK_TARGET="${DEVOS_LIVE_SLACK_CHANNEL}"
  echo "[OK]  SLACK_TARGET (channel): ${SLACK_TARGET}"
else
  echo "[FAIL] Neither DEVOS_LIVE_SLACK_THREAD_ID nor DEVOS_LIVE_SLACK_CHANNEL is set."
  echo "       Set DEVOS_LIVE_SLACK_THREAD_ID=C08XXXXXX/1234567890.123456"
  echo "       or  DEVOS_LIVE_SLACK_CHANNEL=C08XXXXXX"
  PREFLIGHT_OK=false
fi

DEMO_MODE="${DEMO_MODE:-false}"
if [[ "${DEMO_MODE}" == "true" ]]; then
  echo "[OK]  DEMO_MODE=true — no LLM key required (stub response)"
else
  if [[ -z "${GLM_API_KEY:-}" && -z "${OPENAI_API_KEY:-}" ]]; then
    echo "[FAIL] DEMO_MODE=false but neither GLM_API_KEY nor OPENAI_API_KEY is set."
    echo "       Set DEMO_MODE=true for stub, or provide a real LLM key."
    PREFLIGHT_OK=false
  else
    HAVE_LLM=""
    [[ -n "${GLM_API_KEY:-}" ]] && HAVE_LLM="GLM (redacted: ${GLM_API_KEY:0:4}***)"
    [[ -n "${OPENAI_API_KEY:-}" && -z "$HAVE_LLM" ]] && HAVE_LLM="OpenAI (redacted: ${OPENAI_API_KEY:0:4}***)"
    echo "[OK]  LLM backend: ${HAVE_LLM}"
  fi
fi

if ! $PREFLIGHT_OK; then
  echo ""
  echo "Preflight FAILED. Fix the above issues and re-run."
  exit 1
fi

echo ""

# ── 2. Config dry-run via run_production_config_check.sh ────

echo "[step 1/4] Running production config check..."
DEMO_MODE="${DEMO_MODE}" bash "${REPO_ROOT}/scripts/run_production_config_check.sh" || {
  echo "[FAIL] Production config check failed. Aborting live smoke."
  exit 1
}
echo ""

# ── 3. Check backend health ─────────────────────────────────

echo "[step 2/4] Checking backend health at ${ASYNCAIFLOW_URL}..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${ASYNCAIFLOW_URL}/health" 2>/dev/null || echo "000")
if [[ "$HTTP_CODE" != "200" ]]; then
  echo "[FAIL] Backend not reachable at ${ASYNCAIFLOW_URL}/health (HTTP ${HTTP_CODE})."
  echo "       Start it with: mvn spring-boot:run -Dspring-boot.run.profiles=local"
  exit 1
fi
echo "[OK]  Backend healthy (HTTP 200)"
echo ""

# ── 4. POST /devos/start ─────────────────────────────────────

echo "[step 3/4] Sending live smoke request..."
START_PAYLOAD="{\"text\":\"Slack Dev OS live smoke test — please reply briefly.\",\"slackThreadId\":\"${SLACK_TARGET}\"}"

RESPONSE=$(curl -s -X POST "${ASYNCAIFLOW_URL}/devos/start" \
  -H "Content-Type: application/json" \
  -d "${START_PAYLOAD}" \
  --max-time 10)

echo "  POST /devos/start → ${RESPONSE}"

ACTION_ID=$(echo "${RESPONSE}" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['data']['actionId'])" 2>/dev/null || echo "")
if [[ -z "${ACTION_ID}" ]]; then
  echo "[FAIL] Could not extract actionId from response."
  exit 1
fi
echo "[OK]  Action created: id=${ACTION_ID}"
echo ""

# ── 5. Poll until COMPLETED (timeout 60s) ───────────────────

echo "[step 4/4] Polling action ${ACTION_ID} (timeout 60s)..."
FINAL_STATUS=""
FINAL_RESPONSE=""
FINAL_SLACK=""

for i in $(seq 1 30); do
  POLL=$(curl -s "${ASYNCAIFLOW_URL}/action/${ACTION_ID}" --max-time 5 2>/dev/null || echo "{}")
  STATUS=$(echo "${POLL}" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('status',''))" 2>/dev/null || echo "")

  if [[ "${STATUS}" == "COMPLETED" || "${STATUS}" == "SUCCEEDED" ]]; then
    FINAL_STATUS="${STATUS}"
    FINAL_RESPONSE=$(echo "${POLL}" | python3 -c "
import json, sys
d = json.load(sys.stdin)
result_raw = d.get('data', {}).get('result', '{}') or '{}'
result = json.loads(result_raw)
print(result.get('response', '')[:200])
" 2>/dev/null || echo "(no response extracted)")
    break
  elif [[ "${STATUS}" == "FAILED" || "${STATUS}" == "DEAD_LETTER" ]]; then
    FINAL_STATUS="${STATUS}"
    break
  fi

  printf "  [%2d/30] status=%s...\n" "$i" "$STATUS"
  sleep 2
done

echo ""
if [[ "${FINAL_STATUS}" == "COMPLETED" || "${FINAL_STATUS}" == "SUCCEEDED" ]]; then
  echo "✅ Action ${ACTION_ID} ${FINAL_STATUS}"
  echo "   LLM response (first 200 chars): ${FINAL_RESPONSE}"
  echo ""
  echo "Slack post status:"
  if [[ -n "${SLACK_BOT_TOKEN:-}" ]]; then
    echo "   → Slack token present — worker should have posted to ${SLACK_TARGET}"
    echo "   → Check your Slack channel/thread to verify the message appeared."
  else
    echo "   → SLACK_BOT_TOKEN not set — Slack post was skipped (REQUIRE_SLACK_POST=false)."
  fi
  echo ""
  echo "================================================="
  echo " Slack Dev OS Live Smoke: PASSED"
  echo " (manual Slack verification required)"
  echo "================================================="
  exit 0
else
  echo "❌ Action ${ACTION_ID} ended with status=${FINAL_STATUS}"
  echo "   Full poll response: ${POLL}"
  echo ""
  echo "================================================="
  echo " Slack Dev OS Live Smoke: FAILED"
  echo "================================================="
  exit 1
fi
