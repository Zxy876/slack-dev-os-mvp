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

import difflib
import hashlib
import json
import logging
import os
import shutil
import tempfile
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Optional

import requests

# B-xxx Capability Adapter — OpenHands integration (optional, graceful fallback)
try:
    from devos_chat_worker.openhands_adapter import AdapterResult, get_openhands_adapter, reset_adapter  # noqa: F401
    _OPENHANDS_ADAPTER_AVAILABLE = True
except ImportError:  # pragma: no cover
    _OPENHANDS_ADAPTER_AVAILABLE = False
    get_openhands_adapter = lambda: None  # type: ignore[assignment]

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

# ── Agent 身份（多 agent 协作层）──────────────────────────────
AGENT_NAME: str = os.environ.get("AGENT_NAME", "agent-alpha")
AGENT_ROLE: str = os.environ.get("AGENT_ROLE", "general")
AGENT_CHANNEL: str = os.environ.get("SLACK_CHANNEL_ID", "")
AGENT_MODE: str = os.environ.get("AGENT_MODE", "coordinator" if AGENT_NAME == "agent-alpha" else "executor")


# ─────────────────────────────────────────────────────────────
# B-010 — Production Mode Config Helpers
# ─────────────────────────────────────────────────────────────

def redact_secret(value: str) -> str:
    """遮掩 secret 值：保留前 4 字符，其余替换为 ***。"""
    if not value:
        return "(not set)"
    if len(value) <= 4:
        return "***"
    return value[:4] + "***"


def is_demo_mode() -> bool:
    """返回 True 当且仅当环境变量 DEMO_MODE 设为 true/1/yes。"""
    return os.environ.get("DEMO_MODE", "").lower() in ("1", "true", "yes")


def select_llm_backend() -> str:
    """
    确定当前应使用的 LLM 后端。

    优先级（从高到低）：
      1. DEMO_MODE=true              → "demo"  （始终优先，无需真实 key，保证 CI 可控）
      2. OPENHANDS_URL 已配置        → "openhands"  （Capability Adapter，真实 agent 能力）
      3. GLM_API_KEY 存在            → "glm"
      4. OPENAI_API_KEY 存在         → "openai"
      5. 无任何配置                  → RuntimeError（fail fast）

    返回: "demo" | "openhands" | "glm" | "openai"
    抛出: RuntimeError — DEMO_MODE=false 且无任何 LLM/OpenHands 配置
    """
    if is_demo_mode():
        return "demo"
    if os.environ.get("OPENHANDS_URL", "").strip():
        return "openhands"
    if os.environ.get("GLM_API_KEY"):
        return "glm"
    if os.environ.get("OPENAI_API_KEY"):
        return "openai"
    raise RuntimeError(
        "No LLM backend available: DEMO_MODE is not set and none of "
        "OPENHANDS_URL, GLM_API_KEY, OPENAI_API_KEY is configured. "
        "Set DEMO_MODE=true for local/CI testing, or provide a real LLM API key."
    )


def validate_runtime_config() -> dict:
    """
    验证并汇总当前运行时配置（不抛异常，调用方检查 llm_ok）。

    返回 config dict，字段：
      asyncaiflow_url, worker_id, demo_mode, llm_backend, llm_ok,
      llm_error, slack_token_redacted, slack_webhook_redacted,
      require_slack_post, slack_ok
    """
    try:
        backend = select_llm_backend()
        llm_ok = True
        llm_error = None
    except RuntimeError as exc:
        backend = "missing"
        llm_ok = False
        llm_error = str(exc)

    slack_token = os.environ.get("SLACK_BOT_TOKEN", "")
    slack_webhook = os.environ.get("SLACK_WEBHOOK_URL", "")
    require_slack = os.environ.get("REQUIRE_SLACK_POST", "false").lower() in ("1", "true", "yes")
    slack_ok = bool(slack_token or slack_webhook) or not require_slack

    return {
        "asyncaiflow_url": ASYNCAIFLOW_URL,
        "worker_id": WORKER_ID,
        "demo_mode": is_demo_mode(),
        "llm_backend": backend,
        "llm_ok": llm_ok,
        "llm_error": llm_error,
        "slack_token_redacted": redact_secret(slack_token),
        "slack_webhook_redacted": redact_secret(slack_webhook),
        "require_slack_post": require_slack,
        "slack_ok": slack_ok,
    }


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
    # ── 多 agent 注册到共享注册表 ──────────────────────────────
    try:
        try:
            from devos_chat_worker.multi_agent_router import register_self
        except ImportError:
            from multi_agent_router import register_self  # type: ignore
        register_self(channel_id=AGENT_CHANNEL)
        LOGGER.info("[multi-agent] agent=%s role=%s registered", AGENT_NAME, AGENT_ROLE)
    except Exception as exc:
        LOGGER.warning("[multi-agent] register_self failed (non-fatal): %s", exc)


def heartbeat() -> None:
    try:
        _aiflow_post("/worker/heartbeat", {"workerId": WORKER_ID})
    except Exception as exc:
        LOGGER.warning("Heartbeat failed: %s", exc)
    # ── 同步更新 multi-agent 心跳 ──────────────────────────────
    try:
        try:
            from devos_chat_worker.multi_agent_router import heartbeat_self
        except ImportError:
            from multi_agent_router import heartbeat_self  # type: ignore
        heartbeat_self()
    except Exception:
        pass


def poll_action() -> Optional[dict]:
    """从 AsyncAIFlow 拉取一个 devos_chat action（PCB 认领）。"""
    try:
        resp = _aiflow_session.get(
            f"{ASYNCAIFLOW_URL}/action/poll",
            params={"workerId": WORKER_ID},
            timeout=10,
        )
    except Exception as exc:
        LOGGER.warning("poll_action network error (will retry): %s", exc)
        return None
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
    当前白名单: repo.read_file, repo.create_workspace_copy,
               repo.replace_in_file_preview, repo.diff_workspace
    """
    WHITELIST: frozenset = frozenset({
        "repo.read_file",
        "repo.create_workspace_copy",
        "repo.replace_in_file_preview",
        "repo.diff_workspace",
    })

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


# ─────────────────────────────────────────────────────────────
# B-017 Patch Preview Tools
# ─────────────────────────────────────────────────────────────

# 安全常量
_PATCH_WORKSPACE_ROOT = os.environ.get("DEVOS_WORKSPACE_ROOT", "/tmp/devos-workspaces")
_MAX_WORKSPACE_FILE_BYTES = 256 * 1024  # 256 KB max file to copy/patch


def _safe_workspace_path(action_id: int) -> str:
    """返回隔离 workspace 路径（不创建目录）。"""
    return os.path.join(_PATCH_WORKSPACE_ROOT, str(action_id))


def _validate_patch_paths(repo_path: str, file_path: str) -> Optional[str]:
    """
    验证 repo_path + file_path 安全性（与 safe_read_repo_file 同等标准）。
    返回 error string 或 None（通过）。
    """
    if not repo_path or not file_path:
        return "repo_path and file_path must not be empty"
    if os.path.isabs(file_path):
        return "file_path must be relative, not absolute"
    norm = os.path.normpath(file_path)
    if ".." in norm.split(os.sep):
        return "file_path must not contain '..'"
    real_repo = os.path.realpath(repo_path)
    candidate = os.path.realpath(os.path.join(repo_path, file_path))
    if not candidate.startswith(real_repo + os.sep) and candidate != real_repo:
        return "resolved path escapes repo boundary"
    return None


def _repo_create_workspace_copy_handler(args: dict) -> ToolResponse:
    """
    repo.create_workspace_copy — 将 repo_path/file_path 复制到隔离 workspace。

    安全：只复制单文件；workspace 在 /tmp/devos-workspaces/<action_id>/；
    不修改原 repo。
    """
    repo_path = args.get("repo_path", "")
    file_path = args.get("file_path", "")
    action_id = args.get("action_id", "unknown")

    err = _validate_patch_paths(repo_path, file_path)
    if err:
        return ToolResponse(name="repo.create_workspace_copy", ok=False, error=err)

    src = os.path.realpath(os.path.join(repo_path, file_path))
    if not os.path.isfile(src):
        return ToolResponse(
            name="repo.create_workspace_copy", ok=False,
            error=f"source file not found: {file_path}",
        )

    file_size = os.path.getsize(src)
    if file_size > _MAX_WORKSPACE_FILE_BYTES:
        return ToolResponse(
            name="repo.create_workspace_copy", ok=False,
            error=f"file too large for patch preview: {file_size} bytes (max {_MAX_WORKSPACE_FILE_BYTES})",
        )

    ws_dir = _safe_workspace_path(action_id)
    ws_file = os.path.join(ws_dir, os.path.normpath(file_path))
    os.makedirs(os.path.dirname(ws_file), exist_ok=True)

    shutil.copy2(src, ws_file)
    LOGGER.info("[workspace-copy] copied %s → %s", src, ws_file)

    return ToolResponse(
        name="repo.create_workspace_copy",
        ok=True,
        content=ws_file,
        metadata={"workspace_dir": ws_dir, "workspace_file": ws_file, "source_file": src},
    )


def _repo_replace_in_file_preview_handler(args: dict) -> ToolResponse:
    """
    repo.replace_in_file_preview — 在 workspace 副本中执行文本替换（不修改原 repo）。

    安全：只操作 workspace_file 路径（必须在 _PATCH_WORKSPACE_ROOT 内）；
    不执行任意代码；不写回 repo_path。
    """
    workspace_file = args.get("workspace_file", "")
    replace_from = args.get("replace_from", "")
    replace_to = args.get("replace_to", "")

    if not workspace_file or not replace_from:
        return ToolResponse(
            name="repo.replace_in_file_preview", ok=False,
            error="workspace_file and replace_from must not be empty",
        )

    # 安全：workspace_file 必须在 _PATCH_WORKSPACE_ROOT 内
    real_ws = os.path.realpath(workspace_file)
    real_root = os.path.realpath(_PATCH_WORKSPACE_ROOT)
    if not real_ws.startswith(real_root + os.sep):
        return ToolResponse(
            name="repo.replace_in_file_preview", ok=False,
            error="workspace_file is outside the designated workspace root",
        )

    if not os.path.isfile(real_ws):
        return ToolResponse(
            name="repo.replace_in_file_preview", ok=False,
            error=f"workspace_file not found: {workspace_file}",
        )

    try:
        with open(real_ws, "r", encoding="utf-8", errors="replace") as fh:
            original = fh.read()
    except OSError as exc:
        return ToolResponse(name="repo.replace_in_file_preview", ok=False, error=str(exc))

    if replace_from not in original:
        return ToolResponse(
            name="repo.replace_in_file_preview", ok=False,
            error=f"replace_from string not found in file",
            metadata={"replace_from_len": len(replace_from)},
        )

    modified = original.replace(replace_from, replace_to, 1)

    try:
        with open(real_ws, "w", encoding="utf-8") as fh:
            fh.write(modified)
    except OSError as exc:
        return ToolResponse(name="repo.replace_in_file_preview", ok=False, error=str(exc))

    LOGGER.info("[patch-preview] replaced %d chars → %d chars in %s",
                len(replace_from), len(replace_to), workspace_file)

    return ToolResponse(
        name="repo.replace_in_file_preview",
        ok=True,
        content=modified[:500],
        metadata={
            "workspace_file": workspace_file,
            "replace_from_len": len(replace_from),
            "replace_to_len": len(replace_to),
        },
    )


def _repo_diff_workspace_handler(args: dict) -> ToolResponse:
    """
    repo.diff_workspace — 生成 workspace 副本 vs 原始文件的 unified diff。

    安全：只读操作；workspace_file 必须在 _PATCH_WORKSPACE_ROOT 内；
    original_file 必须是 repo_path 内的真实文件路径。
    """
    workspace_file = args.get("workspace_file", "")
    original_file = args.get("original_file", "")
    file_label = args.get("file_label", os.path.basename(workspace_file))

    if not workspace_file or not original_file:
        return ToolResponse(
            name="repo.diff_workspace", ok=False,
            error="workspace_file and original_file must not be empty",
        )

    real_ws = os.path.realpath(workspace_file)
    real_orig = os.path.realpath(original_file)
    real_root = os.path.realpath(_PATCH_WORKSPACE_ROOT)

    if not real_ws.startswith(real_root + os.sep):
        return ToolResponse(
            name="repo.diff_workspace", ok=False,
            error="workspace_file is outside the designated workspace root",
        )

    for path, label in [(real_ws, "workspace_file"), (real_orig, "original_file")]:
        if not os.path.isfile(path):
            return ToolResponse(
                name="repo.diff_workspace", ok=False,
                error=f"{label} not found: {path}",
            )

    try:
        with open(real_orig, "r", encoding="utf-8", errors="replace") as fh:
            orig_lines = fh.readlines()
        with open(real_ws, "r", encoding="utf-8", errors="replace") as fh:
            new_lines = fh.readlines()
    except OSError as exc:
        return ToolResponse(name="repo.diff_workspace", ok=False, error=str(exc))

    diff_lines = list(difflib.unified_diff(
        orig_lines, new_lines,
        fromfile=f"a/{file_label}",
        tofile=f"b/{file_label}",
        lineterm="",
    ))

    diff_text = "\n".join(diff_lines)
    LOGGER.info("[diff-workspace] generated %d diff lines for %s", len(diff_lines), file_label)

    return ToolResponse(
        name="repo.diff_workspace",
        ok=True,
        content=diff_text,
        metadata={
            "diff_lines": len(diff_lines),
            "file_label": file_label,
            "changed": len(diff_lines) > 0,
        },
    )


TOOL_MANAGER.register("repo.create_workspace_copy", _repo_create_workspace_copy_handler)
TOOL_MANAGER.register("repo.replace_in_file_preview", _repo_replace_in_file_preview_handler)
TOOL_MANAGER.register("repo.diff_workspace", _repo_diff_workspace_handler)


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
        mode = payload.get("mode", "").strip().lower() if isinstance(payload, dict) else ""
        if mode == "patch_preview":
            # In DEMO_MODE patch_preview, return a stub patch plan
            return (
                f"[DEMO PATCH_PLAN_ONLY]\n"
                f"1. Modify {file_path or 'target file'} as requested\n"
                f"2. Replace matching text with updated content\n"
                f"3. Run tests to verify change\n"
                f"(Demo stub — provide replace_from/replace_to for real diff)"
            )
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
    # ── B-xxx Capability Adapter: OpenHands (优先级高于 native LLM) ──
    openhands_url = os.environ.get("OPENHANDS_URL", "").strip()
    if openhands_url:
        adapter = get_openhands_adapter()
        if adapter:
            if adapter.is_available():
                result = adapter.run_task(user_text)
                if result.ok and result.response:
                    LOGGER.info(
                        "[worker] using openhands.core, conv=%s",
                        (result.conversation_id or "")[:8],
                    )
                    return result.response + result.format_footer()
                LOGGER.warning(
                    "[worker] openhands.core returned error: %s — falling back to native LLM",
                    result.error,
                )
            else:
                LOGGER.warning(
                    "[worker] OPENHANDS_URL=%s is configured but not reachable "
                    "— falling back to native LLM",
                    openhands_url,
                )
    if os.environ.get("GLM_API_KEY"):
        return _call_glm(user_text, notepad)
    if os.environ.get("OPENAI_API_KEY"):
        return _call_openai(user_text, notepad)
    raise RuntimeError(
        "No LLM API key configured. Set OPENAI_API_KEY, GLM_API_KEY, or DEMO_MODE=true."
    )


def resolve_integration_status(llm_response: str) -> str:
    """根据回复与环境推导对外可见的集成状态。"""
    if "capability_source: openhands.core" in llm_response:
        return "connected"
    if os.environ.get("OPENHANDS_URL", "").strip():
        return "degraded"
    return "disconnected"


def append_integration_footer(text: str, integration_status: str) -> str:
    """确保回复末尾包含 integration_status，避免重复附加。"""
    if "integration_status:" in text:
        return text
    return text + f"\n\n_[integration_status: {integration_status}]_"


def coordinator_classify_and_route(user_text: str, payload: Optional[dict] = None) -> tuple[Optional[str], Optional[str]]:
    """
    Coordinator 任务分类与自动分配。
    
    根据用户需求文本，使用 LLM 分类为具体的 agent 角色，并自动生成 handoff 上下文。
    
    返回: (target_role, context) 或 (None, None) 如果不需要分配给其他 agent
    
    支持的角色: backend, frontend, test, review, devops
    """
    if not user_text.strip():
        return None, None
    
    # 构建分类 prompt
    classification_prompt = (
        f"""你是任务分类与路由系统。分析用户需求，决定应该分配给哪个 agent 角色执行。

用户需求: {user_text}

可选的 agent 角色及职责:
- backend: 后端代码实现、API 设计、数据库迁移
- frontend: 前端组件、UI 交互、样式实现
- test: 编写测试、运行测试套件、分析失败用例
- review: 代码审查、安全扫描、提出改进建议
- devops: CI/CD 配置、Docker、基础设施即代码

你必须用以下 JSON 格式回复，不包含其他文本:
{{
  "should_handoff": true/false,
  "target_role": "backend|frontend|test|review|devops|null",
  "reason": "简短的分类理由",
  "context": "传递给目标 agent 的上下文摘要"
}}

如果用户需求不属于任何特定角色，或者 coordinator 自己可以处理，应该设置 "should_handoff": false。
"""
    )
    
    try:
        # 使用 DEMO_MODE=false 来调用分类 LLM（不用 stub）
        llm_response = call_llm(classification_prompt, "", payload or {})
        
        # 尝试解析 JSON
        import re
        json_match = re.search(r'\{[^{}]*"should_handoff"[^{}]*\}', llm_response, re.DOTALL)
        if not json_match:
            LOGGER.warning("Coordinator classification LLM response not in JSON format: %s", llm_response[:100])
            return None, None
        
        classification_json = json.loads(json_match.group())
        
        if not classification_json.get("should_handoff"):
            LOGGER.info("[coordinator] classification: should not handoff, self-handle")
            return None, None
        
        target_role = classification_json.get("target_role", "").strip()
        context = classification_json.get("context", "").strip()
        reason = classification_json.get("reason", "")
        
        if not target_role or target_role == "null":
            return None, None
        
        LOGGER.info(
            "[coordinator] classified task for handoff: role=%s reason=%s",
            target_role, reason
        )
        
        return target_role, context
        
    except (json.JSONDecodeError, ValueError, KeyError) as exc:
        LOGGER.warning("Coordinator classification failed: %s", exc)
        return None, None
    except Exception as exc:
        LOGGER.error("Unexpected error in coordinator_classify_and_route: %s", exc)
        return None, None


def collect_real_artifacts(payload: Optional[dict], result: Optional[dict] = None) -> dict:
    """
    收集真实的执行产物：Playwright 截图、测试报告、构建产物 URL、页面预览。
    
    支持的产物来源:
    1. Playwright 截图文件路径: payload.get("screenshot_path")
    2. 构建产物目录: payload.get("build_dir")
    3. 测试报告文件: payload.get("test_report_file")
    4. 预览 URL: payload.get("preview_url")
    5. 执行日志: payload.get("log_file") 或 result.get("notepad")
    
    返回收集结果的字典，用于后续上传/展示。
    """
    if not isinstance(payload, dict) and not isinstance(result, dict):
        return {}
    
    payload = payload or {}
    result = result or {}
    artifacts: dict = {}
    
    # 1. Playwright 截图
    screenshot_path = payload.get("screenshot_path", "")
    if screenshot_path and isinstance(screenshot_path, str):
        screenshot_path = screenshot_path.strip()
        if os.path.isfile(screenshot_path):
            try:
                # 在真实部署中，应该上传到 Slack Files API，这里返回本地路径
                artifacts["screenshots"] = [f"file://{os.path.abspath(screenshot_path)}"]
                LOGGER.info("[artifacts] collected screenshot: %s", screenshot_path)
            except Exception as exc:
                LOGGER.warning("[artifacts] failed to process screenshot: %s", exc)
    
    # 2. 构建产物
    build_dir = payload.get("build_dir", "")
    if build_dir and isinstance(build_dir, str):
        build_dir = build_dir.strip()
        if os.path.isdir(build_dir):
            try:
                build_files = []
                for root, dirs, files in os.walk(build_dir):
                    for fname in files[:5]:  # 最多 5 个文件
                        fpath = os.path.join(root, fname)
                        build_files.append({
                            "name": fname,
                            "url": f"file://{os.path.abspath(fpath)}"
                        })
                if build_files:
                    artifacts["buildArtifacts"] = build_files
                    LOGGER.info("[artifacts] collected %d build artifacts from %s", len(build_files), build_dir)
            except Exception as exc:
                LOGGER.warning("[artifacts] failed to collect build artifacts: %s", exc)
    
    # 3. 测试报告
    test_report_file = payload.get("test_report_file", "")
    if test_report_file and isinstance(test_report_file, str):
        test_report_file = test_report_file.strip()
        if os.path.isfile(test_report_file):
            try:
                artifacts["testReport"] = f"file://{os.path.abspath(test_report_file)}"
                LOGGER.info("[artifacts] collected test report: %s", test_report_file)
            except Exception as exc:
                LOGGER.warning("[artifacts] failed to collect test report: %s", exc)
    
    # 4. 预览 URL
    preview_url = payload.get("preview_url", "") or payload.get("previewUrl", "")
    if preview_url and isinstance(preview_url, str):
        preview_url = preview_url.strip()
        if preview_url.startswith(("http://", "https://")):
            artifacts["previewUrl"] = preview_url
            LOGGER.info("[artifacts] collected preview URL: %s", preview_url[:50])
    
    # 5. 日志摘要
    log_file = payload.get("log_file", "")
    if log_file and isinstance(log_file, str):
        log_file = log_file.strip()
        if os.path.isfile(log_file):
            try:
                with open(log_file, "r", encoding="utf-8", errors="ignore") as f:
                    # 读取最后 500 字符作为摘要
                    content = f.read()
                    summary = content[-500:] if len(content) > 500 else content
                    artifacts["logSummary"] = summary
                    LOGGER.info("[artifacts] collected log summary from %s", log_file)
            except Exception as exc:
                LOGGER.warning("[artifacts] failed to collect logs: %s", exc)
    elif "notepad" in result and isinstance(result.get("notepad"), str):
        # fallback: 使用 result notepad 的最后部分作为日志摘要
        notepad = result.get("notepad", "")
        artifacts["logSummary"] = notepad[-500:] if len(notepad) > 500 else notepad
    
    return artifacts


def build_artifact_summary(payload: Optional[dict]) -> tuple[str, dict]:
    """把测试报告、构建产物、截图、预览链接、日志摘要统一整理为 Slack 可读块。
    
    首先尝试收集真实产物（通过 collect_real_artifacts），然后才使用 payload 中提供的产物字段。
    """
    if not isinstance(payload, dict):
        return "", {}

    # 先收集真实产物
    real_artifacts = collect_real_artifacts(payload)
    
    # 然后从 payload 或真实收集结果中提取产物
    artifact_payload = payload.get("artifacts") if isinstance(payload.get("artifacts"), dict) else {}
    preview_url = (
        artifact_payload.get("previewUrl") 
        or artifact_payload.get("preview_url") 
        or payload.get("previewUrl") 
        or payload.get("preview_url")
        or real_artifacts.get("previewUrl")
    )
    screenshots = (
        artifact_payload.get("screenshots") 
        or payload.get("screenshots") 
        or real_artifacts.get("screenshots")
        or []
    )
    build_artifacts = (
        artifact_payload.get("buildArtifacts") 
        or artifact_payload.get("build_artifacts") 
        or payload.get("buildArtifacts") 
        or payload.get("build_artifacts")
        or real_artifacts.get("buildArtifacts")
        or []
    )
    test_report = (
        artifact_payload.get("testReport") 
        or artifact_payload.get("test_report") 
        or payload.get("testReport") 
        or payload.get("test_report")
        or real_artifacts.get("testReport")
    )
    log_summary = (
        artifact_payload.get("logSummary") 
        or artifact_payload.get("log_summary") 
        or payload.get("logSummary") 
        or payload.get("log_summary")
        or real_artifacts.get("logSummary")
    )

    normalized: dict = {}
    lines: list[str] = []

    if preview_url:
        normalized["previewUrl"] = str(preview_url)
        lines.append(f"• Preview: {preview_url}")

    if screenshots:
        normalized["screenshots"] = [str(item) for item in screenshots[:3]]
        for screenshot in normalized["screenshots"]:
            lines.append(f"• Screenshot: {screenshot}")

    if build_artifacts:
        normalized_items: list[str] = []
        for item in build_artifacts[:5]:
            if isinstance(item, dict):
                label = item.get("name") or item.get("label") or item.get("url") or "artifact"
                url = item.get("url")
                normalized_items.append(f"{label}: {url}" if url else str(label))
            else:
                normalized_items.append(str(item))
        normalized["buildArtifacts"] = normalized_items
        for item in normalized_items:
            lines.append(f"• Build: {item}")

    if test_report:
        normalized["testReport"] = str(test_report)
        lines.append(f"• Test report: {test_report}")

    if log_summary:
        summary = str(log_summary).strip()
        normalized["logSummary"] = summary[:500]
        lines.append(f"• Logs: {normalized['logSummary'][:200]}")

    if not lines:
        return "", {}

    return "*Artifacts*\n" + "\n".join(lines), normalized


def append_artifact_summary(text: str, payload: Optional[dict]) -> tuple[str, dict]:
    block, artifacts = build_artifact_summary(payload)
    if not block:
        return text, {}
    if "*Artifacts*" in text:
        return text, artifacts
    return f"{text}\n\n{block}", artifacts


class WorkspaceLockTimeout(RuntimeError):
    pass


def _workspace_lock_root() -> str:
    return os.environ.get("DEVOS_WORKSPACE_LOCK_ROOT", "/tmp/devos-workspace-locks")


def _workspace_lock_timeout_s() -> float:
    return float(os.environ.get("DEVOS_WORKSPACE_LOCK_TIMEOUT_S", "15"))


def _workspace_lock_stale_s() -> float:
    return float(os.environ.get("DEVOS_WORKSPACE_LOCK_STALE_S", "1800"))


def _workspace_key_from_payload(payload: dict) -> str:
    return (
        payload.get("workspaceKey")
        or payload.get("repoPath")
        or payload.get("repo_path")
        or ""
    )


def _workspace_lock_dir(workspace_key: str) -> str:
    digest = hashlib.sha1(workspace_key.encode("utf-8")).hexdigest()
    return os.path.join(_workspace_lock_root(), digest)


def acquire_workspace_lock(workspace_key: str, owner: str, timeout_s: Optional[float] = None) -> dict:
    if not workspace_key:
        return {}

    timeout = _workspace_lock_timeout_s() if timeout_s is None else timeout_s
    stale_s = _workspace_lock_stale_s()
    lock_dir = _workspace_lock_dir(workspace_key)
    metadata_path = os.path.join(lock_dir, "owner.json")
    os.makedirs(_workspace_lock_root(), exist_ok=True)
    deadline = time.monotonic() + timeout

    while True:
        try:
            os.mkdir(lock_dir)
            metadata = {
                "workspaceKey": workspace_key,
                "owner": owner,
                "agent": AGENT_NAME,
                "mode": AGENT_MODE,
                "pid": os.getpid(),
                "acquiredAt": time.time(),
            }
            with open(metadata_path, "w", encoding="utf-8") as fh:
                json.dump(metadata, fh)
            return {"lockDir": lock_dir, "metadata": metadata}
        except FileExistsError:
            try:
                with open(metadata_path, "r", encoding="utf-8") as fh:
                    active = json.load(fh)
            except Exception:
                active = {}

            acquired_at = float(active.get("acquiredAt", 0) or 0)
            if acquired_at and (time.time() - acquired_at) > stale_s:
                LOGGER.warning("Removing stale workspace lock: %s", lock_dir)
                shutil.rmtree(lock_dir, ignore_errors=True)
                continue

            if time.monotonic() >= deadline:
                active_owner = active.get("owner", "unknown")
                raise WorkspaceLockTimeout(
                    f"workspace busy: {workspace_key} (held by {active_owner})"
                )
            time.sleep(0.1)


def release_workspace_lock(lock_info: dict) -> None:
    lock_dir = lock_info.get("lockDir") if isinstance(lock_info, dict) else None
    if lock_dir:
        shutil.rmtree(lock_dir, ignore_errors=True)


@contextmanager
def workspace_lock(payload: dict, action_id: int):
    workspace_key = _workspace_key_from_payload(payload)
    if not workspace_key:
        yield {}, workspace_key
        return

    owner = f"action:{action_id}"
    lock_info = acquire_workspace_lock(workspace_key, owner)
    try:
        yield lock_info, workspace_key
    finally:
        release_workspace_lock(lock_info)


def poll_and_notify_handoffs() -> list[dict]:
    """执行者拾取交接包，并在 Slack 中发送回执，验证协调者→执行者协议。"""
    try:
        try:
            from devos_chat_worker.multi_agent_router import (
                poll_handoffs,
                set_agent_status,
                slack_format_handoff_notification,
            )
        except ImportError:
            from multi_agent_router import poll_handoffs, set_agent_status, slack_format_handoff_notification  # type: ignore
    except Exception:
        return []

    packages = poll_handoffs()
    notifications: list[dict] = []
    for pkg in packages:
        task_summary = pkg.context[:80]
        set_agent_status("busy", task=f"handoff:{task_summary}")
        text = slack_format_handoff_notification(pkg)
        target = AGENT_CHANNEL or os.environ.get("SLACK_CHANNEL_ID", "")
        if target:
            post_to_slack(target, text)
        notifications.append({"from": pkg.from_agent, "context": pkg.context, "text": text})
    return notifications


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
    require_slack = os.environ.get("REQUIRE_SLACK_POST", "false").lower() in ("1", "true", "yes")
    if not token:
        if require_slack:
            raise RuntimeError(
                "REQUIRE_SLACK_POST=true but SLACK_BOT_TOKEN is not set. "
                "Provide a valid Slack Bot Token or set REQUIRE_SLACK_POST=false."
            )
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
# B-017 Patch Preview Executor
# ─────────────────────────────────────────────────────────────

def execute_patch_preview(action_id: int, payload: dict, slack_thread_id: Optional[str]) -> tuple[str, dict, Optional[str]]:
    """
    B-017 dry-run coding path:
      1. Read target file from repo
      2. Create isolated workspace copy
      3. If replace_from/replace_to provided → apply replacement + generate diff → [PATCH_PREVIEW]
      4. Else → ask LLM for patch plan → [PATCH_PLAN_ONLY]
      5. Post result to Slack; never modify original repo

    Returns: (status, result_dict, error_message)
    """
    repo_path = payload.get("repo_path", "").strip()
    file_path = payload.get("file_path", "").strip()
    user_text = payload.get("user_text", "").strip()
    replace_from = payload.get("replace_from", "").strip()
    replace_to = payload.get("replace_to", "").strip()
    notepad = payload.get("notepadRef")  # may be absent

    if not repo_path or not file_path:
        return "FAILED", {}, "patch_preview requires both repo_path and file_path"

    # ── Step 1: read original file ──────────────────────────
    read_resp = TOOL_MANAGER.execute(ToolCall(
        name="repo.read_file",
        args={"repo_path": repo_path, "file_path": file_path},
    ))
    if not read_resp.ok:
        return "FAILED", {}, f"repo.read_file failed: {read_resp.error}"

    original_content = read_resp.content

    # Compute SHA-256 of raw file bytes (for B-018 stale-patch guard)
    try:
        _src = os.path.realpath(os.path.join(repo_path, file_path))
        with open(_src, "rb") as _fh:
            _raw = _fh.read(_MAX_WORKSPACE_FILE_BYTES)
        original_sha256 = hashlib.sha256(_raw).hexdigest()
    except OSError:
        original_sha256 = ""

    # ── Step 2: create workspace copy ──────────────────────
    copy_resp = TOOL_MANAGER.execute(ToolCall(
        name="repo.create_workspace_copy",
        args={"repo_path": repo_path, "file_path": file_path, "action_id": action_id},
    ))
    if not copy_resp.ok:
        return "FAILED", {}, f"repo.create_workspace_copy failed: {copy_resp.error}"

    workspace_file = copy_resp.metadata["workspace_file"]
    original_file = copy_resp.metadata["source_file"]
    workspace_dir = copy_resp.metadata["workspace_dir"]

    # ── Step 3a: deterministic patch (replace_from provided) ─
    if replace_from:
        replace_resp = TOOL_MANAGER.execute(ToolCall(
            name="repo.replace_in_file_preview",
            args={
                "workspace_file": workspace_file,
                "replace_from": replace_from,
                "replace_to": replace_to,
            },
        ))
        if not replace_resp.ok:
            shutil.rmtree(workspace_dir, ignore_errors=True)
            return "FAILED", {}, f"repo.replace_in_file_preview failed: {replace_resp.error}"

        diff_resp = TOOL_MANAGER.execute(ToolCall(
            name="repo.diff_workspace",
            args={
                "workspace_file": workspace_file,
                "original_file": original_file,
                "file_label": file_path,
            },
        ))

        # Clean up workspace (patch already captured in diff)
        shutil.rmtree(workspace_dir, ignore_errors=True)

        diff_text = diff_resp.content if diff_resp.ok else "(diff generation failed)"
        diff_excerpt = diff_text[:1500] if diff_text else "(no changes)"
        changed_files = [file_path] if diff_resp.metadata.get("changed") else []

        response_text = (
            f"[PATCH_PREVIEW]\n"
            f"File: {file_path}\n"
            f"Changed files: {', '.join(changed_files) or 'none'}\n\n"
            f"```diff\n{diff_excerpt}\n```\n\n"
            f"Original repo NOT modified. Review diff above before applying."
        )
        response_text, artifacts = append_artifact_summary(response_text, payload)

        notepad_snapshot = (
            f"[patch-preview:{action_id}]\n"
            f"workspace: {workspace_dir} (cleaned)\n"
            f"changed: {', '.join(changed_files) or 'none'}\n"
            f"test_status: skipped\n"
            f"diff_lines: {diff_resp.metadata.get('diff_lines', 0)}"
        )

        if slack_thread_id:
            post_to_slack(slack_thread_id, response_text)

        return "SUCCEEDED", {
            "response": response_text,
            "notepad": notepad_snapshot,
            "artifacts": artifacts,
            # B-018: structured patch metadata for human-confirm apply
            "patchPreview": {
                "mode": "replace",
                "repoPath": repo_path,
                "filePath": file_path,
                "replaceFrom": replace_from,
                "replaceTo": replace_to,
                "originalSha256": original_sha256,
                "diff": diff_text,
            },
        }, None

    # ── Step 3b: LLM patch plan (no replace_from) ─────────
    shutil.rmtree(workspace_dir, ignore_errors=True)  # no actual patch, discard copy

    # Build LLM prompt with file content
    file_excerpt = original_content[:2000]
    llm_prompt = (
        f"You are a coding assistant performing a dry-run patch preview.\n\n"
        f"User request: {user_text}\n\n"
        f"Target file: {file_path}\n"
        f"Current content (excerpt):\n```\n{file_excerpt}\n```\n\n"
        f"Provide a structured patch plan:\n"
        f"1. What changes are needed\n"
        f"2. Which lines/sections to modify\n"
        f"3. Show a unified diff if possible\n\n"
        f"IMPORTANT: This is a dry-run preview only. Do not auto-apply changes."
    )

    try:
        llm_response = call_llm(llm_prompt, notepad, payload=payload)
    except Exception as exc:
        return "FAILED", {}, f"LLM call failed: {exc}"

    response_text = f"[PATCH_PLAN_ONLY]\n{llm_response}"
    response_text, artifacts = append_artifact_summary(response_text, payload)
    notepad_snapshot = (
        f"[patch-plan:{action_id}]\n"
        f"file: {file_path}\n"
        f"request: {user_text[:200]}\n"
        f"plan: {llm_response[:300]}"
    )

    if slack_thread_id:
        post_to_slack(slack_thread_id, response_text)

    return "SUCCEEDED", {"response": response_text, "notepad": notepad_snapshot, "artifacts": artifacts}, None


# ─────────────────────────────────────────────────────────────
# B-020 Fix Preview Executor
# ─────────────────────────────────────────────────────────────

# Maximum chars read from a file for fix-preview context
_FIX_PREVIEW_MAX_FILE_BYTES = 8_000
# Maximum chars of failure context to embed in LLM prompt
_FIX_PREVIEW_MAX_FAILURE_CHARS = 1_000


def execute_fix_preview(
    action_id: int,
    payload: dict,
    slack_thread_id: Optional[str],
) -> tuple[str, dict, Optional[str]]:
    """
    B-020 fix preview path:
      1. Validate repoPath + filePath (via safe_read_repo_file)
      2. Read target file content (read-only — no workspace copy needed)
      3. Extract failure context from payload.failure_context
      4. DEMO_MODE → return deterministic [FIX_PLAN_ONLY] stub
         Real LLM  → build fix-oriented prompt, return [FIX_PLAN_ONLY]
                     (or [FIX_PATCH_PREVIEW] if LLM provides structured diff)
      5. Submit result with fixPreview metadata (hasPatch=False always on first pass)

    Safety invariants:
      - NO filesystem mutation at any point
      - NO auto-apply / NO commit / NO push
      - NO shell execution
      - file content truncated at _FIX_PREVIEW_MAX_FILE_BYTES chars
      - failure context fields truncated at _FIX_PREVIEW_MAX_FAILURE_CHARS chars
      - path safety enforced by safe_read_repo_file (same as page fault / patch preview)

    Returns: (status, result_dict, error_message)
    """
    repo_path = payload.get("repo_path", "").strip()
    file_path = payload.get("file_path", "").strip()
    failure_context = payload.get("failure_context") or {}
    notepad = payload.get("notepadRef")

    if not repo_path or not file_path:
        return "FAILED", {}, "fix_preview requires both repo_path and file_path"

    # ── Step 1: read file (read-only, path-safe) ──────────────
    read_resp = TOOL_MANAGER.execute(ToolCall(
        name="repo.read_file",
        args={"repo_path": repo_path, "file_path": file_path,
              "max_bytes": _FIX_PREVIEW_MAX_FILE_BYTES},
    ))
    if not read_resp.ok:
        return "FAILED", {}, f"repo.read_file failed: {read_resp.error}"

    file_content = read_resp.content

    # ── Step 2: extract & sanitize failure context ────────────
    fc = failure_context if isinstance(failure_context, dict) else {}
    test_status  = str(fc.get("test_status", "FAILED"))[:64]
    exit_code    = int(fc.get("exit_code", -1)) if isinstance(fc.get("exit_code"), (int, float, str)) else -1
    stdout_raw   = str(fc.get("stdout_excerpt", ""))
    stderr_raw   = str(fc.get("stderr_excerpt", ""))
    hint_raw     = str(fc.get("hint", ""))
    stdout_snip  = stdout_raw[:_FIX_PREVIEW_MAX_FAILURE_CHARS]
    stderr_snip  = stderr_raw[:_FIX_PREVIEW_MAX_FAILURE_CHARS]
    hint_snip    = hint_raw[:512]

    LOGGER.info(
        "[fix-preview] action=%s file=%s test_status=%s exitCode=%d",
        action_id, file_path, test_status, exit_code,
    )

    # ── Step 3: build response ────────────────────────────────
    if is_demo_mode():
        response_text = (
            f"[FIX_PLAN_ONLY]\n"
            f"File: {file_path}\n"
            f"Test status: {test_status} (exitCode={exit_code})\n\n"
            f"Suggested fix steps:\n"
            f"1. Review the stderr output for the root cause\n"
            f"2. Inspect {file_path} around the failing assertion or exception\n"
            f"3. Apply the minimal change to fix the failure\n"
            f"4. Re-run the test suite to confirm\n\n"
            f"No filesystem changes were made. Review this plan and apply changes manually.\n"
            f"(Demo stub — provide real LLM keys for code-level suggestions)"
        )
        response_text, artifacts = append_artifact_summary(response_text, payload)
        notepad_snapshot = (
            f"[fix-preview:{action_id}]\n"
            f"file: {file_path}\n"
            f"test_status: {test_status}\n"
            f"exit_code: {exit_code}\n"
            f"no_apply: true\n"
            f"no_commit: true"
        )

        if slack_thread_id:
            post_to_slack(slack_thread_id, response_text)

        return "SUCCEEDED", {
            "response": response_text,
            "notepad": notepad_snapshot,
            "artifacts": artifacts,
            "fixPreview": {
                "repoPath": repo_path,
                "filePath": file_path,
                "testStatus": test_status,
                "exitCode": exit_code,
                "hasPatch": False,
            },
        }, None

    # ── Real LLM path ─────────────────────────────────────────
    file_excerpt = file_content[:2_000]
    hint_clause = f"\nHuman hint: {hint_snip}" if hint_snip.strip() else ""

    llm_prompt = (
        f"You are a software engineering assistant generating a fix suggestion.\n\n"
        f"A test suite failed on file: {file_path}\n"
        f"Exit code: {exit_code}  |  Test status: {test_status}{hint_clause}\n\n"
        f"--- stdout (excerpt) ---\n{stdout_snip}\n\n"
        f"--- stderr (excerpt) ---\n{stderr_snip}\n\n"
        f"--- file content (excerpt) ---\n```\n{file_excerpt}\n```\n\n"
        f"Provide a structured fix plan:\n"
        f"1. Root cause of the failure\n"
        f"2. Which lines / sections to modify\n"
        f"3. Proposed change (unified diff if possible)\n\n"
        f"IMPORTANT: This is a fix-preview ONLY. "
        f"Do NOT claim to have applied changes. "
        f"Do NOT modify any files. "
        f"Await human review before any change is applied."
    )

    try:
        llm_response = call_llm(llm_prompt, notepad, payload=payload)
    except Exception as exc:
        return "FAILED", {}, f"LLM call failed: {exc}"

    response_text = f"[FIX_PLAN_ONLY]\n{llm_response}"
    response_text, artifacts = append_artifact_summary(response_text, payload)
    notepad_snapshot = (
        f"[fix-preview:{action_id}]\n"
        f"file: {file_path}\n"
        f"test_status: {test_status}\n"
        f"exit_code: {exit_code}\n"
        f"plan: {llm_response[:300]}\n"
        f"no_apply: true\n"
        f"no_commit: true"
    )

    if slack_thread_id:
        post_to_slack(slack_thread_id, response_text)

    return "SUCCEEDED", {
        "response": response_text,
        "notepad": notepad_snapshot,
        "artifacts": artifacts,
        "fixPreview": {
            "repoPath": repo_path,
            "filePath": file_path,
            "testStatus": test_status,
            "exitCode": exit_code,
            "hasPatch": False,
        },
    }, None


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
        "Executing action %s type=devos_chat retry=%d slack_thread=%s notepad=%s mode=%s",
        action_id, retry_count, slack_thread_id, "present" if notepad else "none", AGENT_MODE,
    )

    try:
        try:
            from devos_chat_worker.multi_agent_router import set_agent_status
        except ImportError:
            from multi_agent_router import set_agent_status  # type: ignore
        set_agent_status("busy", task=user_text[:120] or payload.get("mode", "action"))
    except Exception:
        pass

    try:
        with workspace_lock(payload, action_id) as (lock_info, workspace_key):
            # ── B-017 patch_preview routing ──────────────────────────
            mode = payload.get("mode", "").strip().lower()
            if mode == "patch_preview":
                LOGGER.info("[patch-preview] routing action %s to patch preview executor", action_id)
                payload["notepadRef"] = notepad
                status, result, err = execute_patch_preview(action_id, payload, slack_thread_id)
            elif mode == "fix_preview":
                LOGGER.info("[fix-preview] routing action %s to fix preview executor", action_id)
                payload["notepadRef"] = notepad
                status, result, err = execute_fix_preview(action_id, payload, slack_thread_id)
            else:
                if notepad:
                    LOGGER.info("Context Restore: injecting notepad into prompt (retry=%d)", retry_count)

                # ── Coordinator 任务分类与自动分配 ────────────────────
                if AGENT_MODE == "coordinator":
                    target_role, handoff_context = coordinator_classify_and_route(user_text, payload)
                    if target_role and handoff_context:
                        try:
                            from devos_chat_worker.multi_agent_router import create_handoff
                        except ImportError:
                            from multi_agent_router import create_handoff  # type: ignore
                        
                        # 生成 handoff 消息
                        handoff_msg = create_handoff(
                            to_agent=f"agent-{target_role}" if not target_role.startswith("agent-") else target_role,
                            context=handoff_context,
                            conv_id=payload.get("conversation_id", ""),
                            notepad=notepad[:500] if notepad else ""
                        )
                        
                        LOGGER.info(
                            "[coordinator] auto-routed to %s: %s",
                            target_role, handoff_context[:100]
                        )
                        
                        # Slack 回复自动生成的 handoff 消息
                        if slack_thread_id:
                            post_to_slack(slack_thread_id, handoff_msg)
                        
                        return "SUCCEEDED", {
                            "response": handoff_msg,
                            "notepad": f"[auto-handoff:{action_id}] delegated to @agent-{target_role}: {handoff_context[:200]}",
                            "integration_status": "connected",
                            "artifacts": {},
                        }, None

                try:
                    llm_response = call_llm(user_text, notepad, payload=payload)
                except Exception as exc:
                    LOGGER.error("LLM call failed for action %s: %s", action_id, exc)
                    return "FAILED", {}, str(exc)

                llm_response, artifacts = append_artifact_summary(llm_response, payload)
                integration_status = resolve_integration_status(llm_response)
                llm_response = append_integration_footer(llm_response, integration_status)

                if slack_thread_id:
                    post_to_slack(slack_thread_id, llm_response)

                repo_path = payload.get("repo_path", "").strip()
                file_path_val = payload.get("file_path", "").strip()
                if repo_path and file_path_val:
                    page_in_note = f"[page-in:{file_path_val}] loaded from {repo_path}"
                    notepad_snapshot = f"{page_in_note}\n[action:{action_id}] {user_text[:200]} → {llm_response[:300]}"
                else:
                    notepad_snapshot = f"[action:{action_id}] {user_text[:200]} → {llm_response[:500]}"

                status = "SUCCEEDED"
                err = None
                result = {
                    "response": llm_response,
                    "notepad": notepad_snapshot,
                    "integration_status": integration_status,
                    "artifacts": artifacts,
                }

            if workspace_key:
                result.setdefault("workspaceLock", {})
                result["workspaceLock"].update({
                    "workspaceKey": workspace_key,
                    "owner": (lock_info.get("metadata") or {}).get("owner", f"action:{action_id}"),
                })

            LOGGER.info("Action %s %s", action_id, status)
            return status, result, err
    except WorkspaceLockTimeout as exc:
        busy_reply = f"⏳ Workspace busy: {exc}. Try again after the active agent finishes."
        if slack_thread_id:
            post_to_slack(slack_thread_id, busy_reply)
        return "FAILED", {
            "response": busy_reply,
            "workspaceLock": {"workspaceKey": _workspace_key_from_payload(payload)},
        }, str(exc)
    finally:
        try:
            try:
                from devos_chat_worker.multi_agent_router import set_agent_status
            except ImportError:
                from multi_agent_router import set_agent_status  # type: ignore
            set_agent_status("idle", task="")
        except Exception:
            pass


# ─────────────────────────────────────────────────────────────
# Main Worker Loop（纯拉取模型 / Pure Pull Model）
# ─────────────────────────────────────────────────────────────

def main() -> None:
    LOGGER.info("Starting Slack Dev OS devos_chat worker (id=%s)", WORKER_ID)

    # B-010: validate and log runtime config on startup; fail fast if LLM backend missing
    config = validate_runtime_config()
    LOGGER.info(
        "[config] demo_mode=%s llm_backend=%s slack_token=%s slack_webhook=%s require_slack=%s",
        config["demo_mode"],
        config["llm_backend"],
        config["slack_token_redacted"],
        config["slack_webhook_redacted"],
        config["require_slack_post"],
    )
    if not config["llm_ok"]:
        LOGGER.error("[config] Fatal: %s", config["llm_error"])
        raise RuntimeError(config["llm_error"])

    register_worker()

    next_heartbeat_at = 0.0
    while True:
        now = time.monotonic()
        if now >= next_heartbeat_at:
            heartbeat()
            next_heartbeat_at = now + HEARTBEAT_INTERVAL_S

        poll_and_notify_handoffs()

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
