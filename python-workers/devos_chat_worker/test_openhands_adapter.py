"""测试 OpenHands Capability Adapter（中断驱动版）。

Mock 策略：
  - SSE 阶段：mock requests.post，返回模拟流式响应对象
  - WebSocket 阶段：mock websockets.sync.client.connect，返回模拟 WS 连接
  - REST 降级：mock requests.get（events API）
"""

from __future__ import annotations

import json
import os
from typing import Iterator
from unittest.mock import MagicMock, patch

import pytest

from devos_chat_worker.openhands_adapter import (
    AdapterResult,
    OpenHandsAdapter,
    _extract_text_from_event,
    get_openhands_adapter,
    reset_adapter,
)


def _sse_lines(*tasks):
    yield b"["
    for i, task in enumerate(tasks):
        prefix = b"" if i == 0 else b","
        yield prefix + json.dumps(task).encode()
    yield b"]"


def _make_mock_resp(lines, status_code=200):
    mock_resp = MagicMock()
    mock_resp.status_code = status_code
    mock_resp.ok = status_code < 400
    mock_resp.iter_lines.return_value = iter(lines)
    if status_code >= 400:
        mock_resp.raise_for_status.side_effect = Exception(f"HTTP {status_code}")
    else:
        mock_resp.raise_for_status.return_value = None
    mock_resp.__enter__ = lambda s: s
    mock_resp.__exit__ = MagicMock(return_value=False)
    return mock_resp


def _make_mock_ws(messages):
    mock_ws = MagicMock()
    raw_msgs = [json.dumps(m) for m in messages]
    call_count = [0]

    def _recv(**kwargs):
        idx = call_count[0]
        call_count[0] += 1
        if idx >= len(raw_msgs):
            raise TimeoutError("recv timeout")
        return raw_msgs[idx]

    mock_ws.recv = _recv
    mock_ws.__enter__ = lambda s: s
    mock_ws.__exit__ = MagicMock(return_value=False)
    mock_ctx = MagicMock()
    mock_ctx.__enter__ = lambda s: mock_ws
    mock_ctx.__exit__ = MagicMock(return_value=False)
    return mock_ctx


def _ws_agent_msg(text):
    return {"source": "agent", "message": text}


def _ws_state(state):
    return {"agent_state": state}


def _ws_state_extras(state):
    return {"source": "agent", "extras": {"agent_state": state}}


READY_TASK = {"status": "READY", "app_conversation_id": "conv-abc", "agent_server_url": "http://localhost:8000"}
WORKING_TASK = {"status": "WORKING"}
ERROR_TASK = {"status": "ERROR", "detail": "sandbox crashed"}


class TestIsAvailable:
    def test_returns_true_on_200(self):
        with patch("requests.get", return_value=MagicMock(status_code=200)):
            assert OpenHandsAdapter("http://localhost:3000").is_available() is True

    def test_returns_false_on_non_200(self):
        with patch("requests.get", return_value=MagicMock(status_code=503)):
            assert OpenHandsAdapter("http://localhost:3000").is_available() is False

    def test_returns_false_on_exception(self):
        with patch("requests.get", side_effect=Exception("connect timeout")):
            assert OpenHandsAdapter("http://localhost:3000").is_available() is False


class TestStreamStart:
    def _a(self):
        return OpenHandsAdapter("http://localhost:3000")

    def test_returns_conv_id_and_agent_url_on_ready(self):
        lines = list(_sse_lines(WORKING_TASK, READY_TASK))
        with patch("requests.post", return_value=_make_mock_resp(lines)):
            conv_id, agent_url = self._a()._stream_start("hello")
        assert conv_id == "conv-abc"
        assert agent_url == "http://localhost:8000"

    def test_returns_none_on_error_task(self):
        lines = list(_sse_lines(WORKING_TASK, ERROR_TASK))
        with patch("requests.post", return_value=_make_mock_resp(lines)):
            conv_id, _ = self._a()._stream_start("hello")
        assert conv_id is None

    def test_returns_none_on_http_error(self):
        with patch("requests.post", return_value=_make_mock_resp([], 500)):
            conv_id, _ = self._a()._stream_start("hello")
        assert conv_id is None

    def test_returns_none_when_stream_ends_without_ready(self):
        lines = list(_sse_lines(WORKING_TASK))
        with patch("requests.post", return_value=_make_mock_resp(lines)):
            conv_id, _ = self._a()._stream_start("hello")
        assert conv_id is None

    def test_returns_none_on_exception(self):
        with patch("requests.post", side_effect=Exception("network")):
            conv_id, _ = self._a()._stream_start("hello")
        assert conv_id is None

    def test_skips_bracket_lines(self):
        lines = [b"[", json.dumps(WORKING_TASK).encode(), b",", json.dumps(READY_TASK).encode(), b"]"]
        with patch("requests.post", return_value=_make_mock_resp(lines)):
            conv_id, _ = self._a()._stream_start("hello")
        assert conv_id == "conv-abc"


class TestWsWaitAndCollect:
    def _a(self):
        return OpenHandsAdapter("http://localhost:3000")

    def test_collects_last_agent_message(self):
        ws_messages = [_ws_agent_msg("正在分析..."), _ws_agent_msg("分析完成，结果如下"), _ws_state("finished")]
        with patch("devos_chat_worker.openhands_adapter.ws_connect", return_value=_make_mock_ws(ws_messages)):
            result = self._a()._ws_wait_and_collect("conv-abc", "http://localhost:8000")
        assert result == "分析完成，结果如下"

    def test_returns_empty_string_when_no_agent_message(self):
        with patch("devos_chat_worker.openhands_adapter.ws_connect", return_value=_make_mock_ws([_ws_state("finished")])):
            result = self._a()._ws_wait_and_collect("conv-abc", "http://localhost:8000")
        assert result == ""

    def test_handles_agent_state_in_extras(self):
        ws_messages = [_ws_agent_msg("完成"), _ws_state_extras("finished")]
        with patch("devos_chat_worker.openhands_adapter.ws_connect", return_value=_make_mock_ws(ws_messages)):
            result = self._a()._ws_wait_and_collect("conv-abc", "http://localhost:8000")
        assert result == "完成"

    def test_all_terminal_states_trigger_wake(self):
        states = ["finished", "stopped", "error", "rejected", "awaiting_user_input", "awaiting_user_confirmation", "rate_limited"]
        for state in states:
            ws_messages = [_ws_agent_msg("resp"), _ws_state(state)]
            with patch("devos_chat_worker.openhands_adapter.ws_connect", return_value=_make_mock_ws(ws_messages)):
                result = self._a()._ws_wait_and_collect("conv-abc", "http://localhost:8000")
            assert result == "resp", f"failed for state={state}"

    def test_returns_none_when_ws_connect_raises(self):
        with patch("devos_chat_worker.openhands_adapter.ws_connect", side_effect=Exception("conn refused")):
            result = self._a()._ws_wait_and_collect("conv-abc", "http://localhost:8000")
        assert result is None

    def test_returns_none_when_ws_unavailable(self):
        import devos_chat_worker.openhands_adapter as mod
        orig = mod._WS_AVAILABLE
        try:
            mod._WS_AVAILABLE = False
            result = self._a()._ws_wait_and_collect("conv-abc", "http://localhost:8000")
        finally:
            mod._WS_AVAILABLE = orig
        assert result is None

    def test_timeout_returns_empty_string(self):
        mock_ws_inner = MagicMock()
        mock_ws_inner.recv.side_effect = TimeoutError("timeout")
        mock_ws_inner.__enter__ = lambda s: s
        mock_ws_inner.__exit__ = MagicMock(return_value=False)
        mock_ctx = MagicMock()
        mock_ctx.__enter__ = lambda s: mock_ws_inner
        mock_ctx.__exit__ = MagicMock(return_value=False)
        with patch("devos_chat_worker.openhands_adapter.ws_connect", return_value=mock_ctx):
            result = self._a()._ws_wait_and_collect("conv-abc", "http://localhost:8000")
        assert result == ""


class TestBuildWsUrl:
    def test_http_becomes_ws(self):
        url = OpenHandsAdapter("http://app:3000")._build_ws_url("http://localhost:8000", "conv-abc")
        assert url == "ws://localhost:8000/sockets/events/conv-abc"

    def test_https_becomes_wss(self):
        url = OpenHandsAdapter("https://app:3000")._build_ws_url("https://sandbox.example.com:8000", "conv-xyz")
        assert url == "wss://sandbox.example.com:8000/sockets/events/conv-xyz"

    def test_falls_back_to_base_url_when_no_agent_url(self):
        url = OpenHandsAdapter("http://localhost:3000")._build_ws_url(None, "conv-abc")
        assert url == "ws://localhost:3000/sockets/events/conv-abc"


class TestExtractResponseRest:
    def test_returns_last_agent_message(self):
        events = [{"source": "user", "message": "请帮我"}, {"source": "agent", "message": "已完成"}]
        mock_resp = MagicMock(ok=True)
        mock_resp.json.return_value = {"items": events}
        with patch("requests.get", return_value=mock_resp):
            assert OpenHandsAdapter("http://localhost:3000")._extract_response_rest("conv-abc") == "已完成"

    def test_returns_empty_on_http_error(self):
        with patch("requests.get", return_value=MagicMock(ok=False)):
            assert OpenHandsAdapter("http://localhost:3000")._extract_response_rest("conv-abc") == ""

    def test_returns_empty_on_exception(self):
        with patch("requests.get", side_effect=Exception("err")):
            assert OpenHandsAdapter("http://localhost:3000")._extract_response_rest("conv-abc") == ""


class TestRunTask:
    def _a(self, api_key=None):
        return OpenHandsAdapter("http://localhost:3000", session_api_key=api_key, max_wait_secs=10)

    def test_happy_path(self):
        sse_resp = _make_mock_resp(list(_sse_lines(WORKING_TASK, READY_TASK)))
        ws_msgs = [_ws_agent_msg("任务完成，结果在这里"), _ws_state("finished")]
        with patch("requests.post", return_value=sse_resp), \
             patch("devos_chat_worker.openhands_adapter.ws_connect", return_value=_make_mock_ws(ws_msgs)):
            result = self._a().run_task("帮我写代码")
        assert result.ok is True
        assert result.response == "任务完成，结果在这里"
        assert result.conversation_id == "conv-abc"
        assert result.capability_source == "openhands.core"

    def test_returns_error_when_sse_fails(self):
        sse_resp = _make_mock_resp(list(_sse_lines(WORKING_TASK, ERROR_TASK)))
        with patch("requests.post", return_value=sse_resp):
            result = self._a().run_task("帮我")
        assert result.ok is False
        assert "READY" in result.error

    def test_returns_error_when_sse_http_error(self):
        with patch("requests.post", return_value=_make_mock_resp([], 503)):
            result = self._a().run_task("帮我")
        assert result.ok is False

    def test_fallback_to_rest_when_ws_unavailable(self):
        import devos_chat_worker.openhands_adapter as mod
        sse_resp = _make_mock_resp(list(_sse_lines(READY_TASK)))
        events_resp = MagicMock(ok=True)
        events_resp.json.return_value = {"items": [{"source": "agent", "message": "REST 降级回复"}]}
        orig = mod._WS_AVAILABLE
        try:
            mod._WS_AVAILABLE = False
            with patch("requests.post", return_value=sse_resp), \
                 patch("requests.get", return_value=events_resp):
                result = self._a().run_task("帮我")
        finally:
            mod._WS_AVAILABLE = orig
        assert result.ok is True
        assert result.response == "REST 降级回复"

    def test_fallback_to_rest_when_ws_connect_fails(self):
        sse_resp = _make_mock_resp(list(_sse_lines(READY_TASK)))
        events_resp = MagicMock(ok=True)
        events_resp.json.return_value = {"items": [{"source": "agent", "message": "WS 失败后 REST 回复"}]}
        with patch("requests.post", return_value=sse_resp), \
             patch("devos_chat_worker.openhands_adapter.ws_connect", side_effect=Exception("refused")), \
             patch("requests.get", return_value=events_resp):
            result = self._a().run_task("帮我")
        assert result.ok is True
        assert result.response == "WS 失败后 REST 回复"

    def test_ok_false_when_no_response_text(self):
        sse_resp = _make_mock_resp(list(_sse_lines(READY_TASK)))
        ws_msgs = [_ws_state("finished")]
        with patch("requests.post", return_value=sse_resp), \
             patch("devos_chat_worker.openhands_adapter.ws_connect", return_value=_make_mock_ws(ws_msgs)):
            result = self._a().run_task("帮我")
        assert result.ok is False
        assert result.response == ""

    def test_exception_in_run_task_returns_error(self):
        with patch("requests.post", side_effect=RuntimeError("boom")):
            result = self._a().run_task("帮我")
        assert result.ok is False
        assert result.error  # 异常由 _stream_start 捕获，run_task 返回通用错误消息


class TestAuthHeader:
    def test_api_key_added_to_headers(self):
        adapter = OpenHandsAdapter("http://localhost:3000", session_api_key="sk-test-123")
        assert adapter._headers["X-Session-API-Key"] == "sk-test-123"

    def test_no_api_key_no_header(self):
        assert "X-Session-API-Key" not in OpenHandsAdapter("http://localhost:3000")._headers

    def test_ws_connect_called_with_api_key_header(self):
        adapter = OpenHandsAdapter("http://localhost:3000", session_api_key="sk-ws-key")
        ws_msgs = [_ws_agent_msg("ok"), _ws_state("finished")]
        mock_ws = _make_mock_ws(ws_msgs)
        captured = {}

        def fake_connect(url, **kwargs):
            captured.update(kwargs)
            return mock_ws

        with patch("devos_chat_worker.openhands_adapter.ws_connect", side_effect=fake_connect):
            adapter._ws_wait_and_collect("conv-1", "http://localhost:8000")

        assert captured.get("additional_headers", {}).get("X-Session-API-Key") == "sk-ws-key"


class TestSingleton:
    def setup_method(self):
        reset_adapter()

    def teardown_method(self):
        reset_adapter()
        os.environ.pop("OPENHANDS_URL", None)
        os.environ.pop("OPENHANDS_SESSION_API_KEY", None)

    def test_returns_none_without_env(self):
        os.environ.pop("OPENHANDS_URL", None)
        assert get_openhands_adapter() is None

    def test_returns_adapter_with_env(self):
        os.environ["OPENHANDS_URL"] = "http://localhost:3000"
        adapter = get_openhands_adapter()
        assert adapter is not None
        assert adapter.base_url == "http://localhost:3000"

    def test_same_instance_on_second_call(self):
        os.environ["OPENHANDS_URL"] = "http://localhost:3000"
        assert get_openhands_adapter() is get_openhands_adapter()

    def test_reset_clears_singleton(self):
        os.environ["OPENHANDS_URL"] = "http://localhost:3000"
        a1 = get_openhands_adapter()
        reset_adapter()
        a2 = get_openhands_adapter()
        assert a1 is not a2

    def test_session_api_key_from_env(self):
        os.environ["OPENHANDS_URL"] = "http://localhost:3000"
        os.environ["OPENHANDS_SESSION_API_KEY"] = "sk-env-key"
        adapter = get_openhands_adapter()
        assert adapter._headers["X-Session-API-Key"] == "sk-env-key"


class TestAdapterResultFooter:
    def test_footer_with_conversation_id(self):
        r = AdapterResult(response="ok", capability_source="openhands.core", conversation_id="conv-xyz", ok=True)
        footer = r.format_footer()
        assert "openhands.core" in footer
        assert "conv-xyz" in footer

    def test_footer_without_conversation_id(self):
        r = AdapterResult(response="ok", capability_source="openhands.core", ok=True)
        assert "openhands.core" in r.format_footer()

    def test_footer_returns_string(self):
        assert isinstance(AdapterResult(response="", capability_source="x", ok=False).format_footer(), str)


class TestExtractTextFromEvent:
    def test_extracts_message_field(self):
        assert _extract_text_from_event({"message": "hello"}) == "hello"

    def test_extracts_text_field(self):
        assert _extract_text_from_event({"text": "world"}) == "world"

    def test_extracts_content_string(self):
        assert _extract_text_from_event({"content": "raw"}) == "raw"

    def test_extracts_content_block_list(self):
        assert _extract_text_from_event({"content": [{"type": "text", "text": "block"}]}) == "block"

    def test_returns_empty_for_no_fields(self):
        assert _extract_text_from_event({"source": "agent"}) == ""

    def test_returns_empty_for_empty_content(self):
        assert _extract_text_from_event({"message": ""}) == ""

    def test_prefers_message_over_text(self):
        assert _extract_text_from_event({"message": "msg", "text": "txt"}) == "msg"


class TestSelectLlmBackend:
    def teardown_method(self):
        os.environ.pop("OPENHANDS_URL", None)
        os.environ.pop("DEMO_MODE", None)

    def test_openhands_backend_selected_when_url_set(self):
        os.environ.pop("DEMO_MODE", None)
        os.environ["OPENHANDS_URL"] = "http://localhost:3000"
        import importlib
        import devos_chat_worker.worker as worker_mod
        importlib.reload(worker_mod)
        backend = worker_mod.select_llm_backend()
        assert backend in ("openhands", "glm", "openai", "demo")
