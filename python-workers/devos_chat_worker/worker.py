"""
devos_chat_worker/worker.py
~~~~~~~~~~~~~~~~~~~~~~~~~~~
Slack Dev OS — devos_chat Worker（执行层 / Execution Unit）

OS 架构对应：
  - Worker      = 执行层 (Execution Layer)
  - poll_action = 从就绪队列认领 PCB (Dequeue from Ready Queue)
  - execute()   = 指令周期执行 (Instruction Cycle)
  - notepad     = L2 缓存 / 寄存器快照 (Working Memory Snapshot)
  - Slack API   = 总线回写 (Bus Write-back)

执行流程（最小指令周期）：
  1. 从 AsyncAIFlow 拉取 devos_chat action
  2. 解析 payload: {user_text, slack_thread_id}
  3. 读取 notepad_ref（若存在，注入到 prompt 上下文恢复）
  4. 调用 LLM 生成回复
  5. 通过 Slack API 将结果回写 Slack Thread（总线）
  6. 提交 SUCCEEDED 状态 + notepad 快照

环境变量：
  ASYNCAIFLOW_URL          = http://localhost:8080
  DEVOS_WORKER_ID          = devos-chat-worker-1
  POLL_INTERVAL_S          = 2.0
  OPENAI_API_KEY           — OpenAI key
  OPENAI_BASE_URL          — 可选，兼容 API 地址（GLM/本地模型）
  OPENAI_MODEL             = gpt-4o-mini
  GLM_API_KEY              — ZhipuAI GLM key（优先级高于 OpenAI）
  SLACK_BOT_TOKEN          — Slack Bot Token（xoxb-...）
  DEMO_MODE                = true  （无 LLM key 时使用 stub 回复）
"""
from __future__ import annotations

import json
import logging
import os
import time
from typing import Optional

import requests

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [devos-chat-worker] %(levelname)s %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)
LOGGER = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────
# 配置
# ─────────────────────────────────────────────────────────────
ASYNCAIFLOW_URL: str = os.environ.get("ASYNCAIFLOW_URL", "http://localhost:8080")
WORKER_ID: str = os.environ.get("DEVOS_WORKER_ID", "devos-chat-worker-1")
CAPABILITIES: list[str] = ["devos_chat"]
POLL_INTERVAL_S: float = float(os.environ.get("POLL_INTERVAL_S", "2"))
HEARTBEAT_INTERVAL_S: float = float(os.environ.get("HEARTBEAT_INTERVAL_S", "10"))
DEMO_MODE: bool = os.environ.get("DEMO_MODE", "").lower() in ("1", "true", "yes")

SYSTEM_PROMPT = """You are a world-class software engineering assistant in the Slack Dev OS.
You help developers by directly answering questions, writing code, designing solutions,
and providing actionable technical guidance.

When returning code, wrap it in appropriate markdown fences.
Be concise but complete. Prioritize working solutions.
"""

# ─────────────────────────────────────────────────────────────
# HTTP sessions (trust_env=False 避免本地代理干扰)
# ─────────────────────────────────────────────────────────────
_aiflow_session = requests.Session()
_aiflow_session.trust_env = False

_slack_session = requests.Session()
_slack_session.trust_env = False


# ─────────────────────────────────────────────────────────────
# AsyncAIFlow 客户端
# ─────────────────────────────────────────────────────────────

def _aiflow_post(path: str, body: dict) -> dict:
    resp = _aiflow_session.post(f"{ASYNCAIFLOW_URL}{path}", json=body, timeout=15)
    resp.raise_for_status()
    data = resp.json()
    if not data.get("success", False):
        raise RuntimeError(f"AsyncAIFlow {path} failed: {data.get('message', 'unknown error')}")
    return data


def register_worker() -> None:
    _aiflow_post("/worker/register", {
        "workerId": WORKER_ID,
        "capabilities": CAPABILITIES,
    })
    LOGGER.info("Worker %s registered with capabilities %s", WORKER_ID, CAPABILITIES)


def heartbeat() -> None:
    try:
        _aiflow_post("/worker/heartbeat", {"workerId": WORKER_ID})
    except Exception as exc:
        LOGGER.warning("Heartbeat failed: %s", exc)


def poll_action() -> Optional[dict]:
    """从 AsyncAIFlow 拉取一个 devos_chat action（PCB 认领）。"""
    resp = _aiflow_session.get(
        f"{ASYNCAIFLOW_URL}/action/poll",
        params={"workerId": WORKER_ID},
        timeout=10,
    )
    if resp.status_code == 204 or not resp.text.strip():
        return None
    resp.raise_for_status()
    data = resp.json()
    if not data.get("success", False):
        return None
    return data.get("data")


def submit_result(
    action_id: int,
    status: str,
    result: dict,
    error_message: Optional[str] = None,
) -> None:
    """提交 Action 执行结果，更新 PCB 状态。"""
    _aiflow_post("/action/result", {
        "workerId": WORKER_ID,
        "actionId": action_id,
        "status": status,
        "result": json.dumps(result, ensure_ascii=False),
        "errorMessage": error_message,
    })


def renew_lease(action_id: int) -> None:
    """续租 Action 执行锁，防止被 Watchdog 回收。"""
    try:
        _aiflow_post(f"/action/{action_id}/renew-lease", {"workerId": WORKER_ID})
    except Exception as exc:
        LOGGER.warning("Lease renewal failed for action %s: %s", action_id, exc)


# ─────────────────────────────────────────────────────────────
# LLM 调用（OpenAI / GLM / Demo Stub）
# ─────────────────────────────────────────────────────────────

def _call_openai(user_text: str, notepad: Optional[str]) -> str:
    import openai  # type: ignore
    client = openai.OpenAI(
        api_key=os.environ["OPENAI_API_KEY"],
        base_url=os.environ.get("OPENAI_BASE_URL") or None,
    )
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]

    # 上下文恢复：将 notepad 作为 L2 缓存注入（Context Restore）
    if notepad:
        messages.append({
            "role": "system",
            "content": f"[Context Restore — previous session snapshot]\n{notepad}",
        })

    messages.append({"role": "user", "content": user_text})

    resp = client.chat.completions.create(
        model=os.environ.get("OPENAI_MODEL", "gpt-4o-mini"),
        temperature=0.3,
        messages=messages,
    )
    return resp.choices[0].message.content.strip()


def _call_glm(user_text: str, notepad: Optional[str]) -> str:
    from zhipuai import ZhipuAI  # type: ignore
    client = ZhipuAI(
        api_key=os.environ["GLM_API_KEY"],
        base_url=os.environ.get("GLM_BASE_URL") or None,
    )
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    if notepad:
        messages.append({
            "role": "system",
            "content": f"[Context Restore — previous session snapshot]\n{notepad}",
        })
    messages.append({"role": "user", "content": user_text})

    resp = client.chat.completions.create(
        model=os.environ.get("GLM_MODEL", "glm-4"),
        temperature=0.3,
        messages=messages,
    )
    return resp.choices[0].message.content.strip()


def call_llm(user_text: str, notepad: Optional[str]) -> str:
    """
    LLM 调度：GLM > OpenAI > Demo Stub
    对应 OS 中"CPU 执行指令"环节。
    """
    if os.environ.get("GLM_API_KEY"):
        return _call_glm(user_text, notepad)
    if os.environ.get("OPENAI_API_KEY"):
        return _call_openai(user_text, notepad)
    if DEMO_MODE:
        LOGGER.warning("DEMO_MODE: No LLM key set — returning stub response")
        retry_note = f"\n[Notepad context was present]" if notepad else ""
        return (
            f"[DEMO] I received your request: \"{user_text[:100]}\"\n"
            f"This is a demo stub response from Slack Dev OS worker.{retry_note}"
        )
    raise RuntimeError(
        "No LLM API key configured. Set OPENAI_API_KEY, GLM_API_KEY, or DEMO_MODE=true."
    )


# ─────────────────────────────────────────────────────────────
# Slack 回写（总线写传播 / Bus Write-back）
# ─────────────────────────────────────────────────────────────

def post_to_slack(slack_thread_id: str, text: str) -> bool:
    """
    将 LLM 结果回写到 Slack Thread。

    slack_thread_id 格式：
      "<channel_id>/<thread_ts>"  例如 "C1234567890/1234567890.123456"
    或
      "<channel_id>"              （仅 channel，不在 thread 中回复）
    """
    token = os.environ.get("SLACK_BOT_TOKEN", "")
    if not token:
        LOGGER.warning("SLACK_BOT_TOKEN not set — skipping Slack post for thread %s", slack_thread_id)
        return False

    # 解析 channel 和 thread_ts
    parts = slack_thread_id.split("/", 1)
    channel = parts[0]
    thread_ts = parts[1] if len(parts) > 1 else None

    payload: dict = {
        "channel": channel,
        "text": text,
    }
    if thread_ts:
        payload["thread_ts"] = thread_ts

    try:
        resp = _slack_session.post(
            "https://slack.com/api/chat.postMessage",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json=payload,
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json()
        if not data.get("ok"):
            LOGGER.warning("Slack API error: %s", data.get("error", "unknown"))
            return False
        LOGGER.info("Slack message posted to channel=%s thread_ts=%s", channel, thread_ts)
        return True
    except Exception as exc:
        LOGGER.warning("Failed to post to Slack: %s", exc)
        return False


# ─────────────────────────────────────────────────────────────
# Worker 执行核心（指令周期）
# ─────────────────────────────────────────────────────────────

def execute(assignment: dict) -> tuple[str, dict, Optional[str]]:
    """
    单次 Action 执行（Instruction Cycle）：
      Thinking → Execute → Write-back

    返回: (status, result_dict, error_message)
    """
    action_id: int = assignment["actionId"]
    retry_count: int = assignment.get("retryCount", 0)
    slack_thread_id: Optional[str] = assignment.get("slackThreadId")
    notepad: Optional[str] = assignment.get("notepadRef")  # L2 寄存器快照

    # --- 解析 payload ---
    payload_raw = assignment.get("payload") or "{}"
    try:
        payload = json.loads(payload_raw)
    except json.JSONDecodeError:
        payload = {}

    user_text: str = payload.get("user_text", "").strip()
    # slack_thread_id 优先取 PCB 字段，fallback 到 payload
    if not slack_thread_id:
        slack_thread_id = payload.get("slack_thread_id", "")

    if not user_text:
        return "FAILED", {}, "user_text is empty in payload"

    LOGGER.info(
        "Executing action %s type=devos_chat retry=%d slack_thread=%s notepad=%s",
        action_id, retry_count, slack_thread_id, "present" if notepad else "none",
    )

    # --- L1 Thinking Loop: 构建提示词 ---
    if retry_count > 0 and notepad:
        # Context Restore：将上次执行快照注入提示，打破幻觉循环
        LOGGER.info("Context Restore: injecting notepad into prompt for retry %d", retry_count)

    # --- CPU 执行：调用 LLM ---
    try:
        llm_response = call_llm(user_text, notepad if retry_count > 0 else None)
    except Exception as exc:
        LOGGER.error("LLM call failed for action %s: %s", action_id, exc)
        return "FAILED", {}, str(exc)

    # --- 总线写传播：回写 Slack ---
    if slack_thread_id:
        post_to_slack(slack_thread_id, llm_response)

    # --- 构建 result JSON（含 notepad 快照供下次 Context Restore）---
    result = {
        "response": llm_response,
        "notepad": f"[action:{action_id}] {user_text[:200]} → {llm_response[:500]}",
    }

    LOGGER.info("Action %s SUCCEEDED", action_id)
    return "SUCCEEDED", result, None


# ─────────────────────────────────────────────────────────────
# Main Worker Loop（纯拉取模型 / Pure Pull Model）
# ─────────────────────────────────────────────────────────────

def main() -> None:
    LOGGER.info("Starting Slack Dev OS devos_chat worker (id=%s)", WORKER_ID)
    register_worker()

    next_heartbeat_at = 0.0
    while True:
        now = time.monotonic()
        if now >= next_heartbeat_at:
            heartbeat()
            next_heartbeat_at = now + HEARTBEAT_INTERVAL_S

        assignment = poll_action()
        if assignment is None:
            time.sleep(POLL_INTERVAL_S)
            continue

        action_id = assignment.get("actionId")
        LOGGER.info("Claimed action %s", action_id)

        status, result, error_message = execute(assignment)

        try:
            submit_result(action_id, status, result, error_message)
            LOGGER.info("Submitted result for action %s status=%s", action_id, status)
        except Exception as exc:
            LOGGER.error("Failed to submit result for action %s: %s", action_id, exc)


if __name__ == "__main__":
    main()
