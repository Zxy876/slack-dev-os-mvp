"""
openhands_slack_mapper.py  —  OpenHands REST API → Slack 指令映射层
==================================================================

把 OpenHands :3000 上的全量 API 以可组合函数形式封装，供 slack_bridge 调用。
每个函数返回人类可读的 Slack 格式字符串（Markdown）。

覆盖范围（对应 OpenHands app_server 路由）：
  - 对话管理        (app_conversation_router)
  - 事件/历史       (event_router)
  - 文件浏览/下载   (app_conversation_router /{conv_id}/file)
  - Skills          (app_conversation_router /{conv_id}/skills, user/skills_router)
  - Git 集成        (git_router)
  - 沙盒管理        (sandbox_router)
  - Settings / LLM  (settings_router, config_router)
  - Secrets 管理    (secrets_router)
  - 状态探针        (status_router)
  - 用户信息        (user_router)
  - Pending 消息    (pending_message_router)

超集能力（Slack Only）：
  - 多 agent 协作路由（见 multi_agent_router.py）
  - 跨 thread 广播
  - Agent 注册/发现
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any, Optional

import requests

LOGGER = logging.getLogger(__name__)

# ─── 环境变量 ────────────────────────────────────────────────
def _openhands_url() -> str:
    return os.environ.get("OPENHANDS_URL", "").rstrip("/")


def _session_key() -> Optional[str]:
    return os.environ.get("OPENHANDS_SESSION_API_KEY")


def _headers() -> dict:
    h: dict = {"Content-Type": "application/json"}
    key = _session_key()
    if key:
        h["X-Session-API-Key"] = key
    return h


def _get(path: str, params: Optional[dict] = None, timeout: int = 15) -> dict:
    """GET {OPENHANDS_URL}{path}，返回解析后的 JSON（dict 或 list 包装成 dict）。"""
    base = _openhands_url()
    if not base:
        raise RuntimeError("OPENHANDS_URL not configured")
    url = f"{base}{path}"
    resp = requests.get(url, headers=_headers(), params=params, timeout=timeout)
    resp.raise_for_status()
    data = resp.json()
    return data if isinstance(data, dict) else {"items": data}


def _post(path: str, body: dict, timeout: int = 15) -> dict:
    base = _openhands_url()
    if not base:
        raise RuntimeError("OPENHANDS_URL not configured")
    url = f"{base}{path}"
    resp = requests.post(url, headers=_headers(), json=body, timeout=timeout)
    resp.raise_for_status()
    if resp.status_code == 204:
        return {}
    data = resp.json()
    return data if isinstance(data, dict) else {"items": data}


def _delete(path: str, timeout: int = 15) -> dict:
    base = _openhands_url()
    if not base:
        raise RuntimeError("OPENHANDS_URL not configured")
    url = f"{base}{path}"
    resp = requests.delete(url, headers=_headers(), timeout=timeout)
    resp.raise_for_status()
    return {}


def _patch(path: str, body: dict, timeout: int = 15) -> dict:
    base = _openhands_url()
    if not base:
        raise RuntimeError("OPENHANDS_URL not configured")
    url = f"{base}{path}"
    resp = requests.patch(url, headers=_headers(), json=body, timeout=timeout)
    resp.raise_for_status()
    data = resp.json()
    return data if isinstance(data, dict) else {"items": data}


# ─────────────────────────────────────────────────────────────
# 1. 对话管理
# ─────────────────────────────────────────────────────────────

def slack_list_conversations(limit: int = 10) -> str:
    """列出最近对话。"""
    data = _get("/api/v1/app-conversations", params={"pageSize": limit})
    items = data.get("appConversations") or data.get("items") or []
    if not items:
        return "_（无对话记录）_"
    lines = ["*最近对话列表*"]
    for c in items[:limit]:
        cid = c.get("conversationId") or c.get("id", "?")
        title = c.get("title") or c.get("name") or "（无标题）"
        status = c.get("status") or c.get("sandboxStatus") or ""
        lines.append(f"• `{cid[:8]}` {title}  [{status}]")
    return "\n".join(lines)


def slack_search_conversations(query: str, limit: int = 10) -> str:
    """搜索对话。"""
    data = _get(
        "/api/v1/app-conversations/search",
        params={"q": query, "pageSize": limit},
    )
    items = data.get("appConversations") or data.get("items") or []
    if not items:
        return f"_未找到包含「{query}」的对话_"
    lines = [f"*搜索「{query}」的结果*"]
    for c in items[:limit]:
        cid = c.get("conversationId") or c.get("id", "?")
        title = c.get("title") or "（无标题）"
        lines.append(f"• `{cid[:8]}` {title}")
    return "\n".join(lines)


def slack_delete_conversation(conv_id: str) -> str:
    """删除指定对话及其沙盒（不可逆）。"""
    _delete(f"/api/v1/app-conversations/{conv_id}")
    return f"✅ 对话 `{conv_id[:8]}` 已删除。"


def slack_rename_conversation(conv_id: str, new_title: str) -> str:
    """重命名对话。"""
    _patch(f"/api/v1/app-conversations/{conv_id}", {"title": new_title})
    return f"✅ 对话 `{conv_id[:8]}` 已重命名为「{new_title}」。"


# ─────────────────────────────────────────────────────────────
# 2. 事件 / 对话历史
# ─────────────────────────────────────────────────────────────

def slack_conversation_history(conv_id: str, limit: int = 10) -> str:
    """获取对话事件历史（最近 N 条）。"""
    data = _get(
        f"/api/v1/conversations/{conv_id}/events",
        params={"limit": limit, "order": "desc"},
    )
    events = data.get("events") or data.get("items") or []
    if not events:
        return f"_对话 `{conv_id[:8]}` 暂无事件记录_"
    lines = [f"*对话 `{conv_id[:8]}` 最近 {len(events)} 条事件*"]
    for ev in reversed(events[:limit]):
        source = ev.get("source") or ev.get("role") or "?"
        etype = ev.get("action") or ev.get("observation") or ev.get("type") or "event"
        msg = (ev.get("message") or ev.get("content") or "")[:120]
        lines.append(f"• [{source}] *{etype}*: {msg}")
    return "\n".join(lines)


def slack_search_events(conv_id: str, query: str, limit: int = 10) -> str:
    """在对话事件中搜索关键词。"""
    data = _get(
        f"/api/v1/conversations/{conv_id}/events/search",
        params={"q": query, "limit": limit},
    )
    events = data.get("events") or data.get("items") or []
    if not events:
        return f"_在对话 `{conv_id[:8]}` 中未找到「{query}」_"
    lines = [f"*对话 `{conv_id[:8]}` 搜索「{query}」结果*"]
    for ev in events[:limit]:
        source = ev.get("source") or "?"
        msg = (ev.get("message") or ev.get("content") or "")[:120]
        lines.append(f"• [{source}] {msg}")
    return "\n".join(lines)


# ─────────────────────────────────────────────────────────────
# 3. 文件浏览 / 下载
# ─────────────────────────────────────────────────────────────

def slack_list_files(conv_id: str, path: str = "/") -> str:
    """列出对话沙盒中的文件。"""
    data = _get(
        f"/api/v1/app-conversations/{conv_id}/file",
        params={"path": path},
    )
    # 返回格式可能是 {"children": [...]} 或 {"content": "..."}
    children = data.get("children") or data.get("files") or []
    if children:
        lines = [f"*沙盒文件 `{path}`*"]
        for f in children[:30]:
            name = f.get("name") or f.get("path") or str(f)
            ftype = "📁" if f.get("isDirectory") or f.get("type") == "directory" else "📄"
            lines.append(f"  {ftype} {name}")
        return "\n".join(lines)
    content = data.get("content") or data.get("text") or ""
    if content:
        # 文件内容，截断后返回
        preview = content[:2000]
        return f"*文件 `{path}`*\n```\n{preview}\n```"
    return f"_路径 `{path}` 无内容或不存在_"


def slack_read_file(conv_id: str, path: str) -> str:
    """读取沙盒文件内容。"""
    data = _get(
        f"/api/v1/app-conversations/{conv_id}/file",
        params={"path": path},
    )
    content = data.get("content") or data.get("text") or ""
    if not content:
        return f"_文件 `{path}` 为空或不存在_"
    ext = path.rsplit(".", 1)[-1] if "." in path else ""
    lang = {
        "py": "python", "js": "javascript", "ts": "typescript",
        "java": "java", "go": "go", "sh": "bash", "yaml": "yaml",
        "yml": "yaml", "json": "json", "md": "markdown",
    }.get(ext, "")
    preview = content[:3000]
    return f"*`{path}`*\n```{lang}\n{preview}\n```"


# ─────────────────────────────────────────────────────────────
# 4. Skills
# ─────────────────────────────────────────────────────────────

def slack_list_skills(conv_id: str) -> str:
    """列出对话可用的 Skills。"""
    data = _get(f"/api/v1/app-conversations/{conv_id}/skills")
    skills = data.get("skills") or data.get("items") or []
    if not skills:
        return "_（该对话无 Skills）_"
    lines = ["*可用 Skills*"]
    for s in skills:
        name = s.get("name") or "?"
        desc = (s.get("description") or "")[:80]
        lines.append(f"• *{name}*: {desc}")
    return "\n".join(lines)


def slack_list_user_skills() -> str:
    """列出用户全局 Skills。"""
    data = _get("/api/v1/skills")
    skills = data.get("skills") or data.get("items") or []
    if not skills:
        return "_（无用户 Skills）_"
    lines = ["*用户 Skills*"]
    for s in skills:
        name = s.get("name") or "?"
        desc = (s.get("description") or "")[:80]
        lines.append(f"• *{name}*: {desc}")
    return "\n".join(lines)


# ─────────────────────────────────────────────────────────────
# 5. Git 集成
# ─────────────────────────────────────────────────────────────

def slack_list_repos(query: str = "", limit: int = 15) -> str:
    """列出可访问的 Git 仓库。"""
    params: dict = {"pageSize": limit}
    if query:
        params["q"] = query
    data = _get("/api/v1/git/repositories/search", params=params)
    repos = data.get("repositories") or data.get("items") or []
    if not repos:
        return "_（无可访问仓库）_"
    lines = ["*可访问仓库*"]
    for r in repos[:limit]:
        full = r.get("fullName") or r.get("name") or "?"
        private = "🔒" if r.get("isPrivate") else "🌐"
        lines.append(f"  {private} `{full}`")
    return "\n".join(lines)


def slack_list_branches(repo: str, limit: int = 20) -> str:
    """列出仓库分支。"""
    data = _get("/api/v1/git/branches/search", params={"repo": repo, "pageSize": limit})
    branches = data.get("branches") or data.get("items") or []
    if not branches:
        return f"_仓库 `{repo}` 无分支或无权访问_"
    lines = [f"*`{repo}` 分支列表*"]
    for b in branches[:limit]:
        name = b.get("name") or str(b)
        default = " ← default" if b.get("isDefault") else ""
        lines.append(f"  • `{name}`{default}")
    return "\n".join(lines)


def slack_suggested_tasks(repo: str, branch: str = "") -> str:
    """获取仓库 AI 建议任务。"""
    params: dict = {"repo": repo}
    if branch:
        params["branch"] = branch
    data = _get("/api/v1/git/suggested-tasks/search", params=params)
    tasks = data.get("tasks") or data.get("items") or []
    if not tasks:
        return f"_仓库 `{repo}` 暂无建议任务_"
    lines = [f"*`{repo}` 建议任务*"]
    for t in tasks[:10]:
        title = t.get("title") or t.get("task") or str(t)[:80]
        lines.append(f"  • {title}")
    return "\n".join(lines)


# ─────────────────────────────────────────────────────────────
# 6. 沙盒管理
# ─────────────────────────────────────────────────────────────

def slack_list_sandboxes(limit: int = 10) -> str:
    """列出沙盒实例。"""
    data = _get("/api/v1/sandboxes", params={"pageSize": limit})
    sandboxes = data.get("sandboxes") or data.get("items") or []
    if not sandboxes:
        return "_（无活跃沙盒）_"
    lines = ["*沙盒列表*"]
    for s in sandboxes[:limit]:
        sid = s.get("sandboxId") or s.get("id") or "?"
        status = s.get("status") or "?"
        conv = s.get("conversationId") or ""
        lines.append(f"  • `{sid[:12]}` [{status}] conv:{conv[:8] if conv else '-'}")
    return "\n".join(lines)


def slack_pause_sandbox(sandbox_id: str) -> str:
    _post(f"/api/v1/sandboxes/{sandbox_id}/pause", {})
    return f"⏸ 沙盒 `{sandbox_id[:12]}` 已暂停。"


def slack_resume_sandbox(sandbox_id: str) -> str:
    _post(f"/api/v1/sandboxes/{sandbox_id}/resume", {})
    return f"▶️ 沙盒 `{sandbox_id[:12]}` 已恢复。"


def slack_delete_sandbox(sandbox_id: str) -> str:
    _delete(f"/api/v1/sandboxes/{sandbox_id}")
    return f"🗑 沙盒 `{sandbox_id[:12]}` 已删除。"


# ─────────────────────────────────────────────────────────────
# 7. Settings / LLM 配置
# ─────────────────────────────────────────────────────────────

def slack_get_settings() -> str:
    """查看当前 LLM 配置。"""
    data = _get("/api/v1/settings")
    llm = data.get("llm") or {}
    model = llm.get("model") or data.get("llmModel") or "?"
    provider = llm.get("provider") or data.get("llmProvider") or "?"
    agent = data.get("agent") or data.get("defaultAgent") or "?"
    return (
        f"*当前 OpenHands 配置*\n"
        f"  • LLM 模型: `{model}`\n"
        f"  • 提供商: `{provider}`\n"
        f"  • Agent: `{agent}`"
    )


def slack_list_models(query: str = "") -> str:
    """列出可用 LLM 模型。"""
    params: dict = {}
    if query:
        params["q"] = query
    data = _get("/api/v1/config/models/search", params=params)
    models = data.get("models") or data.get("items") or []
    if not models:
        return "_（无可用模型）_"
    lines = ["*可用 LLM 模型*"]
    for m in models[:25]:
        name = m if isinstance(m, str) else (m.get("model") or m.get("name") or str(m))
        lines.append(f"  • `{name}`")
    return "\n".join(lines)


def slack_list_profiles() -> str:
    """列出 LLM 配置 Profiles。"""
    data = _get("/api/v1/settings/profiles")
    profiles = data.get("profiles") or data.get("items") or []
    if not profiles:
        return "_（无 LLM Profile）_"
    lines = ["*LLM Profiles*"]
    for p in profiles:
        name = p.get("name") or "?"
        active = " ← active" if p.get("isActive") or p.get("active") else ""
        model = p.get("llm", {}).get("model") or p.get("model") or ""
        lines.append(f"  • `{name}`{active}  {model}")
    return "\n".join(lines)


def slack_activate_profile(profile_name: str) -> str:
    """激活指定 LLM Profile。"""
    _post(f"/api/v1/settings/profiles/{profile_name}/activate", {})
    return f"✅ LLM Profile `{profile_name}` 已激活。"


# ─────────────────────────────────────────────────────────────
# 8. Secrets 管理
# ─────────────────────────────────────────────────────────────

def slack_list_secrets() -> str:
    """列出 Secrets 名称（不含值）。"""
    data = _get("/api/v1/secrets/search")
    secrets = data.get("secrets") or data.get("items") or []
    if not secrets:
        return "_（无 Secrets）_"
    lines = ["*Secrets 列表（仅名称）*"]
    for s in secrets:
        name = s.get("name") or s.get("id") or "?"
        lines.append(f"  • `{name}`")
    return "\n".join(lines)


def slack_add_secret(name: str, value: str) -> str:
    """添加 Secret（注意：仅在私信中使用，避免明文泄漏）。"""
    _post("/api/v1/secrets", {"name": name, "value": value})
    return f"✅ Secret `{name}` 已添加。"


def slack_delete_secret(secret_id: str) -> str:
    """删除 Secret。"""
    _delete(f"/api/v1/secrets/{secret_id}")
    return f"✅ Secret `{secret_id}` 已删除。"


# ─────────────────────────────────────────────────────────────
# 9. 状态探针
# ─────────────────────────────────────────────────────────────

def slack_openhands_status() -> str:
    """查询 OpenHands 健康状态。"""
    base = _openhands_url()
    if not base:
        return "_[integration_status: disconnected] OPENHANDS_URL 未配置_"
    try:
        r = requests.get(f"{base}/api/v1/status/health", timeout=5)
        r.raise_for_status()
        data = r.json()
        version = data.get("version") or "?"
        return (
            f"✅ *OpenHands 健康*\n"
            f"  • 地址: `{base}`\n"
            f"  • 版本: `{version}`\n"
            f"  • _[integration_status: connected]_"
        )
    except Exception as exc:
        return (
            f"⚠️ *OpenHands 不可达*: {exc}\n"
            f"  • _[integration_status: degraded]_"
        )


def slack_server_info() -> str:
    """获取 OpenHands 服务器信息。"""
    data = _get("/api/v1/status/server_info")
    version = data.get("version") or "?"
    runtime = data.get("runtimeType") or "?"
    return (
        f"*OpenHands 服务信息*\n"
        f"  • 版本: `{version}`\n"
        f"  • Runtime: `{runtime}`"
    )


# ─────────────────────────────────────────────────────────────
# 10. 用户信息
# ─────────────────────────────────────────────────────────────

def slack_whoami() -> str:
    """获取当前用户信息。"""
    data = _get("/api/v1/users/me")
    name = data.get("name") or data.get("username") or "?"
    email = data.get("email") or ""
    return f"*当前用户*: `{name}`{f'  ({email})' if email else ''}"


def slack_git_info() -> str:
    """获取用户 Git 信息。"""
    data = _get("/api/v1/users/git-info")
    name = data.get("gitName") or data.get("name") or "?"
    email = data.get("gitEmail") or data.get("email") or "?"
    return f"*Git 身份*: `{name}` <{email}>"


# ─────────────────────────────────────────────────────────────
# 11. Pending Messages（向运行中对话发消息）
# ─────────────────────────────────────────────────────────────

def slack_send_message_to_conversation(conv_id: str, message: str) -> str:
    """向运行中的对话发送用户消息（中断 agent 并输入）。"""
    _post(
        "/api/v1/pending-messages",
        {"conversationId": conv_id, "content": message},
    )
    return f"✅ 消息已发送到对话 `{conv_id[:8]}`。"


# ─────────────────────────────────────────────────────────────
# 12. Hooks（对话级 Webhook）
# ─────────────────────────────────────────────────────────────

def slack_list_hooks(conv_id: str) -> str:
    """列出对话的 Webhook hooks。"""
    data = _get(f"/api/v1/app-conversations/{conv_id}/hooks")
    hooks = data.get("hooks") or data.get("items") or []
    if not hooks:
        return f"_对话 `{conv_id[:8]}` 无 hooks_"
    lines = [f"*对话 `{conv_id[:8]}` Hooks*"]
    for h in hooks:
        url = h.get("url") or "?"
        event = h.get("event") or "?"
        lines.append(f"  • [{event}] {url}")
    return "\n".join(lines)


# ─────────────────────────────────────────────────────────────
# 帮助文本（供 slack_bridge 的 devos: help 使用）
# ─────────────────────────────────────────────────────────────

HELP_TEXT = """*DevOS Slack 指令手册*  (OpenHands 完整映射 + 多 agent 超集)

*📋 对话管理*
```
devos: list                          → 列出最近对话
devos: search <query>                → 搜索对话
devos: rename <conv_id> <title>      → 重命名对话
devos: delete <conv_id> confirm      → 删除对话（不可逆）
```

*💬 任务执行*
```
devos: ask <instruction>             → 启动新 OpenHands 对话
devos: preview <file> replace "X" with "Y"  → 预览代码变更
devos: apply <actionId> confirm      → 应用补丁
devos: fix <issue>                   → 提出修复方案
devos: test <command>                → 运行测试
devos: commit "<message>" confirm    → 提交代码
```

*📨 对话消息*
```
devos: send <conv_id> <message>      → 向运行中对话发消息
devos: history <conv_id>             → 查看对话历史
devos: search-events <conv_id> <q>   → 搜索对话事件
```

*📁 文件系统*
```
devos: files <conv_id> [path]        → 浏览沙盒文件
devos: read <conv_id> <path>         → 读取文件内容
```

*🔧 Skills*
```
devos: skills <conv_id>              → 对话可用 Skills
devos: my-skills                     → 用户全局 Skills
```

*🐙 Git 集成*
```
devos: repos [query]                 → 列出仓库
devos: branches <repo>               → 列出分支
devos: suggest <repo> [branch]       → AI 建议任务
```

*🖥 沙盒管理*
```
devos: sandbox list                  → 列出沙盒
devos: sandbox pause <id>            → 暂停沙盒
devos: sandbox resume <id>           → 恢复沙盒
devos: sandbox delete <id> confirm   → 删除沙盒
```

*⚙️ 配置 & LLM*
```
devos: settings                      → 查看当前配置
devos: models [query]                → 可用 LLM 模型
devos: profiles                      → LLM Profile 列表
devos: profile use <name>            → 切换 LLM Profile
```

*🔑 Secrets*
```
devos: secrets                       → 列出 Secrets（名称）
devos: secret delete <id> confirm    → 删除 Secret
```

*📡 状态*
```
devos: status                        → OpenHands 健康状态
devos: server-info                   → 服务信息
devos: whoami                        → 当前用户
devos: git-info                      → Git 身份
```

*🤖 多 Agent 协作（Slack 超集）*
```
devos: agents                        → 列出活跃 agent
@agent-<name> ask <instruction>      → 路由到指定 agent
devos: broadcast <message>           → 广播到频道
devos: handoff @agent-<name> <ctx>   → 工作交接给另一个 agent
devos: roles                         → 查看 agent 角色分配
```
"""


def slack_help() -> str:
    return HELP_TEXT
