"""
slack_bridge.py — B-021.5 Slack Minimal Loop Bridge
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Slack Dev OS — Slack event → /devos/start syscall adapter

此模块实现最小 Slack → DevOS 桥接逻辑：
  - 解析 Slack message event
  - 识别 "devos:" 前缀的消息
  - 构造 /devos/start 请求并调用后端
  - 通过 post_to_slack() 将结果回帖到 Slack thread

设计边界（禁止越界）：
  - 只做输入适配，不执行代码
  - 不直接调用 LLM
  - 不直接修改 repo / workspace 文件
  - 不调用 apply-patch / run-test / git-commit
  - 只把 Slack message 转换成 /devos/start syscall

环境变量：
  ASYNCAIFLOW_URL          default http://localhost:8080
  DEVOS_BRIDGE_PREFIX      default "devos:"
  DEVOS_DEFAULT_REPO_PATH  optional; included in payload when set
  DEVOS_BRIDGE_DRY_RUN     default false; true → 仅打印 payload，不调用后端
"""
from __future__ import annotations

import json
import logging
import os
from typing import Optional

import requests

LOGGER = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────
# 配置（全部从环境变量读取，不写 hardcoded 值）
# ─────────────────────────────────────────────────────────────

def _base_url() -> str:
    return os.environ.get("ASYNCAIFLOW_URL", "http://localhost:8080").rstrip("/")

def _bridge_prefix() -> str:
    return os.environ.get("DEVOS_BRIDGE_PREFIX", "devos:")

def _default_repo_path() -> Optional[str]:
    val = os.environ.get("DEVOS_DEFAULT_REPO_PATH", "")
    return val.strip() or None

def _dry_run() -> bool:
    return os.environ.get("DEVOS_BRIDGE_DRY_RUN", "false").lower() in ("1", "true", "yes")


# ─────────────────────────────────────────────────────────────
# 核心解析函数
# ─────────────────────────────────────────────────────────────

def parse_devos_command(text: str, prefix: Optional[str] = None) -> Optional[str]:
    """
    检查消息文本是否以 prefix 开头，提取后续 instruction。

    参数：
      text:   Slack 消息文本
      prefix: 命令前缀，默认从 DEVOS_BRIDGE_PREFIX 环境变量读取

    返回：
      str  — 去除前缀和首尾空白后的 instruction（非空）
      None — 文本不以 prefix 开头，或 instruction 为空
    """
    if prefix is None:
        prefix = _bridge_prefix()
    prefix_lower = prefix.lower()
    text_lower = text.strip().lower()
    if not text_lower.startswith(prefix_lower):
        return None
    instruction = text.strip()[len(prefix):].strip()
    return instruction if instruction else None


def build_slack_thread_id(event: dict) -> str:
    """
    从 Slack event 构造 slackThreadId。

    格式：channel/thread_ts

    规则：
      - 若 event.thread_ts 存在且非空 → channel/thread_ts
      - 否则 → channel/ts（本条消息自身时间戳，作为 thread 起点）

    Slack 约定：thread_ts == ts 意味着消息本身是 thread root；
    thread_ts != ts 意味着消息是某个 thread 的回复。
    """
    channel = event.get("channel", "")
    thread_ts = event.get("thread_ts") or event.get("ts", "")
    return f"{channel}/{thread_ts}"


def build_devos_start_payload(
    event: dict,
    instruction: str,
    default_repo_path: Optional[str] = None,
) -> dict:
    """
    构造 POST /devos/start 请求 payload。

    必填字段：
      text          — 从 event 中提取的 instruction
      slackThreadId — channel/thread_ts 格式

    可选字段：
      repoPath — 来自 default_repo_path 参数（通常从环境变量读取）
    """
    slack_thread_id = build_slack_thread_id(event)
    payload: dict = {
        "text": instruction,
        "slackThreadId": slack_thread_id,
    }
    repo = default_repo_path if default_repo_path is not None else _default_repo_path()
    if repo:
        payload["repoPath"] = repo
    return payload


def call_devos_start(payload: dict, base_url: Optional[str] = None) -> dict:
    """
    调用 POST /devos/start 并返回响应 data 字段。

    成功：返回 {"success": True, "actionId": int, "workflowId": int, ...}
    失败：抛出 RuntimeError 包含 HTTP 状态码和错误信息

    注意：不打印 token，不记录完整 payload（避免泄漏 repoPath 等用户信息）
    """
    url = (base_url or _base_url()) + "/devos/start"
    session = requests.Session()
    session.trust_env = False  # 忽略本地代理，与 worker.py 保持一致
    try:
        resp = session.post(url, json=payload, timeout=15)
        resp.raise_for_status()
        body = resp.json()
        data = body.get("data") or body
        return {"success": True, **data}
    except requests.HTTPError as exc:
        status_code = exc.response.status_code if exc.response is not None else 0
        try:
            err_body = exc.response.json() if exc.response is not None else {}
        except Exception:
            err_body = {}
        msg = err_body.get("message") or str(exc)
        raise RuntimeError(f"devos/start HTTP {status_code}: {msg}") from exc
    except requests.RequestException as exc:
        raise RuntimeError(f"devos/start connection error: {exc}") from exc


# ─────────────────────────────────────────────────────────────
# 主入口
# ─────────────────────────────────────────────────────────────

def handle_slack_event(
    event: dict,
    config: Optional[dict] = None,
) -> dict:
    """
    处理一条 Slack message event，返回处理结果。

    参数：
      event:  Slack event dict（message event 格式）
      config: 可选配置覆盖，支持以下 key：
                base_url, prefix, default_repo_path, dry_run

    返回结构：
      {
        "handled":       bool,          # True = 已处理（无论成功失败）
        "reason":        str,           # 处理决策说明
        "slackThreadId": str | None,    # 解析出的 slackThreadId
        "actionId":      int | None,    # 成功时后端返回的 actionId
        "replyText":     str,           # 应当回帖到 Slack 的文本
      }

    不变量：
      - bot_message 或带 subtype 的消息 → handled=False（不处理）
      - 非 "devos:" 前缀的消息 → handled=False
      - 空 instruction → handled=False
      - dry_run=true → handled=True，不调用后端，replyText 包含 payload 预览
      - 后端调用失败 → handled=True，replyText 包含安全错误提示
    """
    cfg = config or {}
    prefix       = cfg.get("prefix", _bridge_prefix())
    base_url     = cfg.get("base_url", _base_url())
    repo_path    = cfg.get("default_repo_path", _default_repo_path())
    dry_run      = cfg.get("dry_run", _dry_run())

    # ── 1. 忽略 bot 消息和带 subtype 的消息（avoid loops）
    event_type = event.get("type", "")
    subtype = event.get("subtype", "")
    bot_id = event.get("bot_id", "")

    if subtype or bot_id:
        return {
            "handled": False,
            "reason": f"ignored: bot/subtype message (subtype={subtype!r}, bot_id={bot_id!r})",
            "slackThreadId": None,
            "actionId": None,
            "replyText": "",
        }

    if event_type != "message" and event_type != "":
        return {
            "handled": False,
            "reason": f"ignored: event type is not 'message' (got {event_type!r})",
            "slackThreadId": None,
            "actionId": None,
            "replyText": "",
        }

    # ── 2. 检查 devos: 前缀
    text = event.get("text", "") or ""
    instruction = parse_devos_command(text, prefix=prefix)

    if instruction is None:
        return {
            "handled": False,
            "reason": f"ignored: text does not start with prefix {prefix!r}",
            "slackThreadId": None,
            "actionId": None,
            "replyText": "",
        }

    # ── 3. 构造 slackThreadId
    slack_thread_id = build_slack_thread_id(event)

    # ── 4. 构造 /devos/start payload
    payload = build_devos_start_payload(event, instruction, default_repo_path=repo_path)

    # ── 5. dry-run 模式：只打印 payload，不调用后端
    if dry_run:
        LOGGER.info("[dry-run] Would POST /devos/start: %s", json.dumps(payload))
        reply = (
            f"[DRY RUN] DevOS bridge would call /devos/start with:\n"
            f"• instruction: {instruction}\n"
            f"• slackThreadId: {slack_thread_id}"
        )
        return {
            "handled": True,
            "reason": "dry-run mode",
            "slackThreadId": slack_thread_id,
            "actionId": None,
            "replyText": reply,
        }

    # ── 6. 调用后端
    try:
        result = call_devos_start(payload, base_url=base_url)
        action_id = result.get("actionId") or result.get("id")
        LOGGER.info("devos/start succeeded: actionId=%s slackThreadId=%s",
                    action_id, slack_thread_id)
        reply = f"DevOS session started — action {action_id}"
        return {
            "handled": True,
            "reason": "devos command dispatched",
            "slackThreadId": slack_thread_id,
            "actionId": action_id,
            "replyText": reply,
        }
    except RuntimeError as exc:
        LOGGER.warning("devos/start failed for thread %s: %s", slack_thread_id, exc)
        reply = f"DevOS failed to start: {exc}"
        return {
            "handled": True,
            "reason": "backend error",
            "slackThreadId": slack_thread_id,
            "actionId": None,
            "replyText": str(reply),
        }


# ─────────────────────────────────────────────────────────────
# CLI entry point (for scripts/run_slack_bridge_mock.sh)
# ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import sys

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [slack-bridge] %(levelname)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S",
    )

    if len(sys.argv) < 2:
        print("Usage: python slack_bridge.py '<event_json>'", file=sys.stderr)
        sys.exit(1)

    raw = sys.argv[1]
    try:
        ev = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"ERROR: invalid JSON: {e}", file=sys.stderr)
        sys.exit(1)

    outcome = handle_slack_event(ev)
    print(json.dumps(outcome, indent=2))
    if outcome.get("handled") and "failed" in outcome.get("replyText", "").lower():
        sys.exit(1)
