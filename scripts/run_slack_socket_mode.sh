#!/usr/bin/env bash
# run_slack_socket_mode.sh — B-023 Slack Socket Mode Adapter Launcher
#
# 启动 socket_mode_adapter.py，连接 Slack Socket Mode 并监听真实消息事件。
# 无需公网 URL 或 ngrok。仅适合本地个人开发。
#
# 使用前提：
#   1. 已有 Slack App 并开启 Socket Mode (App Settings → Features → Socket Mode)
#   2. 已创建 App-Level Token，scope: connections:write（以 xapp- 开头）
#   3. Bot Token scope: chat:write（以 xoxb- 开头）
#   4. 订阅 bot events: message.channels（或 message.im / message.groups）
#   5. 已将 Bot 邀请到目标频道
#
# 用法：
#   # 1. 设置环境变量（可以 source .env 或直接 export）
#   source python-workers/devos_chat_worker/.env
#
#   # 2. 可选：dry-run 模式（不调用后端，不回复 Slack）
#   export DEVOS_SOCKET_DRY_RUN=true
#
#   # 3. 启动后端（非 dry-run 时必须）
#   mvn spring-boot:run -Dspring-boot.run.profiles=local &
#
#   # 4. 启动 socket adapter
#   bash scripts/run_slack_socket_mode.sh
#
#   # 5. 在 Slack 频道发送：
#   devos: ask hello
#   devos: preview README.md replace "Old" with "New"
#
# 环境变量：
#   SLACK_APP_TOKEN           必须：xapp-... (scope: connections:write)
#   SLACK_BOT_TOKEN           必须：xoxb-... (scope: chat:write)
#   ASYNCAIFLOW_URL           default: http://localhost:8080
#   DEVOS_BRIDGE_PREFIX       default: devos:
#   DEVOS_DEFAULT_REPO_PATH   required for preview/test/commit intents
#   DEVOS_SOCKET_DRY_RUN      default: false; true → 仅打印，不调用后端
#
# 注意：此脚本不打印 SLACK_BOT_TOKEN 或 SLACK_APP_TOKEN 的完整值。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKER_DIR="${REPO_ROOT}/python-workers/devos_chat_worker"

# ── Env var status check ─────────────────────────────────────
echo "================================================="
echo " Slack Dev OS — Socket Mode Adapter (B-023)"
echo "================================================="
echo ""

_check_env() {
  local var="$1"
  local required="$2"
  local val="${!var:-}"
  if [[ -z "$val" ]]; then
    if [[ "$required" == "required" ]]; then
      echo "  [MISSING] $var  ← REQUIRED"
    else
      echo "  [UNSET]   $var  (optional)"
    fi
  else
    # Show only first 8 chars to avoid leaking secrets
    local preview="${val:0:8}***"
    echo "  [SET]     $var=${preview}"
  fi
}

_check_env "SLACK_APP_TOKEN"          required
_check_env "SLACK_BOT_TOKEN"          required
_check_env "ASYNCAIFLOW_URL"          optional
_check_env "DEVOS_DEFAULT_REPO_PATH"  optional
_check_env "DEVOS_SOCKET_DRY_RUN"     optional

echo ""

# ── Fail fast if required tokens are missing ─────────────────
if [[ -z "${SLACK_APP_TOKEN:-}" ]]; then
  echo "[ERROR] SLACK_APP_TOKEN is not set."
  echo "        Create an App-Level Token in Slack App settings:"
  echo "        App Settings → Features → Socket Mode → App-Level Tokens"
  echo "        Required scope: connections:write"
  exit 1
fi

if [[ -z "${SLACK_BOT_TOKEN:-}" ]]; then
  echo "[ERROR] SLACK_BOT_TOKEN is not set."
  echo "        Find it in: App Settings → OAuth & Permissions → Bot User OAuth Token"
  echo "        Required scope: chat:write"
  exit 1
fi

# ── Health check for backend (only when not in dry-run) ──────
DRY_RUN="${DEVOS_SOCKET_DRY_RUN:-false}"
BACKEND_URL="${ASYNCAIFLOW_URL:-http://localhost:8080}"

if [[ "${DRY_RUN}" != "true" ]]; then
  echo "[preflight] Checking backend at ${BACKEND_URL}/health ..."
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BACKEND_URL}/health" 2>/dev/null || echo "000")
  if [[ "${HTTP_CODE}" != "200" ]]; then
    echo "[WARN] Backend not reachable at ${BACKEND_URL}/health (HTTP ${HTTP_CODE})."
    echo "       Start it with: mvn spring-boot:run -Dspring-boot.run.profiles=local"
    echo "       Or set DEVOS_SOCKET_DRY_RUN=true to run without backend."
    exit 1
  fi
  echo "[OK]  Backend healthy (HTTP 200)"
  echo ""
fi

# ── Launch ───────────────────────────────────────────────────
echo "Starting socket mode adapter..."
echo "  BACKEND:  ${BACKEND_URL}"
echo "  DRY_RUN:  ${DRY_RUN}"
echo "  PREFIX:   ${DEVOS_BRIDGE_PREFIX:-devos:}"
echo ""
echo "Send a message in Slack: devos: ask hello"
echo "Press Ctrl+C to stop."
echo ""

cd "${WORKER_DIR}"
exec python3 socket_mode_adapter.py
