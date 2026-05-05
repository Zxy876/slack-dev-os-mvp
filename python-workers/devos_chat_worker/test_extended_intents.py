"""
test_extended_intents.py  —  扩展意图与多 agent 路由测试

覆盖：
  - parse_extended_intent: 所有新意图
  - dispatch_extended_intent: mock 调用 mapper/mar
  - multi_agent_router: 注册/注销/心跳/handoff/广播
  - handle_slack_event: 新意图端到端路径
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

# ─────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────

def _event(text: str, prefix: str = "devos: ") -> dict:
    return {
        "type": "message",
        "text": f"{prefix}{text}",
        "channel": "C001",
        "thread_ts": "111.222",
    }


# ─────────────────────────────────────────────────────────────
# parse_extended_intent
# ─────────────────────────────────────────────────────────────

class TestParseExtendedIntent:
    def setup_method(self):
        try:
            from devos_chat_worker.slack_bridge import parse_extended_intent
        except ImportError:
            from slack_bridge import parse_extended_intent  # type: ignore
        self.parse = parse_extended_intent

    def test_help(self):
        r = self.parse("help")
        assert r is not None and r.kind == "help"

    def test_help_question_mark(self):
        r = self.parse("?")
        assert r is not None and r.kind == "help"

    def test_list(self):
        r = self.parse("list")
        assert r is not None and r.kind == "list_conversations"

    def test_search_conversations(self):
        r = self.parse("search my feature branch")
        assert r is not None and r.kind == "search_conversations"
        assert r.payload["query"] == "my feature branch"

    def test_delete_needs_confirm(self):
        r = self.parse("delete abc123")
        assert r is not None and r.kind == "needs_confirmation"

    def test_delete_confirmed(self):
        r = self.parse("delete abc123 confirm")
        assert r is not None and r.kind == "delete_conversation"
        assert r.payload["conv_id"] == "abc123"
        assert r.dangerous is True

    def test_rename(self):
        r = self.parse("rename abc123 My New Title")
        assert r is not None and r.kind == "rename_conversation"
        assert r.payload["conv_id"] == "abc123"
        assert r.payload["title"] == "My New Title"

    def test_history(self):
        r = self.parse("history conv999")
        assert r is not None and r.kind == "history"
        assert r.payload["conv_id"] == "conv999"

    def test_search_events(self):
        r = self.parse("search-events convABC error message")
        assert r is not None and r.kind == "search_events"
        assert r.payload["conv_id"] == "convABC"
        assert r.payload["query"] == "error message"

    def test_files_root(self):
        r = self.parse("files convXYZ")
        assert r is not None and r.kind == "files"
        assert r.payload["path"] == "/"

    def test_files_with_path(self):
        r = self.parse("files convXYZ /src/main.py")
        assert r is not None and r.kind == "files"
        assert r.payload["path"] == "/src/main.py"

    def test_read_file(self):
        r = self.parse("read convXYZ /src/utils.py")
        assert r is not None and r.kind == "read_file"
        assert r.payload["conv_id"] == "convXYZ"
        assert r.payload["path"] == "/src/utils.py"

    def test_skills(self):
        r = self.parse("skills convABC")
        assert r is not None and r.kind == "skills"

    def test_my_skills(self):
        r = self.parse("my-skills")
        assert r is not None and r.kind == "my_skills"

    def test_repos(self):
        r = self.parse("repos")
        assert r is not None and r.kind == "repos"

    def test_repos_with_query(self):
        r = self.parse("repos openhands")
        assert r is not None and r.kind == "repos"
        assert r.payload["query"] == "openhands"

    def test_branches(self):
        r = self.parse("branches owner/repo")
        assert r is not None and r.kind == "branches"
        assert r.payload["repo"] == "owner/repo"

    def test_suggest(self):
        r = self.parse("suggest owner/repo main")
        assert r is not None and r.kind == "suggest"
        assert r.payload["branch"] == "main"

    def test_sandbox_list(self):
        r = self.parse("sandbox list")
        assert r is not None and r.kind == "sandbox_list"

    def test_sandbox_pause(self):
        r = self.parse("sandbox pause sb-001")
        assert r is not None and r.kind == "sandbox_pause"
        assert r.payload["sandbox_id"] == "sb-001"

    def test_sandbox_resume(self):
        r = self.parse("sandbox resume sb-001")
        assert r is not None and r.kind == "sandbox_resume"

    def test_sandbox_delete_needs_confirm(self):
        r = self.parse("sandbox delete sb-001")
        assert r is not None and r.kind == "needs_confirmation"

    def test_sandbox_delete_confirmed(self):
        r = self.parse("sandbox delete sb-001 confirm")
        assert r is not None and r.kind == "sandbox_delete"
        assert r.dangerous is True

    def test_settings(self):
        r = self.parse("settings")
        assert r is not None and r.kind == "settings"

    def test_models(self):
        r = self.parse("models gpt")
        assert r is not None and r.kind == "models"
        assert r.payload["query"] == "gpt"

    def test_profiles(self):
        r = self.parse("profiles")
        assert r is not None and r.kind == "profiles"

    def test_profile_use(self):
        r = self.parse("profile use my-profile")
        assert r is not None and r.kind == "profile_use"
        assert r.payload["name"] == "my-profile"

    def test_secrets(self):
        r = self.parse("secrets")
        assert r is not None and r.kind == "secrets"

    def test_secret_delete_needs_confirm(self):
        r = self.parse("secret delete sec-007")
        assert r is not None and r.kind == "needs_confirmation"

    def test_secret_delete_confirmed(self):
        r = self.parse("secret delete sec-007 confirm")
        assert r is not None and r.kind == "secret_delete"
        assert r.dangerous is True

    def test_status(self):
        r = self.parse("status")
        assert r is not None and r.kind == "status"

    def test_server_info(self):
        r = self.parse("server-info")
        assert r is not None and r.kind == "server_info"

    def test_whoami(self):
        r = self.parse("whoami")
        assert r is not None and r.kind == "whoami"

    def test_git_info(self):
        r = self.parse("git-info")
        assert r is not None and r.kind == "git_info"

    def test_send_message(self):
        r = self.parse("send convABC please continue")
        assert r is not None and r.kind == "send_message"
        assert r.payload["conv_id"] == "convABC"
        assert r.payload["message"] == "please continue"

    def test_agents(self):
        r = self.parse("agents")
        assert r is not None and r.kind == "agents"

    def test_roles(self):
        r = self.parse("roles")
        assert r is not None and r.kind == "roles"

    def test_broadcast(self):
        r = self.parse("broadcast 所有 agent 请注意")
        assert r is not None and r.kind == "broadcast"
        assert "所有" in r.payload["message"]

    def test_handoff(self):
        r = self.parse("handoff @agent-beta 继续实现测试")
        assert r is not None and r.kind == "handoff"
        assert r.payload["to_agent"] == "agent-beta"
        assert "继续" in r.payload["context"]

    def test_unknown_returns_none(self):
        r = self.parse("ask 帮我写代码")
        assert r is None  # ask 交给 parse_devos_intent

    def test_plain_text_returns_none(self):
        r = self.parse("hello world")
        assert r is None


# ─────────────────────────────────────────────────────────────
# dispatch_extended_intent（mock mapper & mar）
# ─────────────────────────────────────────────────────────────

class TestDispatchExtendedIntent:
    def _dispatch(self, kind: str, payload: dict = {}) -> str:
        try:
            from devos_chat_worker.slack_bridge import DevosIntent, dispatch_extended_intent
            import devos_chat_worker.openhands_slack_mapper as mapper
            import devos_chat_worker.multi_agent_router as mar
        except ImportError:
            from slack_bridge import DevosIntent, dispatch_extended_intent  # type: ignore
            import openhands_slack_mapper as mapper  # type: ignore
            import multi_agent_router as mar  # type: ignore

        intent = DevosIntent(kind=kind, endpoint="", payload=payload)

        with patch.object(mapper, "slack_help", return_value="HELP_TEXT"), \
             patch.object(mapper, "slack_list_conversations", return_value="CONV_LIST"), \
             patch.object(mapper, "slack_search_conversations", return_value="SEARCH_RESULT"), \
             patch.object(mapper, "slack_openhands_status", return_value="STATUS_OK"), \
             patch.object(mar, "slack_list_agents", return_value="AGENTS_LIST"), \
             patch.object(mar, "slack_broadcast_message", return_value="BROADCAST_OK"), \
             patch.object(mar, "create_handoff", return_value="HANDOFF_OK"), \
             patch.object(mar, "slack_list_roles", return_value="ROLES_LIST"):
            return dispatch_extended_intent(intent)

    def test_dispatch_help(self):
        result = self._dispatch("help")
        assert "HELP_TEXT" in result

    def test_dispatch_list_conversations(self):
        result = self._dispatch("list_conversations")
        assert "CONV_LIST" in result

    def test_dispatch_status(self):
        result = self._dispatch("status")
        assert "STATUS_OK" in result

    def test_dispatch_agents(self):
        result = self._dispatch("agents")
        assert "AGENTS_LIST" in result

    def test_dispatch_broadcast(self):
        result = self._dispatch("broadcast", {"message": "hello"})
        assert "BROADCAST_OK" in result

    def test_dispatch_handoff(self):
        result = self._dispatch("handoff", {"to_agent": "agent-beta", "context": "work"})
        assert "HANDOFF_OK" in result

    def test_dispatch_unknown_kind(self):
        result = self._dispatch("nonexistent_kind_xyz")
        assert "未知扩展意图" in result or "nonexistent_kind_xyz" in result


# ─────────────────────────────────────────────────────────────
# handle_slack_event 端到端（新意图路径）
# ─────────────────────────────────────────────────────────────

class TestHandleSlackEventExtended:
    def setup_method(self):
        try:
            from devos_chat_worker.slack_bridge import handle_slack_event
        except ImportError:
            from slack_bridge import handle_slack_event  # type: ignore
        self.handle = handle_slack_event

    def _mock_dispatch(self, return_value: str):
        try:
            target = "devos_chat_worker.slack_bridge.dispatch_extended_intent"
        except Exception:
            target = "slack_bridge.dispatch_extended_intent"
        return patch(target, return_value=return_value)

    def test_list_intent_handled(self):
        with self._mock_dispatch("CONV_LIST"):
            result = self.handle(_event("list"))
        assert result["handled"] is True
        assert result["replyText"] == "CONV_LIST"
        assert result["intent"]["kind"] == "list_conversations"

    def test_status_intent_handled(self):
        with self._mock_dispatch("STATUS_OK"):
            result = self.handle(_event("status"))
        assert result["handled"] is True
        assert result["replyText"] == "STATUS_OK"

    def test_help_intent_handled(self):
        with self._mock_dispatch("HELP_TEXT"):
            result = self.handle(_event("help"))
        assert result["handled"] is True

    def test_agents_intent_handled(self):
        with self._mock_dispatch("AGENTS_LIST"):
            result = self.handle(_event("agents"))
        assert result["handled"] is True

    def test_dry_run_extended_intent(self):
        result = self.handle(_event("list"), config={"dry_run": True})
        assert result["handled"] is True
        assert "DRY RUN" in result["replyText"]
        assert result["intent"]["kind"] == "list_conversations"

    def test_delete_without_confirm_returns_needs_confirmation(self):
        result = self.handle(_event("delete abc123"))
        assert result["handled"] is True
        assert result["intent"]["kind"] == "needs_confirmation"
        assert "confirm" in result["replyText"].lower() or "confirm" in result["replyText"]

    def test_delete_confirmed(self):
        with self._mock_dispatch("DELETED"):
            result = self.handle(_event("delete abc123 confirm"))
        assert result["handled"] is True
        assert result["intent"]["kind"] == "delete_conversation"
        assert result["intent"]["dangerous"] is True

    def test_broadcast_intent_handled(self):
        with self._mock_dispatch("BROADCAST_OK"):
            result = self.handle(_event("broadcast 大家注意"))
        assert result["handled"] is True
        assert result["intent"]["kind"] == "broadcast"

    def test_handoff_intent_handled(self):
        with self._mock_dispatch("HANDOFF_OK"):
            result = self.handle(_event("handoff @agent-beta 接着做测试"))
        assert result["handled"] is True
        assert result["intent"]["kind"] == "handoff"

    def test_ask_still_works(self):
        """确认老的 ask 指令不受影响。"""
        result = self.handle(
            _event("ask 帮我写一个快速排序"),
            config={"dry_run": True},
        )
        assert result["handled"] is True
        assert result["intent"]["kind"] == "ask"

    def test_non_devos_prefix_not_handled(self):
        result = self.handle({"type": "message", "text": "hello list", "channel": "C1"})
        assert result["handled"] is False


# ─────────────────────────────────────────────────────────────
# multi_agent_router 单元测试
# ─────────────────────────────────────────────────────────────

class TestMultiAgentRouter:
    def setup_method(self, tmp_path=None):
        try:
            import devos_chat_worker.multi_agent_router as mar
        except ImportError:
            import multi_agent_router as mar  # type: ignore
        self.mar = mar

    def test_parse_agent_mention_with_mention(self):
        agent, rest = self.mar.parse_agent_mention("@agent-alpha ask 帮我写代码")
        assert agent == "agent-alpha"
        assert rest == "ask 帮我写代码"

    def test_parse_agent_mention_without_mention(self):
        agent, rest = self.mar.parse_agent_mention("ask 帮我写代码")
        assert agent is None
        assert rest == "ask 帮我写代码"

    def test_is_for_me_no_mention(self):
        with patch.dict(os.environ, {"AGENT_NAME": "agent-alpha"}):
            assert self.mar.is_for_me(None) is True

    def test_is_for_me_matching(self):
        with patch.dict(os.environ, {"AGENT_NAME": "agent-alpha"}):
            assert self.mar.is_for_me("agent-alpha") is True

    def test_is_for_me_not_matching(self):
        with patch.dict(os.environ, {"AGENT_NAME": "agent-alpha"}):
            assert self.mar.is_for_me("agent-beta") is False

    def test_register_and_load(self, tmp_path=None):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            registry_path = str(Path(d) / "agents.json")
            with patch.dict(os.environ, {
                "AGENT_NAME": "agent-test",
                "AGENT_ROLE": "test",
                "AGENT_REGISTRY_PATH": registry_path,
            }):
                rec = self.mar.register_self(channel_id="C123")
                assert rec.name == "agent-test"
                assert rec.role == "test"
                agents = self.mar._load_registry()
                assert "agent-test" in agents

    def test_deregister(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            registry_path = str(Path(d) / "agents.json")
            with patch.dict(os.environ, {
                "AGENT_NAME": "agent-x",
                "AGENT_ROLE": "general",
                "AGENT_REGISTRY_PATH": registry_path,
            }):
                self.mar.register_self()
                self.mar.deregister_self()
                agents = self.mar._load_registry()
                assert "agent-x" not in agents

    def test_handoff_create_and_poll(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            handoff_dir = str(Path(d) / "handoffs")
            with patch.dict(os.environ, {
                "AGENT_NAME": "agent-sender",
                "HANDOFF_DIR": handoff_dir,
            }):
                # 创建交接
                msg = self.mar.create_handoff(
                    to_agent="agent-receiver",
                    context="继续实现 PR review 功能",
                    conv_id="abc123",
                )
                assert "agent-receiver" in msg

            # 模拟接收方
            with patch.dict(os.environ, {
                "AGENT_NAME": "agent-receiver",
                "HANDOFF_DIR": handoff_dir,
            }):
                packages = self.mar.poll_handoffs()
                assert len(packages) == 1
                assert packages[0].from_agent == "agent-sender"
                assert "PR review" in packages[0].context
                # 再次 poll 应为空（文件已删除）
                assert self.mar.poll_handoffs() == []

    def test_slack_list_agents_empty(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            with patch.dict(os.environ, {"AGENT_REGISTRY_PATH": str(Path(d) / "a.json")}):
                result = self.mar.slack_list_agents()
                assert "无活跃 agent" in result

    def test_slack_list_agents_with_agents(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            registry_path = str(Path(d) / "agents.json")
            with patch.dict(os.environ, {
                "AGENT_NAME": "agent-alpha",
                "AGENT_ROLE": "backend",
                "AGENT_REGISTRY_PATH": registry_path,
            }):
                self.mar.register_self()
                result = self.mar.slack_list_agents()
                assert "agent-alpha" in result
                assert "backend" in result

    def test_broadcast_no_token(self):
        with patch.dict(os.environ, {}, clear=True):
            ok = self.mar.broadcast_to_channel("test message")
            assert ok is False

    def test_set_agent_status(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            registry_path = str(Path(d) / "agents.json")
            with patch.dict(os.environ, {
                "AGENT_NAME": "agent-alpha",
                "AGENT_ROLE": "general",
                "AGENT_REGISTRY_PATH": registry_path,
            }):
                self.mar.register_self()
                self.mar.set_agent_status("busy", task="writing tests")
                agents = self.mar._load_registry()
                assert agents["agent-alpha"].status == "busy"
                assert agents["agent-alpha"].current_task == "writing tests"


# ─────────────────────────────────────────────────────────────
# openhands_slack_mapper 单元测试（全部 mock HTTP）
# ─────────────────────────────────────────────────────────────

class TestOpenHandsSlackMapper:
    def setup_method(self):
        try:
            import devos_chat_worker.openhands_slack_mapper as mapper
        except ImportError:
            import openhands_slack_mapper as mapper  # type: ignore
        self.mapper = mapper

    def _mock_get(self, return_data: dict):
        try:
            target = "devos_chat_worker.openhands_slack_mapper._get"
        except Exception:
            target = "openhands_slack_mapper._get"
        return patch(target, return_value=return_data)

    def _mock_post(self, return_data: dict = {}):
        try:
            target = "devos_chat_worker.openhands_slack_mapper._post"
        except Exception:
            target = "openhands_slack_mapper._post"
        return patch(target, return_value=return_data)

    def _mock_delete(self):
        try:
            target = "devos_chat_worker.openhands_slack_mapper._delete"
        except Exception:
            target = "openhands_slack_mapper._delete"
        return patch(target, return_value={})

    def test_list_conversations(self):
        with self._mock_get({
            "appConversations": [
                {"conversationId": "abc12345", "title": "Test Conv", "status": "running"}
            ]
        }):
            result = self.mapper.slack_list_conversations()
        assert "abc1234" in result
        assert "Test Conv" in result

    def test_list_conversations_empty(self):
        with self._mock_get({"appConversations": []}):
            result = self.mapper.slack_list_conversations()
        assert "无对话" in result

    def test_search_conversations(self):
        with self._mock_get({
            "appConversations": [
                {"conversationId": "xyz12345", "title": "Feature Branch"}
            ]
        }):
            result = self.mapper.slack_search_conversations("feature")
        assert "Feature Branch" in result

    def test_delete_conversation(self):
        with self._mock_delete():
            result = self.mapper.slack_delete_conversation("abc123")
        assert "✅" in result and "abc123" in result

    def test_list_repos(self):
        with self._mock_get({
            "repositories": [
                {"fullName": "owner/my-repo", "isPrivate": False}
            ]
        }):
            result = self.mapper.slack_list_repos()
        assert "owner/my-repo" in result

    def test_list_files(self):
        with self._mock_get({
            "children": [
                {"name": "main.py", "isDirectory": False},
                {"name": "src", "isDirectory": True},
            ]
        }):
            result = self.mapper.slack_list_files("conv1", "/")
        assert "main.py" in result
        assert "src" in result

    def test_read_file_python(self):
        with self._mock_get({"content": "def hello():\n    pass\n"}):
            result = self.mapper.slack_read_file("conv1", "/main.py")
        assert "```python" in result
        assert "def hello" in result

    def test_status_no_url(self):
        with patch.dict(os.environ, {}, clear=True):
            result = self.mapper.slack_openhands_status()
        assert "disconnected" in result

    def test_status_reachable(self):
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"version": "1.2.3"}
        mock_resp.raise_for_status = MagicMock()
        with patch.dict(os.environ, {"OPENHANDS_URL": "http://localhost:3000"}):
            with patch("requests.get", return_value=mock_resp):
                result = self.mapper.slack_openhands_status()
        assert "connected" in result
        assert "1.2.3" in result

    def test_status_unreachable(self):
        with patch.dict(os.environ, {"OPENHANDS_URL": "http://localhost:3000"}):
            with patch("requests.get", side_effect=ConnectionError("refused")):
                result = self.mapper.slack_openhands_status()
        assert "degraded" in result

    def test_list_sandboxes(self):
        with self._mock_get({
            "sandboxes": [
                {"sandboxId": "sb-001", "status": "running", "conversationId": "c123"}
            ]
        }):
            result = self.mapper.slack_list_sandboxes()
        assert "sb-001" in result

    def test_list_secrets(self):
        with self._mock_get({"secrets": [{"name": "MY_TOKEN"}]}):
            result = self.mapper.slack_list_secrets()
        assert "MY_TOKEN" in result

    def test_get_settings(self):
        with self._mock_get({"llm": {"model": "gpt-4o", "provider": "openai"}, "agent": "CodeActAgent"}):
            result = self.mapper.slack_get_settings()
        assert "gpt-4o" in result
        assert "openai" in result

    def test_list_models(self):
        with self._mock_get({"models": ["gpt-4o", "claude-3-5-sonnet"]}):
            result = self.mapper.slack_list_models()
        assert "gpt-4o" in result
        assert "claude-3-5-sonnet" in result

    def test_help_text_complete(self):
        result = self.mapper.slack_help()
        # 验证关键章节存在
        assert "对话管理" in result
        assert "文件系统" in result
        assert "多 Agent 协作" in result
        assert "沙盒管理" in result
        assert "Git 集成" in result
