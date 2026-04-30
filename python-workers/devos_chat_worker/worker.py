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
from dataclasses import dataclass, field
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


# ─────────────────────────────────────────────────────────────
# B-005 Page Fault — 安全文件读取（Repo as Disk）
# ─────────────────────────────────────────────────────────────

def safe_read_repo_file(repo_path: str, file_path: str, max_bytes: int = 32768) -> dict:
    """
    安全读取 repo_path 下 file_path 指向的文件内容。

    安全不变量：
      - file_path 必须是相对路径（不能以 '/' 开头）
      - file_path 不能含 '..' 路径穿越
      - resolved path 必须仍在 realpath(repo_path) 内
      - 只读取普通文件（非目录、非符号链接目标目录）
      - 最多读取 max_bytes 字节

    返回: {ok: bool, content: str, error: str}
    """
    if not repo_path or not file_path:
        return {"ok": False, "content": "", "error": "repo_path or file_path is empty"}

    # 安全检查 1：file_path 不得是绝对路径
    if os.path.isabs(file_path):
        return {"ok": False, "content": "", "error": "file_path must be relative, not absolute"}

    # 安全检查 2：file_path 不得含 '..'
    norm = os.path.normpath(file_path)
    if ".." in norm.split(os.sep):
        return {"ok": False, "content": "", "error": "file_path must not contain '..'"}

    # 安全检查 3：resolved path 必须在 repo_path 内（防符号链接逃逸）
    real_repo = os.path.realpath(repo_path)
    candidate = os.path.realpath(os.path.join(repo_path, file_path))
    if not candidate.startswith(real_repo + os.sep) and candidate != real_repo:
        return {"ok": False, "content": "", "error": "resolved path escapes repo boundary"}

    # 安全检查 4：只读取普通文件
    if not os.path.isfile(candidate):
        return {"ok": False, "content": "", "error": f"not a regular file: {file_path}"}

    try:
        with open(candidate, "r", encoding="utf-8", errors="replace") as fh:
            content = fh.read(max_bytes)
        LOGGER.info("[page-in] read %d bytes from %s", len(content), file_path)
        return {"ok": True, "content": content, "error": ""}
    except OSError as exc:
        return {"ok": False, "content": "", "error": str(exc)}


# ─────────────────────────────────────────────────────────────
# B-008 Tool Manager — 最小工具协议 (Minimal Tool Protocol)
# ─────────────────────────────────────────────────────────────

@dataclass
class ToolCall:
    """结构化工具调用请求。"""
    name: str
    args: dict = field(default_factory=dict)


@dataclass
class ToolResponse:
    """结构化工具调用响应。"""
    name: str
    ok: bool
    content: str = ""
    error: str = ""
    metadata: dict = field(default_factory=dict)


class ToolManager:
    """
    最小工具注册表 / Minimal Tool Registry。

    安全不变量：
      - 只允许 WHITELIST 内的工具名注册和执行；
      - 未知工具返回 ok=False 的 ToolResponse，不抛异常；
      - 禁止任意 shell command、网络工具、动态插件加载。
    当前白名单: repo.read_file
    """
    WHITELIST: frozenset = frozenset({"repo.read_file"})

    def __init__(self) -> None:
        self._handlers: dict = {}

    def register(self, name: str, handler) -> None:
        """注册工具处理器（仅白名单内工具可注册）。"""
        if name not in self.WHITELIST:
            raise ValueError(
                f"Tool '{name}' is not in the whitelist: {sorted(self.WHITELIST)}"
            )
        self._handlers[name] = handler
        LOGGER.info("[tool-manager] registered tool: %s", name)

    def execute(self, tool_call: ToolCall) -> ToolResponse:
        """执行工具调用；未知工具返回 ok=False（不抛异常）。"""
        if tool_call.name not in self._handlers:
            LOGGER.warning(
                "[tool-manager] unknown tool requested: '%s'", tool_call.name
            )
            return ToolResponse(
                name=tool_call.name,
                ok=False,
                error=(
                    f"Unknown tool: '{tool_call.name}'."
                    f" Available: {sorted(self._handlers)}"
                ),
            )
        try:
            return self._handlers[tool_call.name](tool_call.args)
        except Exception as exc:  # pragma: no cover
            LOGGER.warning("[tool-manager] tool '%s' raised: %s", tool_call.name, exc)
            return ToolResponse(name=tool_call.name, ok=False, error=str(exc))


def _repo_read_file_handler(args: dict) -> ToolResponse:
    """repo.read_file 工具处理器 — safe_read_repo_file() 的协议包装。"""
    repo_path = args.get("repo_path", "")
    file_path_arg = args.get("file_path", "")
    max_bytes = int(args.get("max_bytes", 32768))
    result = safe_read_repo_file(repo_path, file_path_arg, max_bytes)
    return ToolResponse(
        name="repo.read_file",
        ok=result["ok"],
        content=result.get("content", ""),
        error=result.get("error", ""),
        metadata={
            "repo_path": repo_path,
            "file_path": file_path_arg,
            "max_bytes": max_bytes,
        },
    )


# 全局 ToolManager 单例（模块加载时注册白名单工具）
TOOL_MANAGER = ToolManager()
TOOL_MANAGER.register("repo.read_file", _repo_read_file_handler)


def call_llm(user_text: str, notepad: Optional[str], payload: Optional[dict] = None) -> str:
    """
    LLM 调度：DEMO_MODE > GLM > OpenAI
    对应 OS 中"CPU 执行指令"环节。

    DEMO_MODE 优先：若显式设置 DEMO_MODE=true，无论是否有 LLM key，
    均使用 stub 响应（保证 CI/E2E 完全可控）。

    payload（可选）：用于 B-005 Page Fault — 从 repo_path/file_path 读取文件内容。
    """
    if DEMO_MODE:
        LOGGER.warning("DEMO_MODE: returning stub response")
        retry_note = f"\n[Notepad context was present]" if notepad else ""
        # B-005 Page Fault: if repo_path + file_path present in payload, do page-in
        repo_path = payload.get("repo_path", "").strip() if isinstance(payload, dict) else ""
        file_path = payload.get("file_path", "").strip() if isinstance(payload, dict) else ""
        if repo_path and file_path:
            # B-008: route through ToolManager instead of calling safe_read_repo_file directly
            page_in_resp = TOOL_MANAGER.execute(
                ToolCall(
                    name="repo.read_file",
                    args={"repo_path": repo_path, "file_path": file_path},
                )
            )
            if page_in_resp.ok:
                excerpt = page_in_resp.content[:300]
                return (
                    f"[DEMO] I received your request: \"{user_text[:100]}\"\n"
                    f"[PAGE_IN] Loaded file: {file_path}\n"
                    f"{excerpt}\n"
                    f"This is a demo stub response from Slack Dev OS worker.{retry_note}"
                )
            else:
                return (
                    f"[DEMO] I received your request: \"{user_text[:100]}\"\n"
                    f"[PAGE_IN: FILE NOT FOUND] {file_path}: {page_in_resp.error}\n"
                    f"This is a demo stub response from Slack Dev OS worker.{retry_note}"
                )
        return (
            f"[DEMO] I received your request: \"{user_text[:100]}\"\n"
            f"This is a demo stub response from Slack Dev OS worker.{retry_note}"
        )
    if os.environ.get("GLM_API_KEY"):
        return _call_glm(user_text, notepad)
    if os.environ.get("OPENAI_API_KEY"):
        return _call_openai(user_text, notepad)
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
    if notepad:
        # Context Restore：notepad 来自 prevActionId 继承或 retry 快照，均注入提示
        LOGGER.info("Context Restore: injecting notepad into prompt (retry=%d)", retry_count)

    # --- CPU 执行：调用 LLM（notepad 若存在则始终注入，支持顺序周期恢复）---
    # B-005: pass payload so DEMO_MODE can include page-in content in response
    try:
        llm_response = call_llm(user_text, notepad, payload=payload)
    except Exception as exc:
        LOGGER.error("LLM call failed for action %s: %s", action_id, exc)
        return "FAILED", {}, str(exc)

    # --- 总线写传播：回写 Slack ---
    if slack_thread_id:
        post_to_slack(slack_thread_id, llm_response)

    # --- 构建 result JSON（含 notepad 快照供下次 Context Restore）---
    # B-005 Page Fault: if page-in was performed, record in notepad
    repo_path = payload.get("repo_path", "").strip()
    file_path_val = payload.get("file_path", "").strip()
    if repo_path and file_path_val:
        page_in_note = f"[page-in:{file_path_val}] loaded from {repo_path}"
        notepad_snapshot = f"{page_in_note}\n[action:{action_id}] {user_text[:200]} → {llm_response[:300]}"
    else:
        notepad_snapshot = f"[action:{action_id}] {user_text[:200]} → {llm_response[:500]}"

    result = {
        "response": llm_response,
        "notepad": notepad_snapshot,
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
