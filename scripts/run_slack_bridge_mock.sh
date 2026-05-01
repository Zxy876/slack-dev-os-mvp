#!/usr/bin/env bash
# run_slack_bridge_mock.sh — B-021.5 Slack Minimal Loop Bridge Mock Runner
#
# 使用模拟 Slack event 测试 slack_bridge.py 的 handle_slack_event，
# 不需要真实 Slack token 或后端连接。
#
# 用法：
#   # dry-run（默认）：只打印 payload，不调用后端
#   bash scripts/run_slack_bridge_mock.sh
#
#   # 真实调用（需要后端运行在 ASYNCAIFLOW_URL）
#   DEVOS_BRIDGE_DRY_RUN=false bash scripts/run_slack_bridge_mock.sh
#
#   # 自定义指令
#   DEVOS_MOCK_TEXT="devos: run all tests" bash scripts/run_slack_bridge_mock.sh
#
# 环境变量：
#   ASYNCAIFLOW_URL       default: http://localhost:8080
#   DEVOS_BRIDGE_DRY_RUN  default: true
#   DEVOS_MOCK_TEXT       模拟消息文本，default: "devos: summarize this repo"
#   DEVOS_MOCK_CHANNEL    模拟频道 ID，default: C-MOCK-BRIDGE
#   DEVOS_MOCK_TS         模拟时间戳，default: 1710000000.000100
#
# 注意：此脚本不打印 SLACK_BOT_TOKEN。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKER_DIR="${REPO_ROOT}/python-workers/devos_chat_worker"

ASYNCAIFLOW_URL="${ASYNCAIFLOW_URL:-http://localhost:8080}"
DEVOS_BRIDGE_DRY_RUN="${DEVOS_BRIDGE_DRY_RUN:-true}"
DEVOS_MOCK_TEXT="${DEVOS_MOCK_TEXT:-devos: summarize this repo}"
DEVOS_MOCK_CHANNEL="${DEVOS_MOCK_CHANNEL:-C-MOCK-BRIDGE}"
DEVOS_MOCK_TS="${DEVOS_MOCK_TS:-1710000000.000100}"

echo "================================================="
echo " Slack Dev OS — Bridge Mock Runner (B-021.5)"
echo "================================================="
echo ""
echo "  DRY_RUN:    ${DEVOS_BRIDGE_DRY_RUN}"
echo "  BACKEND:    ${ASYNCAIFLOW_URL}"
echo "  MESSAGE:    ${DEVOS_MOCK_TEXT}"
echo "  CHANNEL:    ${DEVOS_MOCK_CHANNEL}"
echo "  TS:         ${DEVOS_MOCK_TS}"
echo ""

# ── Health check (only when not in dry-run) ─────────────────
if [[ "${DEVOS_BRIDGE_DRY_RUN}" != "true" ]]; then
  echo "[preflight] Checking backend at ${ASYNCAIFLOW_URL}/health ..."
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${ASYNCAIFLOW_URL}/health" 2>/dev/null || echo "000")
  if [[ "${HTTP_CODE}" != "200" ]]; then
    echo "[WARN] Backend not reachable at ${ASYNCAIFLOW_URL}/health (HTTP ${HTTP_CODE})."
    echo "       Start it with: mvn spring-boot:run -Dspring-boot.run.profiles=local"
    echo "       Or set DEVOS_BRIDGE_DRY_RUN=true to run without backend."
    exit 1
  fi
  echo "[OK]  Backend healthy (HTTP 200)"
  echo ""
fi

# ── Run bridge via Python here-doc ──────────────────────────
python3 - <<PYEOF
import sys
import os
import json
import logging

sys.path.insert(0, "${WORKER_DIR}")

os.environ["DEVOS_BRIDGE_DRY_RUN"] = "${DEVOS_BRIDGE_DRY_RUN}"
os.environ["ASYNCAIFLOW_URL"]      = "${ASYNCAIFLOW_URL}"
# DEMO_MODE prevents worker.py from requiring LLM keys on import
os.environ.setdefault("DEMO_MODE", "true")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [bridge-mock] %(levelname)s %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)

from slack_bridge import handle_slack_event  # noqa: E402

mock_event = {
    "type": "message",
    "channel": "${DEVOS_MOCK_CHANNEL}",
    "ts": "${DEVOS_MOCK_TS}",
    "text": "${DEVOS_MOCK_TEXT}",
}

print("── Mock Slack Event ──────────────────────────────")
print(json.dumps(mock_event, indent=2))
print("")

result = handle_slack_event(mock_event)

print("── Bridge Result ─────────────────────────────────")
print(json.dumps(result, indent=2))
print("")

if result.get("handled"):
    print(f"[OK]  Bridge handled the event.")
    if result.get("replyText"):
        print(f"      Slack reply: {result['replyText']}")
    if result.get("actionId"):
        print(f"      Action ID:   {result['actionId']}")
else:
    print(f"[INFO] Bridge did not handle the event: {result.get('reason', 'unknown reason')}")

PYEOF

echo ""
echo "================================================="
echo " Done."
echo "================================================="
