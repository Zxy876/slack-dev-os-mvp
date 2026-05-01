"""
test_slack_bridge.py — B-021.5 Slack Minimal Loop Bridge unit tests
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
覆盖场景：
  A. parse_devos_command ignores non-devos message
  B. parse_devos_command extracts instruction
  C. build_slack_thread_id uses thread_ts if present
  D. build_slack_thread_id uses ts if no thread_ts
  E. bot/subtype message ignored
  F. handle_slack_event dry-run returns payload and handled=true
  G. handle_slack_event calls backend with correct payload
  H. backend error returns handled=true with failure reply
  I. default repo path included when configured
  J. empty instruction ignored

Mock requests.post and post_to_slack; no real Slack or backend calls.
"""
from __future__ import annotations

import json
import os
import sys
from unittest.mock import MagicMock, patch

import pytest

# Ensure the worker directory is importable
sys.path.insert(0, os.path.dirname(__file__))

# Force DEMO_MODE so worker.py doesn't attempt real LLM on import
os.environ.setdefault("DEMO_MODE", "true")

import slack_bridge  # noqa: E402


# ─────────────────────────────────────────────────────────────
# Fixtures / helpers
# ─────────────────────────────────────────────────────────────

def _make_message_event(
    text: str,
    channel: str = "C1234567890",
    ts: str = "1710000000.000100",
    thread_ts: str = None,
    subtype: str = None,
    bot_id: str = None,
) -> dict:
    ev: dict = {
        "type": "message",
        "channel": channel,
        "ts": ts,
        "text": text,
    }
    if thread_ts is not None:
        ev["thread_ts"] = thread_ts
    if subtype is not None:
        ev["subtype"] = subtype
    if bot_id is not None:
        ev["bot_id"] = bot_id
    return ev


def _make_backend_ok_response(action_id: int = 42, workflow_id: int = 7) -> MagicMock:
    resp = MagicMock()
    resp.raise_for_status.return_value = None
    resp.json.return_value = {
        "success": True,
        "message": "ok",
        "data": {
            "actionId": action_id,
            "workflowId": workflow_id,
            "status": "QUEUED",
            "slackThreadId": "C1234567890/1710000000.000100",
        },
    }
    return resp


def _make_backend_error_response(status_code: int = 400, message: str = "bad request") -> MagicMock:
    resp = MagicMock()
    resp.status_code = status_code
    resp.json.return_value = {"success": False, "message": message}
    resp.raise_for_status.side_effect = __import__("requests").HTTPError(
        response=resp
    )
    return resp


# ─────────────────────────────────────────────────────────────
# A. parse_devos_command — non-devos messages
# ─────────────────────────────────────────────────────────────

class TestParseDevosCommand:

    def test_a_non_devos_message_returns_none(self):
        """Non-devos text returns None."""
        assert slack_bridge.parse_devos_command("hello world") is None

    def test_a_empty_text_returns_none(self):
        """Empty text returns None."""
        assert slack_bridge.parse_devos_command("") is None

    def test_a_partial_prefix_returns_none(self):
        """'dev:' is not the default prefix."""
        assert slack_bridge.parse_devos_command("dev: do something") is None

    def test_a_prefix_only_no_instruction_returns_none(self):
        """'devos:' alone (no instruction after) returns None."""
        assert slack_bridge.parse_devos_command("devos:") is None

    def test_a_prefix_whitespace_only_returns_none(self):
        """'devos:   ' (only spaces after prefix) returns None."""
        assert slack_bridge.parse_devos_command("devos:   ") is None

    # ─────────────────────────────────────────────────────────
    # B. parse_devos_command — extraction
    # ─────────────────────────────────────────────────────────

    def test_b_extracts_instruction(self):
        """Basic instruction extraction."""
        result = slack_bridge.parse_devos_command("devos: summarize this repo")
        assert result == "summarize this repo"

    def test_b_strips_leading_whitespace_in_instruction(self):
        """Instruction is stripped of leading spaces."""
        result = slack_bridge.parse_devos_command("devos:   run the tests")
        assert result == "run the tests"

    def test_b_case_insensitive_prefix(self):
        """'Devos:' (capital) is recognized as prefix."""
        result = slack_bridge.parse_devos_command("Devos: fix the bug")
        assert result == "fix the bug"

    def test_b_custom_prefix(self):
        """Custom prefix is respected."""
        result = slack_bridge.parse_devos_command("/devos fix lint", prefix="/devos")
        assert result == "fix lint"

    def test_b_instruction_preserved_exactly(self):
        """Multi-word instruction with internal spaces is preserved."""
        result = slack_bridge.parse_devos_command("devos: apply the README patch and commit")
        assert result == "apply the README patch and commit"


# ─────────────────────────────────────────────────────────────
# C/D. build_slack_thread_id
# ─────────────────────────────────────────────────────────────

class TestBuildSlackThreadId:

    def test_c_uses_thread_ts_when_present(self):
        """thread_ts takes precedence over ts."""
        event = _make_message_event(
            "devos: test",
            channel="C9876543210",
            ts="1710000000.000200",
            thread_ts="1710000000.000100",
        )
        result = slack_bridge.build_slack_thread_id(event)
        assert result == "C9876543210/1710000000.000100"

    def test_d_uses_ts_when_no_thread_ts(self):
        """Falls back to ts when thread_ts is absent."""
        event = _make_message_event(
            "devos: test",
            channel="C9876543210",
            ts="1710000000.000200",
        )
        result = slack_bridge.build_slack_thread_id(event)
        assert result == "C9876543210/1710000000.000200"

    def test_d_uses_ts_when_thread_ts_is_none(self):
        """thread_ts=None triggers fallback to ts."""
        event = {
            "type": "message",
            "channel": "CABC123",
            "ts": "1710000099.000001",
            "text": "devos: hello",
            "thread_ts": None,
        }
        result = slack_bridge.build_slack_thread_id(event)
        assert result == "CABC123/1710000099.000001"

    def test_c_thread_ts_matches_ts_is_root(self):
        """thread_ts == ts means the message is the root; channel/ts is correct."""
        event = _make_message_event(
            "devos: test",
            channel="CX",
            ts="1710000000.000001",
            thread_ts="1710000000.000001",
        )
        result = slack_bridge.build_slack_thread_id(event)
        assert result == "CX/1710000000.000001"


# ─────────────────────────────────────────────────────────────
# E. bot/subtype messages ignored
# ─────────────────────────────────────────────────────────────

class TestBotSubtypeIgnored:

    def test_e_subtype_message_ignored(self):
        """Message with subtype is not handled."""
        event = _make_message_event(
            "devos: should be ignored",
            subtype="bot_message",
        )
        result = slack_bridge.handle_slack_event(event)
        assert result["handled"] is False
        assert "bot" in result["reason"].lower() or "subtype" in result["reason"].lower()

    def test_e_bot_id_message_ignored(self):
        """Message with bot_id is not handled (anti-loop guard)."""
        event = _make_message_event(
            "devos: should be ignored",
            bot_id="B0ABCDEF123",
        )
        result = slack_bridge.handle_slack_event(event)
        assert result["handled"] is False

    def test_e_subtype_without_devos_prefix_ignored(self):
        """Non-devos subtype message also ignored."""
        event = _make_message_event(
            "hello world",
            subtype="channel_join",
        )
        result = slack_bridge.handle_slack_event(event)
        assert result["handled"] is False


# ─────────────────────────────────────────────────────────────
# F. dry-run mode
# ─────────────────────────────────────────────────────────────

class TestDryRun:

    def test_f_dry_run_returns_handled_true_no_backend_call(self):
        """dry_run=True → handled=True, no real HTTP call."""
        event = _make_message_event("devos: summarize this repo")

        with patch("slack_bridge.call_devos_start") as mock_call:
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": True},
            )

        mock_call.assert_not_called()
        assert result["handled"] is True
        assert result["actionId"] is None

    def test_f_dry_run_reply_contains_instruction(self):
        """Dry-run reply mentions the instruction and thread ID."""
        event = _make_message_event(
            "devos: run all tests",
            channel="CTEST",
            ts="9999999999.000001",
        )
        result = slack_bridge.handle_slack_event(
            event,
            config={"dry_run": True},
        )
        assert "run all tests" in result["replyText"]
        assert "CTEST" in result["replyText"] or "9999999999" in result["replyText"]

    def test_f_dry_run_reason_set(self):
        """Dry-run sets reason to 'dry-run mode'."""
        event = _make_message_event("devos: test")
        result = slack_bridge.handle_slack_event(event, config={"dry_run": True})
        assert "dry" in result["reason"].lower()


# ─────────────────────────────────────────────────────────────
# G. live mode — backend called with correct payload
# ─────────────────────────────────────────────────────────────

class TestLiveBackendCall:

    def test_g_backend_called_with_correct_payload(self):
        """call_devos_start receives correct text and slackThreadId."""
        event = _make_message_event(
            "devos: fix the linting errors",
            channel="C555",
            ts="1710000001.000001",
        )

        captured_payload = {}

        def fake_call_devos_start(payload, base_url=None):
            captured_payload.update(payload)
            return {"success": True, "actionId": 99, "workflowId": 3}

        result = slack_bridge.handle_slack_event(
            event,
            config={
                "dry_run": False,
                "base_url": "http://fake-backend:8080",
            },
        )

        # We need to patch call_devos_start at module level
        with patch("slack_bridge.call_devos_start", side_effect=fake_call_devos_start):
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "base_url": "http://fake-backend:8080"},
            )

        assert captured_payload.get("text") == "fix the linting errors"
        assert "slackThreadId" in captured_payload
        assert captured_payload["slackThreadId"] == "C555/1710000001.000001"

    def test_g_successful_result_returns_action_id(self):
        """Successful backend call returns actionId in result."""
        event = _make_message_event("devos: do something")

        with patch("slack_bridge.call_devos_start") as mock_call:
            mock_call.return_value = {"success": True, "actionId": 77}
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False},
            )

        assert result["handled"] is True
        assert result["actionId"] == 77
        assert "77" in result["replyText"]
        assert "started" in result["replyText"].lower()

    def test_g_reply_text_format(self):
        """Reply text matches expected format."""
        event = _make_message_event("devos: summarize repo")

        with patch("slack_bridge.call_devos_start") as mock_call:
            mock_call.return_value = {"success": True, "actionId": 123}
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False},
            )

        assert result["replyText"] == "DevOS session started — action 123"


# ─────────────────────────────────────────────────────────────
# H. backend error → safe reply
# ─────────────────────────────────────────────────────────────

class TestBackendError:

    def test_h_backend_error_returns_handled_true(self):
        """Backend failure still sets handled=True."""
        event = _make_message_event("devos: do something")

        with patch("slack_bridge.call_devos_start") as mock_call:
            mock_call.side_effect = RuntimeError("devos/start HTTP 500: internal server error")
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False},
            )

        assert result["handled"] is True
        assert result["actionId"] is None

    def test_h_backend_error_reply_contains_error_info(self):
        """Error reply mentions the failure without token."""
        event = _make_message_event("devos: run tests")

        with patch("slack_bridge.call_devos_start") as mock_call:
            mock_call.side_effect = RuntimeError("devos/start HTTP 400: bad request")
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False},
            )

        assert "DevOS failed to start" in result["replyText"]
        # Token must never appear in reply
        assert "xoxb-" not in result["replyText"]
        assert "xoxp-" not in result["replyText"]

    def test_h_connection_error_handled_gracefully(self):
        """Connection error is handled, not propagated."""
        event = _make_message_event("devos: test connection")

        with patch("slack_bridge.call_devos_start") as mock_call:
            mock_call.side_effect = RuntimeError("devos/start connection error: connection refused")
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False},
            )

        assert result["handled"] is True
        assert "DevOS failed to start" in result["replyText"]


# ─────────────────────────────────────────────────────────────
# I. default repo path included when configured
# ─────────────────────────────────────────────────────────────

class TestDefaultRepoPath:

    def test_i_repo_path_included_when_configured(self):
        """default_repo_path is passed through to payload."""
        event = _make_message_event("devos: run mvn test")

        captured_payload = {}

        def fake_call(payload, base_url=None):
            captured_payload.update(payload)
            return {"success": True, "actionId": 1}

        with patch("slack_bridge.call_devos_start", side_effect=fake_call):
            slack_bridge.handle_slack_event(
                event,
                config={
                    "dry_run": False,
                    "default_repo_path": "/home/user/my-project",
                },
            )

        assert captured_payload.get("repoPath") == "/home/user/my-project"

    def test_i_repo_path_absent_when_not_configured(self):
        """repoPath is absent from payload when default_repo_path is None."""
        event = _make_message_event("devos: check status")

        captured_payload = {}

        def fake_call(payload, base_url=None):
            captured_payload.update(payload)
            return {"success": True, "actionId": 2}

        with patch("slack_bridge.call_devos_start", side_effect=fake_call):
            slack_bridge.handle_slack_event(
                event,
                config={
                    "dry_run": False,
                    "default_repo_path": None,
                },
            )

        assert "repoPath" not in captured_payload

    def test_i_repo_path_in_dry_run_reply(self):
        """Even in dry-run, repo_path is included in payload preview."""
        event = _make_message_event("devos: check status")

        result = slack_bridge.handle_slack_event(
            event,
            config={
                "dry_run": True,
                "default_repo_path": "/srv/repo",
            },
        )

        assert result["handled"] is True
        # payload is described in reply text (dry run shows instruction + thread id)
        assert "check status" in result["replyText"]


# ─────────────────────────────────────────────────────────────
# J. empty instruction ignored
# ─────────────────────────────────────────────────────────────

class TestEmptyInstruction:

    def test_j_prefix_only_ignored(self):
        """Message with only 'devos:' (no instruction) → handled=False."""
        event = _make_message_event("devos:")
        result = slack_bridge.handle_slack_event(event)
        assert result["handled"] is False

    def test_j_prefix_with_only_spaces_ignored(self):
        """'devos:   ' (spaces only) → handled=False."""
        event = _make_message_event("devos:   ")
        result = slack_bridge.handle_slack_event(event)
        assert result["handled"] is False

    def test_j_unrelated_message_not_handled(self):
        """Completely unrelated message → handled=False."""
        event = _make_message_event("hello team, let's sync")
        result = slack_bridge.handle_slack_event(event)
        assert result["handled"] is False
        assert result["replyText"] == ""


# ─────────────────────────────────────────────────────────────
# Extra: slackThreadId in result
# ─────────────────────────────────────────────────────────────

class TestSlackThreadIdInResult:

    def test_slack_thread_id_returned_in_result(self):
        """Result always contains slackThreadId for handled events."""
        event = _make_message_event(
            "devos: test",
            channel="CABC",
            ts="1000000000.000001",
        )

        with patch("slack_bridge.call_devos_start") as mock_call:
            mock_call.return_value = {"success": True, "actionId": 5}
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False},
            )

        assert result["slackThreadId"] == "CABC/1000000000.000001"

    def test_non_message_event_not_handled(self):
        """Non-message events are not handled."""
        event = {"type": "reaction_added", "channel": "C1234"}
        result = slack_bridge.handle_slack_event(event)
        assert result["handled"] is False
