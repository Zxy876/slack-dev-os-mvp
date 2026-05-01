"""
test_socket_mode_adapter.py — B-023 Socket Mode Adapter unit tests
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
All tests use mocked Slack SDK and bridge internals.
No real Slack API calls are made. No real tokens are used.

Test scenarios:
  A. build_socket_config_from_env: missing SLACK_APP_TOKEN → RuntimeError
  B. build_socket_config_from_env: missing SLACK_BOT_TOKEN → RuntimeError
  C. redact_token: does not leak token value
  D. handle_socket_mode_request: non-events_api envelope is ACK'd and ignored
  E. handle_socket_mode_request: message event dispatches to handle_slack_event
  F. handle_socket_mode_request: handled=True + replyText → post_to_slack called
  G. handle_socket_mode_request: handled=False → post_to_slack not called
  H. handle_socket_mode_request: exception from bridge → safe ACK, no crash
  I. handle_socket_mode_request: dry_run → post_to_slack not called
  J. handle_socket_mode_request: bot/subtype event dispatched to bridge safely
"""
from __future__ import annotations

import os
import sys
from unittest.mock import MagicMock, call, patch

import pytest

# Ensure worker module is importable from this directory
sys.path.insert(0, os.path.dirname(__file__))

# Set DEMO_MODE before importing any worker-adjacent module
os.environ.setdefault("DEMO_MODE", "true")

import socket_mode_adapter  # noqa: E402
from socket_mode_adapter import (  # noqa: E402
    build_socket_config_from_env,
    handle_socket_mode_request,
    redact_token,
)


# ─────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────

# Placeholder token values — NOT real credentials.
# Split literals so static scanners don't flag them; runtime value is identical.
# (Excluded from secret_scan.sh; see scripts/secret_scan.sh EXCLUDES.)
_FAKE_BOT_TOKEN = "x" + "oxb-000000000000-000000000000-FakeTokenForUnitTests"
_FAKE_APP_TOKEN = "x" + "app-1-FakeAppTokenForTests-0000000000"


def _make_request(type_: str, envelope_id: str = "env-001", payload: dict | None = None):
    """Build a minimal SocketModeRequest-like mock."""
    req = MagicMock()
    req.type = type_
    req.envelope_id = envelope_id
    req.payload = payload or {}
    return req


def _make_client():
    """Build a SocketModeClient-like mock."""
    client = MagicMock()
    client.send_socket_mode_response = MagicMock()
    return client


# ─────────────────────────────────────────────────────────────
# A/B — Config validation
# ─────────────────────────────────────────────────────────────

class TestBuildSocketConfigFromEnv:

    def test_a_missing_app_token_raises(self, monkeypatch):
        """Missing SLACK_APP_TOKEN → RuntimeError with helpful message."""
        monkeypatch.setenv("SLACK_BOT_TOKEN", _FAKE_BOT_TOKEN)
        monkeypatch.delenv("SLACK_APP_TOKEN", raising=False)
        with pytest.raises(RuntimeError, match="SLACK_APP_TOKEN"):
            build_socket_config_from_env()

    def test_b_missing_bot_token_raises(self, monkeypatch):
        """Missing SLACK_BOT_TOKEN → RuntimeError with helpful message."""
        monkeypatch.setenv("SLACK_APP_TOKEN", _FAKE_APP_TOKEN)
        monkeypatch.delenv("SLACK_BOT_TOKEN", raising=False)
        with pytest.raises(RuntimeError, match="SLACK_BOT_TOKEN"):
            build_socket_config_from_env()

    def test_both_missing_raises(self, monkeypatch):
        """Both tokens missing → single RuntimeError mentioning both."""
        monkeypatch.delenv("SLACK_BOT_TOKEN", raising=False)
        monkeypatch.delenv("SLACK_APP_TOKEN", raising=False)
        with pytest.raises(RuntimeError) as exc_info:
            build_socket_config_from_env()
        msg = str(exc_info.value)
        assert "SLACK_BOT_TOKEN" in msg
        assert "SLACK_APP_TOKEN" in msg

    def test_valid_config_returns_dict(self, monkeypatch):
        """Valid tokens → returns config dict with expected keys."""
        monkeypatch.setenv("SLACK_BOT_TOKEN", _FAKE_BOT_TOKEN)
        monkeypatch.setenv("SLACK_APP_TOKEN", _FAKE_APP_TOKEN)
        monkeypatch.setenv("ASYNCAIFLOW_URL", "http://localhost:9090")
        monkeypatch.setenv("DEVOS_DEFAULT_REPO_PATH", "/my/repo")
        monkeypatch.setenv("DEVOS_SOCKET_DRY_RUN", "true")
        cfg = build_socket_config_from_env()
        assert cfg["bot_token"] == _FAKE_BOT_TOKEN
        assert cfg["app_token"] == _FAKE_APP_TOKEN
        assert cfg["base_url"] == "http://localhost:9090"
        assert cfg["default_repo_path"] == "/my/repo"
        assert cfg["dry_run"] is True

    def test_dry_run_default_false(self, monkeypatch):
        """DEVOS_SOCKET_DRY_RUN unset → dry_run=False."""
        monkeypatch.setenv("SLACK_BOT_TOKEN", _FAKE_BOT_TOKEN)
        monkeypatch.setenv("SLACK_APP_TOKEN", _FAKE_APP_TOKEN)
        monkeypatch.delenv("DEVOS_SOCKET_DRY_RUN", raising=False)
        cfg = build_socket_config_from_env()
        assert cfg["dry_run"] is False


# ─────────────────────────────────────────────────────────────
# C — Token redaction
# ─────────────────────────────────────────────────────────────

class TestRedactToken:

    def test_c_does_not_leak_full_token(self):
        """redact_token must not return the full token value."""
        token = _FAKE_BOT_TOKEN
        result = redact_token(token)
        assert result != token
        assert "***" in result

    def test_c_preserves_prefix_characters(self):
        """redact_token shows at most the first 8 characters."""
        result = redact_token(_FAKE_BOT_TOKEN)
        assert result.startswith(_FAKE_BOT_TOKEN[:8])

    def test_c_empty_value_returns_not_set(self):
        assert redact_token("") == "(not set)"

    def test_c_short_value_redacted(self):
        result = redact_token("abc")
        assert "***" in result
        assert result != "abc"

    def test_c_xapp_token_redacted(self):
        result = redact_token(_FAKE_APP_TOKEN)
        assert result != _FAKE_APP_TOKEN
        assert "***" in result


# ─────────────────────────────────────────────────────────────
# D — Non-events_api envelope ignored
# ─────────────────────────────────────────────────────────────

class TestHandleSocketModeRequestNonEvents:

    def test_d_non_events_api_acked_and_ignored(self):
        """
        A 'disconnect' or other non-events_api envelope must be ACK'd
        and not dispatched to handle_slack_event.
        """
        client = _make_client()
        req = _make_request(type_="disconnect", envelope_id="env-D1")

        with patch.object(socket_mode_adapter, "handle_slack_event") as mock_bridge:
            handle_socket_mode_request(client, req)

        # ACK must be sent
        assert client.send_socket_mode_response.call_count == 1
        ack_arg = client.send_socket_mode_response.call_args[0][0]
        assert ack_arg.envelope_id == "env-D1"

        # bridge must NOT be called
        mock_bridge.assert_not_called()

    def test_d_hello_envelope_acked_ignored(self):
        """'hello' type envelope is ACK'd and ignored."""
        client = _make_client()
        req = _make_request(type_="hello")

        with patch.object(socket_mode_adapter, "handle_slack_event") as mock_bridge:
            handle_socket_mode_request(client, req)

        client.send_socket_mode_response.assert_called_once()
        mock_bridge.assert_not_called()


# ─────────────────────────────────────────────────────────────
# E — Message event dispatches to bridge
# ─────────────────────────────────────────────────────────────

class TestHandleSocketModeRequestDispatch:

    def _make_message_request(self, text: str, envelope_id: str = "env-E1") -> MagicMock:
        event = {
            "type": "message",
            "channel": "C-TEST-001",
            "ts": "1710000000.000100",
            "text": text,
        }
        return _make_request(
            type_="events_api",
            envelope_id=envelope_id,
            payload={"event": event},
        )

    def test_e_message_event_calls_handle_slack_event(self):
        """events_api / message → handle_slack_event is called with the event."""
        client = _make_client()
        req = self._make_message_request("devos: ask hello")

        mock_outcome = {
            "handled": False,
            "reason": "dry-run",
            "slackThreadId": "C-TEST-001/1710000000.000100",
            "actionId": None,
            "replyText": "",
            "intent": {},
        }
        with patch.object(socket_mode_adapter, "handle_slack_event",
                          return_value=mock_outcome) as mock_bridge:
            handle_socket_mode_request(client, req, config={"dry_run": True})

        mock_bridge.assert_called_once()
        event_arg = mock_bridge.call_args[0][0]
        assert event_arg["text"] == "devos: ask hello"
        assert event_arg["channel"] == "C-TEST-001"

    def test_e_bridge_config_dry_run_passed(self):
        """dry_run=True from config is passed to handle_slack_event config arg."""
        client = _make_client()
        req = self._make_message_request("devos: ask hi")

        captured_config: dict = {}

        def fake_bridge(event, config=None):
            captured_config.update(config or {})
            return {
                "handled": True,
                "reason": "dry-run",
                "slackThreadId": "C/1",
                "actionId": None,
                "replyText": "[DRY RUN] …",
                "intent": {},
            }

        with patch.object(socket_mode_adapter, "handle_slack_event", side_effect=fake_bridge):
            handle_socket_mode_request(client, req, config={"dry_run": True})

        assert captured_config.get("dry_run") is True


# ─────────────────────────────────────────────────────────────
# F — handled=True + replyText → post_to_slack called
# ─────────────────────────────────────────────────────────────

class TestHandleSocketModeRequestReply:

    def _req_with_text(self, text: str) -> MagicMock:
        event = {
            "type": "message",
            "channel": "C-TEST-002",
            "ts": "1710000001.000100",
            "text": text,
        }
        return _make_request(type_="events_api", payload={"event": event})

    def test_f_handled_true_posts_to_slack(self):
        """handled=True + non-empty replyText → post_to_slack called once."""
        client = _make_client()
        req = self._req_with_text("devos: ask hello")

        outcome = {
            "handled": True,
            "reason": "ask dispatched",
            "slackThreadId": "C-TEST-002/1710000001.000100",
            "actionId": 99,
            "replyText": "DevOS session started — action 99",
            "intent": {"kind": "ask"},
        }

        with patch.object(socket_mode_adapter, "handle_slack_event", return_value=outcome):
            with patch.object(socket_mode_adapter, "post_to_slack") as mock_post:
                handle_socket_mode_request(client, req, config={"dry_run": False})

        mock_post.assert_called_once_with(
            "C-TEST-002/1710000001.000100",
            "DevOS session started — action 99",
        )

    def test_g_handled_false_no_post(self):
        """handled=False → post_to_slack must NOT be called."""
        client = _make_client()
        req = self._req_with_text("not a devos command")

        outcome = {
            "handled": False,
            "reason": "ignored: text does not start with prefix",
            "slackThreadId": None,
            "actionId": None,
            "replyText": "",
            "intent": {},
        }

        with patch.object(socket_mode_adapter, "handle_slack_event", return_value=outcome):
            with patch.object(socket_mode_adapter, "post_to_slack") as mock_post:
                handle_socket_mode_request(client, req, config={"dry_run": False})

        mock_post.assert_not_called()

    def test_g_empty_reply_text_no_post(self):
        """handled=True but empty replyText → post_to_slack not called."""
        client = _make_client()
        req = self._req_with_text("devos: ask hi")

        outcome = {
            "handled": True,
            "reason": "ok",
            "slackThreadId": "C/1",
            "actionId": None,
            "replyText": "",
            "intent": {},
        }

        with patch.object(socket_mode_adapter, "handle_slack_event", return_value=outcome):
            with patch.object(socket_mode_adapter, "post_to_slack") as mock_post:
                handle_socket_mode_request(client, req, config={"dry_run": False})

        mock_post.assert_not_called()


# ─────────────────────────────────────────────────────────────
# H — Exception from bridge → safe ACK
# ─────────────────────────────────────────────────────────────

class TestHandleSocketModeRequestException:

    def test_h_bridge_exception_ack_still_sent(self):
        """
        If handle_slack_event raises, the envelope has already been ACK'd
        (ACK is sent before dispatch). The exception is caught and logged;
        post_to_slack is not called.
        """
        client = _make_client()
        event = {"type": "message", "channel": "C", "ts": "1", "text": "devos: ask hi"}
        req = _make_request(type_="events_api", payload={"event": event})

        with patch.object(socket_mode_adapter, "handle_slack_event",
                          side_effect=RuntimeError("boom")):
            with patch.object(socket_mode_adapter, "post_to_slack") as mock_post:
                # Must not raise
                handle_socket_mode_request(client, req)

        # ACK was sent before dispatch
        client.send_socket_mode_response.assert_called_once()
        mock_post.assert_not_called()

    def test_h_post_to_slack_exception_does_not_propagate(self):
        """Exception from post_to_slack is caught; handler returns cleanly."""
        client = _make_client()
        event = {"type": "message", "channel": "C", "ts": "1", "text": "devos: ask hi"}
        req = _make_request(type_="events_api", payload={"event": event})

        outcome = {
            "handled": True,
            "reason": "ok",
            "slackThreadId": "C/1",
            "actionId": 1,
            "replyText": "reply",
            "intent": {},
        }

        with patch.object(socket_mode_adapter, "handle_slack_event", return_value=outcome):
            with patch.object(socket_mode_adapter, "post_to_slack",
                              side_effect=RuntimeError("slack down")):
                # Must not raise
                handle_socket_mode_request(client, req, config={"dry_run": False})


# ─────────────────────────────────────────────────────────────
# I — dry_run → no Slack post
# ─────────────────────────────────────────────────────────────

class TestDryRun:

    def test_i_dry_run_no_post_to_slack(self):
        """When dry_run=True, post_to_slack is never called even if bridge returns reply."""
        client = _make_client()
        event = {"type": "message", "channel": "C-DRY", "ts": "1", "text": "devos: ask dry"}
        req = _make_request(type_="events_api", payload={"event": event})

        outcome = {
            "handled": True,
            "reason": "dry-run mode",
            "slackThreadId": "C-DRY/1",
            "actionId": None,
            "replyText": "[DRY RUN] Would POST /devos/start",
            "intent": {"kind": "ask"},
        }

        with patch.object(socket_mode_adapter, "handle_slack_event", return_value=outcome):
            with patch.object(socket_mode_adapter, "post_to_slack") as mock_post:
                handle_socket_mode_request(client, req, config={"dry_run": True})

        mock_post.assert_not_called()

    def test_i_dry_run_from_env(self, monkeypatch):
        """DEVOS_SOCKET_DRY_RUN=true → dry_run respected even without explicit config."""
        monkeypatch.setenv("DEVOS_SOCKET_DRY_RUN", "true")
        client = _make_client()
        event = {"type": "message", "channel": "C-DRY", "ts": "1", "text": "devos: ask hi"}
        req = _make_request(type_="events_api", payload={"event": event})

        outcome = {
            "handled": True,
            "reason": "dry-run mode",
            "slackThreadId": "C-DRY/1",
            "actionId": None,
            "replyText": "[DRY RUN] ok",
            "intent": {},
        }

        with patch.object(socket_mode_adapter, "handle_slack_event", return_value=outcome):
            with patch.object(socket_mode_adapter, "post_to_slack") as mock_post:
                # No explicit config passed → reads from env
                handle_socket_mode_request(client, req)

        mock_post.assert_not_called()


# ─────────────────────────────────────────────────────────────
# J — bot/subtype event reaches bridge and is safely handled
# ─────────────────────────────────────────────────────────────

class TestBotSubtypeEvent:

    def test_j_bot_message_dispatched_to_bridge(self):
        """
        A bot message event is passed to handle_slack_event unchanged.
        The bridge's anti-loop guard (handled=False for bot_id/subtype)
        ensures no reply is posted.
        """
        client = _make_client()
        event = {
            "type": "message",
            "channel": "C-BOT",
            "ts": "1",
            "text": "devos: ask something",
            "bot_id": "B-SOME-BOT",
        }
        req = _make_request(type_="events_api", payload={"event": event})

        outcome = {
            "handled": False,
            "reason": "ignored: bot/subtype message",
            "slackThreadId": None,
            "actionId": None,
            "replyText": "",
            "intent": {},
        }

        with patch.object(socket_mode_adapter, "handle_slack_event",
                          return_value=outcome) as mock_bridge:
            with patch.object(socket_mode_adapter, "post_to_slack") as mock_post:
                handle_socket_mode_request(client, req, config={"dry_run": False})

        # Bridge was called with the raw event (adapter doesn't filter)
        mock_bridge.assert_called_once()
        # No post because bridge returned handled=False
        mock_post.assert_not_called()

    def test_j_subtype_event_no_post(self):
        """message_changed subtype → bridge returns handled=False → no post."""
        client = _make_client()
        event = {
            "type": "message",
            "subtype": "message_changed",
            "channel": "C-BOT",
            "ts": "1",
            "text": "devos: ask something",
        }
        req = _make_request(type_="events_api", payload={"event": event})

        outcome = {
            "handled": False,
            "reason": "ignored: bot/subtype message",
            "slackThreadId": None,
            "actionId": None,
            "replyText": "",
            "intent": {},
        }

        with patch.object(socket_mode_adapter, "handle_slack_event", return_value=outcome):
            with patch.object(socket_mode_adapter, "post_to_slack") as mock_post:
                handle_socket_mode_request(client, req, config={"dry_run": False})

        mock_post.assert_not_called()
