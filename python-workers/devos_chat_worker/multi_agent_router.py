"""
multi_agent_router.py  —  Slack 多 Agent 协作路由层
=====================================================

职责：
  1. Agent 身份注册：每个 worker 进程有唯一名称 + 角色
  2. Agent 注册表：用 JSON 文件（共享文件系统）或环境变量维护活跃 agent 列表
  3. @agent-<name> 路由：消息分发给特定 worker
  4. 广播机制：把结果 post 到频道（非 thread）
  5. 工作交接协议：handoff context 传递

环境变量：
  AGENT_NAME        当前 agent 名称（默认 agent-alpha）
  AGENT_ROLE        当前 agent 职责描述（默认 general）
  AGENT_REGISTRY_PATH  共享注册表文件路径（默认 /tmp/devos_agents.json）
  SLACK_BOT_TOKEN   Slack Bot Token（用于广播）
  SLACK_CHANNEL_ID  默认广播频道
"""

from __future__ import annotations

import json
import logging
import os
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Optional

LOGGER = logging.getLogger(__name__)

# ─── 环境变量 ────────────────────────────────────────────────

def _agent_name() -> str:
    return os.environ.get("AGENT_NAME", "agent-alpha")


def _agent_role() -> str:
    return os.environ.get("AGENT_ROLE", "general")


def _agent_mode() -> str:
    """协调者/执行者模式。默认 agent-alpha 为协调者，其余为执行者。"""
    configured = os.environ.get("AGENT_MODE", "").strip().lower()
    if configured in {"coordinator", "executor"}:
        return configured
    return "coordinator" if _agent_name() == "agent-alpha" else "executor"


def _registry_path() -> Path:
    return Path(os.environ.get("AGENT_REGISTRY_PATH", "/tmp/devos_agents.json"))


def _slack_bot_token() -> Optional[str]:
    return os.environ.get("SLACK_BOT_TOKEN")


def _slack_channel() -> Optional[str]:
    return os.environ.get("SLACK_CHANNEL_ID")


def _handoff_path() -> Path:
    return Path(os.environ.get("HANDOFF_DIR", "/tmp/devos_handoffs"))


# ─── Agent 数据模型 ──────────────────────────────────────────

@dataclass
class AgentRecord:
    name: str            # e.g. "agent-alpha"
    role: str            # e.g. "backend", "test", "review"
    pid: int             # 进程 ID
    last_heartbeat: float  # UNIX timestamp
    channel_id: str      # 所在 Slack 频道
    mode: str = "executor"  # coordinator | executor
    status: str = "idle"   # idle | busy
    current_task: str = ""


# ─── 注册表（基于共享 JSON 文件，适合单机多 worker） ─────────

_HEARTBEAT_TTL = 120  # 超过 2 分钟未心跳视为离线


def _load_registry() -> dict[str, AgentRecord]:
    path = _registry_path()
    if not path.exists():
        return {}
    try:
        raw = json.loads(path.read_text())
        now = time.time()
        agents: dict[str, AgentRecord] = {}
        for name, data in raw.items():
            if now - data.get("last_heartbeat", 0) < _HEARTBEAT_TTL:
                agents[name] = AgentRecord(**data)
        return agents
    except Exception as exc:
        LOGGER.warning("Failed to load agent registry: %s", exc)
        return {}


def _save_registry(agents: dict[str, AgentRecord]) -> None:
    path = _registry_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        serialised = {name: asdict(rec) for name, rec in agents.items()}
        path.write_text(json.dumps(serialised, indent=2))
    except Exception as exc:
        LOGGER.warning("Failed to save agent registry: %s", exc)


def register_self(channel_id: str = "") -> AgentRecord:
    """将当前 worker 注册到共享注册表。"""
    name = _agent_name()
    role = _agent_role()
    rec = AgentRecord(
        name=name,
        role=role,
        pid=os.getpid(),
        last_heartbeat=time.time(),
        channel_id=channel_id or _slack_channel() or "",
        mode=_agent_mode(),
        status="idle",
    )
    agents = _load_registry()
    agents[name] = rec
    _save_registry(agents)
    LOGGER.info("[multi-agent] registered self: name=%s role=%s", name, role)
    return rec


def heartbeat_self() -> None:
    """更新当前 worker 的心跳时间（定期调用）。"""
    name = _agent_name()
    agents = _load_registry()
    if name in agents:
        agents[name].last_heartbeat = time.time()
        _save_registry(agents)


def set_agent_status(status: str, task: str = "") -> None:
    """更新当前 agent 的状态（idle / busy）。"""
    name = _agent_name()
    agents = _load_registry()
    if name in agents:
        agents[name].status = status
        agents[name].current_task = task
        _save_registry(agents)


def deregister_self() -> None:
    """下线时从注册表移除自身。"""
    name = _agent_name()
    agents = _load_registry()
    agents.pop(name, None)
    _save_registry(agents)
    LOGGER.info("[multi-agent] deregistered self: name=%s", name)


# ─── 路由解析 ────────────────────────────────────────────────

def parse_agent_mention(text: str) -> tuple[Optional[str], str]:
    """
    解析消息中的 @agent-<name> 前缀。

    返回 (agent_name, remaining_instruction)
    若无 @agent 前缀，返回 (None, text)

    支持格式：
      @agent-alpha ask 帮我写测试
      @agent-beta fix bug in main.py
    """
    import re
    m = re.match(r"^@(agent-[\w\-]+)\s+(.*)", text.strip(), re.DOTALL)
    if m:
        return m.group(1), m.group(2).strip()
    return None, text


def is_for_me(agent_name: Optional[str]) -> bool:
    """判断路由的目标 agent 是否是当前 worker。"""
    if agent_name is None:
        return True
    return agent_name == _agent_name()


def is_coordinator() -> bool:
    return _agent_mode() == "coordinator"


def route_incoming_text(text: str) -> tuple[bool, str, str]:
    """
    决定当前 agent 是否应处理这条 Slack 文本，并在点名时移除 @agent 前缀。

    规则：
      1. 明确 `@agent-x ...` 只投递给目标 agent。
      2. 未点名消息默认只由 coordinator 处理。
      3. executor 忽略未点名消息，避免同频道多 worker 重复消费。
    """
    target_agent, remaining = parse_agent_mention(text)
    if target_agent is not None:
        if is_for_me(target_agent):
            return True, remaining, f"explicit route to @{target_agent}"
        return False, text, f"explicit route to @{target_agent}"

    if is_coordinator():
        return True, text, "coordinator accepted unmentioned command"
    return False, text, "executor ignored unmentioned command"


# ─── Slack 回复工具 ──────────────────────────────────────────

def post_to_slack_channel(
    channel_id: str,
    text: str,
    thread_ts: Optional[str] = None,
) -> bool:
    """
    直接调用 Slack API 回写消息。
    若 thread_ts 为 None，消息发到频道顶层（广播）。
    返回是否成功。
    """
    token = _slack_bot_token()
    if not token:
        LOGGER.warning("[multi-agent] SLACK_BOT_TOKEN not set, cannot post")
        return False
    try:
        import requests as req
        payload: dict = {"channel": channel_id, "text": text}
        if thread_ts:
            payload["thread_ts"] = thread_ts
        resp = req.post(
            "https://slack.com/api/chat.postMessage",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json=payload,
            timeout=10,
        )
        data = resp.json()
        if not data.get("ok"):
            LOGGER.warning("[multi-agent] Slack post failed: %s", data.get("error"))
            return False
        return True
    except Exception as exc:
        LOGGER.warning("[multi-agent] post_to_slack_channel exception: %s", exc)
        return False


def broadcast_to_channel(text: str, channel_id: Optional[str] = None) -> bool:
    """广播消息到频道顶层（不在 thread 内）。"""
    ch = channel_id or _slack_channel()
    if not ch:
        LOGGER.warning("[multi-agent] SLACK_CHANNEL_ID not set, cannot broadcast")
        return False
    agent = _agent_name()
    full_text = f"[{agent}] {text}"
    return post_to_slack_channel(ch, full_text)


# ─── 多 agent 协作 Slack 指令实现 ────────────────────────────

def slack_list_agents() -> str:
    """列出活跃 agent（供 devos: agents 指令使用）。"""
    agents = _load_registry()
    if not agents:
        return "_（无活跃 agent）_\n提示：启动 worker 时设置 `AGENT_NAME=agent-alpha AGENT_ROLE=backend`"
    lines = ["*活跃 Agent 列表*"]
    for name, rec in sorted(agents.items()):
        status_emoji = "🟢" if rec.status == "idle" else "🔴"
        task = f"  → {rec.current_task[:50]}" if rec.current_task else ""
        lines.append(f"  {status_emoji} `@{name}` [{rec.role}/{rec.mode}]{task}")
    return "\n".join(lines)


def slack_broadcast_message(message: str, channel_id: Optional[str] = None) -> str:
    """执行广播（供 devos: broadcast 使用）。"""
    ok = broadcast_to_channel(message, channel_id)
    if ok:
        return f"📢 已广播到频道：{message[:80]}"
    return "⚠️ 广播失败，请检查 `SLACK_BOT_TOKEN` 和 `SLACK_CHANNEL_ID`。"


@dataclass
class HandoffPackage:
    """工作交接数据包。"""
    from_agent: str
    to_agent: str
    context: str           # 交接的上下文/指令
    conv_id: str = ""      # 当前对话 ID（可选）
    notepad: str = ""      # notepad 内容摘要（可选）
    timestamp: float = field(default_factory=time.time)


def create_handoff(to_agent: str, context: str, conv_id: str = "", notepad: str = "") -> str:
    """
    创建工作交接包。
    把当前工作状态写到共享文件，目标 agent 轮询时拾取。
    """
    pkg = HandoffPackage(
        from_agent=_agent_name(),
        to_agent=to_agent,
        context=context,
        conv_id=conv_id,
        notepad=notepad,
    )
    handoff_path = _handoff_path()
    try:
        handoff_path.mkdir(parents=True, exist_ok=True)
        fname = f"{to_agent}_{int(pkg.timestamp)}.json"
        (handoff_path / fname).write_text(json.dumps(asdict(pkg), indent=2))
        LOGGER.info("[multi-agent] handoff created: %s → %s", pkg.from_agent, to_agent)
        return (
            f"✅ 工作交接已发送给 `@{to_agent}`\n"
            f"  • 上下文: {context[:100]}\n"
            f"  • conv_id: {conv_id[:8] if conv_id else '无'}"
        )
    except Exception as exc:
        return f"⚠️ 交接失败: {exc}"


def poll_handoffs() -> list[HandoffPackage]:
    """当前 agent 拾取发给自己的交接包（并删除文件）。"""
    name = _agent_name()
    result: list[HandoffPackage] = []
    handoff_path = _handoff_path()
    if not handoff_path.exists():
        return result
    for fpath in sorted(handoff_path.glob(f"{name}_*.json")):
        try:
            data = json.loads(fpath.read_text())
            result.append(HandoffPackage(**data))
            fpath.unlink()
        except Exception as exc:
            LOGGER.warning("[multi-agent] failed to load handoff %s: %s", fpath, exc)
    return result


def slack_format_handoff_notification(pkg: HandoffPackage) -> str:
    """把交接包格式化成 Slack 消息。"""
    return (
        f"📬 *工作交接*  `@{pkg.from_agent}` → `@{pkg.to_agent}`\n"
        f"  • 上下文: {pkg.context}\n"
        f"  • conv_id: {pkg.conv_id[:8] if pkg.conv_id else '无'}\n"
        f"  • 备忘: {pkg.notepad[:200] if pkg.notepad else '无'}"
    )


# ─── Agent 角色约定 ──────────────────────────────────────────

ROLE_PRESETS = {
    "coordinator": "负责拆解任务、分配执行者、串联结果与最终回帖",
    "backend": "负责后端代码实现、API 设计、数据库迁移",
    "frontend": "负责前端组件、UI 交互、样式实现",
    "test": "负责编写测试、运行测试套件、分析失败用例",
    "review": "负责代码审查、安全扫描、提出改进建议",
    "devops": "负责 CI/CD 配置、Docker、基础设施即代码",
    "general": "通用 agent，处理所有类型任务",
}


def slack_list_roles() -> str:
    """列出 agent 角色定义。"""
    lines = ["*Agent 角色定义*"]
    # 显示活跃 agent 的角色分配
    agents = _load_registry()
    for name, rec in sorted(agents.items()):
        desc = ROLE_PRESETS.get(rec.role, rec.role)
        status_emoji = "🟢" if rec.status == "idle" else "🔴"
        lines.append(f"  {status_emoji} `@{name}` [{rec.role}/{rec.mode}]: {desc}")
    if not agents:
        lines.append("  _（无活跃 agent）_")
    lines.append("\n*角色类型*")
    for role, desc in ROLE_PRESETS.items():
        lines.append(f"  • `{role}`: {desc}")
    return "\n".join(lines)
