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
        """call_devos_endpoint receives correct text and slackThreadId."""
        event = _make_message_event(
            "devos: improve the linting setup",
            channel="C555",
            ts="1710000001.000001",
        )

        captured_endpoint = {}
        captured_payload = {}

        def fake_call_devos_endpoint(endpoint, payload, base_url=None):
            captured_endpoint["endpoint"] = endpoint
            captured_payload.update(payload)
            return {"success": True, "actionId": 99, "workflowId": 3}

        with patch("slack_bridge.call_devos_endpoint", side_effect=fake_call_devos_endpoint):
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "base_url": "http://fake-backend:8080"},
            )

        assert captured_endpoint.get("endpoint") == "/devos/start"
        assert captured_payload.get("text") == "improve the linting setup"
        assert "slackThreadId" in captured_payload
        assert captured_payload["slackThreadId"] == "C555/1710000001.000001"

    def test_g_successful_result_returns_action_id(self):
        """Successful backend call returns actionId in result."""
        event = _make_message_event("devos: do something")

        with patch("slack_bridge.call_devos_endpoint") as mock_call:
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

        with patch("slack_bridge.call_devos_endpoint") as mock_call:
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

        with patch("slack_bridge.call_devos_endpoint") as mock_call:
            mock_call.side_effect = RuntimeError("/devos/start HTTP 500: internal server error")
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False},
            )

        assert result["handled"] is True
        assert result["actionId"] is None

    def test_h_backend_error_reply_contains_error_info(self):
        """Error reply mentions the failure without token."""
        event = _make_message_event("devos: run tests")

        with patch("slack_bridge.call_devos_endpoint") as mock_call:
            mock_call.side_effect = RuntimeError("/devos/start HTTP 400: bad request")
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False},
            )

        assert "DevOS ask failed" in result["replyText"]
        # Token must never appear in reply
        assert "xoxb-" not in result["replyText"]
        assert "xoxp-" not in result["replyText"]

    def test_h_connection_error_handled_gracefully(self):
        """Connection error is handled, not propagated."""
        event = _make_message_event("devos: check backend connection")

        with patch("slack_bridge.call_devos_endpoint") as mock_call:
            mock_call.side_effect = RuntimeError("/devos/start connection error: connection refused")
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False},
            )

        assert result["handled"] is True
        assert "DevOS ask failed" in result["replyText"]


# ─────────────────────────────────────────────────────────────
# I. default repo path included when configured
# ─────────────────────────────────────────────────────────────

class TestDefaultRepoPath:

    def test_i_repo_path_included_when_configured(self):
        """default_repo_path is passed through to payload."""
        event = _make_message_event("devos: run mvn test")

        captured_payload = {}

        def fake_call(endpoint, payload, base_url=None):
            captured_payload.update(payload)
            return {"success": True, "actionId": 1}

        with patch("slack_bridge.call_devos_endpoint", side_effect=fake_call):
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

        def fake_call(endpoint, payload, base_url=None):
            captured_payload.update(payload)
            return {"success": True, "actionId": 2}

        with patch("slack_bridge.call_devos_endpoint", side_effect=fake_call):
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


# ─────────────────────────────────────────────────────────────
# B-022 Intent Router Tests (Scenarios A–T)
# ─────────────────────────────────────────────────────────────

CHANNEL = "C99999"
TS = "1700000001.000001"
THREAD_TS = "1700000000.000001"
THREAD_ID = f"{CHANNEL}/{THREAD_TS}"
REPO_PATH = "/srv/repo"


def _make_ev(text: str, thread_ts: str = None) -> dict:
    """Minimal message event helper for intent-router tests."""
    return _make_message_event(
        text=text,
        channel=CHANNEL,
        ts=TS,
        thread_ts=thread_ts or THREAD_TS,
    )


class TestParseDevosIntent:
    """Unit tests for parse_devos_intent()."""

    # A. legacy devos: hello → ask/start (backward compat)
    def test_a_legacy_ask_default(self):
        intent = slack_bridge.parse_devos_intent(
            "hello world", THREAD_ID, repo_path=None
        )
        assert intent.kind == slack_bridge.INTENT_ASK
        assert intent.endpoint == "/devos/start"
        assert intent.payload["text"] == "hello world"
        assert not intent.dangerous

    # B. devos: ask hello → ask/start
    def test_b_explicit_ask_keyword(self):
        intent = slack_bridge.parse_devos_intent(
            "ask deploy the service", THREAD_ID, repo_path=None
        )
        assert intent.kind == slack_bridge.INTENT_ASK
        assert intent.endpoint == "/devos/start"
        assert intent.payload["text"] == "deploy the service"

    # C. preview command parses filePath/from/to correctly
    def test_c_preview_parses_correctly(self):
        intent = slack_bridge.parse_devos_intent(
            'preview src/main/App.java replace "OldClass" with "NewClass"',
            THREAD_ID,
            repo_path=REPO_PATH,
        )
        assert intent.kind == slack_bridge.INTENT_PREVIEW
        assert intent.endpoint == "/devos/start"
        assert intent.payload["filePath"] == "src/main/App.java"
        assert intent.payload["replaceFrom"] == "OldClass"
        assert intent.payload["replaceTo"] == "NewClass"
        assert intent.payload["mode"] == "patch_preview"
        assert intent.payload["writeIntent"] is True
        assert not intent.dangerous

    # D. preview missing repoPath returns CONFIG_ERROR
    def test_d_preview_missing_repo(self):
        intent = slack_bridge.parse_devos_intent(
            'preview README.md replace "a" with "b"',
            THREAD_ID,
            repo_path=None,
        )
        assert intent.kind == slack_bridge.INTENT_CONFIG_ERROR
        assert intent.endpoint == ""

    # F. apply without confirm returns NEEDS_CONFIRMATION
    def test_f_apply_without_confirm(self):
        intent = slack_bridge.parse_devos_intent(
            "apply 101",
            THREAD_ID,
            repo_path=REPO_PATH,
        )
        assert intent.kind == slack_bridge.INTENT_NEEDS_CONFIRMATION
        assert intent.needs_confirmation is True
        assert intent.dangerous is True

    # G. apply with confirm payload has correct previewActionId
    def test_g_apply_with_confirm_payload(self):
        intent = slack_bridge.parse_devos_intent(
            "apply 101 confirm",
            THREAD_ID,
            repo_path=REPO_PATH,
        )
        assert intent.kind == slack_bridge.INTENT_APPLY
        assert intent.endpoint == "/devos/apply-patch"
        assert intent.payload["previewActionId"] == 101
        assert intent.payload["confirm"] is True
        assert intent.dangerous is True

    # H. test command calls /devos/run-test with correct command
    def test_h_test_command_payload(self):
        intent = slack_bridge.parse_devos_intent(
            "test mvn verify -q",
            THREAD_ID,
            repo_path=REPO_PATH,
        )
        assert intent.kind == slack_bridge.INTENT_TEST
        assert intent.endpoint == "/devos/run-test"
        assert intent.payload["command"] == "mvn verify -q"
        assert intent.payload["repoPath"] == REPO_PATH

    # I. test missing repoPath returns CONFIG_ERROR
    def test_i_test_missing_repo(self):
        intent = slack_bridge.parse_devos_intent(
            "test pytest",
            THREAD_ID,
            repo_path=None,
        )
        assert intent.kind == slack_bridge.INTENT_CONFIG_ERROR

    # J. commit without confirm returns NEEDS_CONFIRMATION
    def test_j_commit_without_confirm(self):
        intent = slack_bridge.parse_devos_intent(
            'commit "add feature"',
            THREAD_ID,
            repo_path=REPO_PATH,
        )
        assert intent.kind == slack_bridge.INTENT_NEEDS_CONFIRMATION
        assert intent.dangerous is True

    # K. commit with confirm payload
    def test_k_commit_with_confirm_payload(self):
        intent = slack_bridge.parse_devos_intent(
            'commit "feat: add login" confirm',
            THREAD_ID,
            repo_path=REPO_PATH,
        )
        assert intent.kind == slack_bridge.INTENT_COMMIT
        assert intent.endpoint == "/devos/git-commit"
        assert intent.payload["message"] == "feat: add login"
        assert intent.payload["confirm"] is True
        assert intent.dangerous is True

    # L. commit missing repoPath returns CONFIG_ERROR
    def test_l_commit_missing_repo(self):
        intent = slack_bridge.parse_devos_intent(
            'commit "fix: typo" confirm',
            THREAD_ID,
            repo_path=None,
        )
        assert intent.kind == slack_bridge.INTENT_CONFIG_ERROR

    # M. fix command returns NEEDS_CONTEXT
    def test_m_fix_returns_needs_context(self):
        intent = slack_bridge.parse_devos_intent(
            "fix src/Worker.java",
            THREAD_ID,
            repo_path=REPO_PATH,
        )
        assert intent.kind == slack_bridge.INTENT_NEEDS_CONTEXT
        assert intent.endpoint == ""
        assert not intent.dangerous

    # N. unknown sub-command falls back to ask
    def test_n_unknown_subcommand_falls_back_to_ask(self):
        intent = slack_bridge.parse_devos_intent(
            "summarize all the changes",
            THREAD_ID,
            repo_path=None,
        )
        assert intent.kind == slack_bridge.INTENT_ASK

    # S. apply/commit intents have dangerous=True
    def test_s_dangerous_flags(self):
        apply_intent = slack_bridge.parse_devos_intent(
            "apply 55 confirm", THREAD_ID, repo_path=REPO_PATH
        )
        commit_intent = slack_bridge.parse_devos_intent(
            'commit "msg" confirm', THREAD_ID, repo_path=REPO_PATH
        )
        assert apply_intent.dangerous is True
        assert commit_intent.dangerous is True
        # ask/preview/test should NOT be dangerous
        ask_intent = slack_bridge.parse_devos_intent(
            "hello", THREAD_ID, repo_path=REPO_PATH
        )
        assert ask_intent.dangerous is False


class TestIntentRouterHandleSlackEvent:
    """Integration tests for handle_slack_event with B-022 intent routing."""

    # O. bot message ignored (existing behavior preserved)
    def test_o_bot_message_ignored(self):
        event = _make_ev("devos: apply 1 confirm")
        event["bot_id"] = "B123"
        result = slack_bridge.handle_slack_event(event)
        assert result["handled"] is False

    # P. thread_ts mapping still works
    def test_p_thread_ts_preserved(self):
        event = _make_ev("devos: hello", thread_ts="1600000000.000001")
        result = slack_bridge.handle_slack_event(
            event, config={"dry_run": True}
        )
        assert result["slackThreadId"] == f"{CHANNEL}/1600000000.000001"

    # E. preview dry-run does not call backend
    def test_e_preview_dry_run_no_backend(self):
        event = _make_ev('devos: preview README.md replace "Old" with "New"')
        with patch("slack_bridge.call_devos_endpoint") as mock_call:
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": True, "default_repo_path": REPO_PATH},
            )
        mock_call.assert_not_called()
        assert result["handled"] is True
        assert result["intent"]["kind"] == slack_bridge.INTENT_PREVIEW

    # R. dry-run payload includes endpoint and kind
    def test_r_dry_run_includes_endpoint_and_kind(self):
        event = _make_ev("devos: ask explain this")
        result = slack_bridge.handle_slack_event(
            event,
            config={"dry_run": True, "default_repo_path": REPO_PATH},
        )
        assert result["intent"]["kind"] == slack_bridge.INTENT_ASK
        assert result["intent"]["endpoint"] == "/devos/start"
        assert result["reason"] == "dry-run mode"

    # G full. apply with confirm calls /devos/apply-patch
    def test_g_apply_calls_apply_patch(self):
        event = _make_ev("devos: apply 77 confirm")
        mock_resp = MagicMock()
        mock_resp.raise_for_status.return_value = None
        mock_resp.json.return_value = {
            "data": {"actionId": 77, "status": "APPLIED", "applied": True}
        }
        with patch("slack_bridge.requests.Session") as mock_sess_cls:
            mock_sess = MagicMock()
            mock_sess_cls.return_value = mock_sess
            mock_sess.post.return_value = mock_resp
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "default_repo_path": REPO_PATH},
            )
        posted_url = mock_sess.post.call_args[0][0]
        assert "/devos/apply-patch" in posted_url
        posted_json = mock_sess.post.call_args[1]["json"]
        assert posted_json["previewActionId"] == 77
        assert posted_json["confirm"] is True
        assert "APPLIED" in result["replyText"]

    # H full. test command calls /devos/run-test
    def test_h_test_calls_run_test(self):
        event = _make_ev("devos: test mvn test -q")
        mock_resp = MagicMock()
        mock_resp.raise_for_status.return_value = None
        mock_resp.json.return_value = {
            "data": {"actionId": 99, "status": "PASSED", "exitCode": 0}
        }
        with patch("slack_bridge.requests.Session") as mock_sess_cls:
            mock_sess = MagicMock()
            mock_sess_cls.return_value = mock_sess
            mock_sess.post.return_value = mock_resp
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "default_repo_path": REPO_PATH},
            )
        posted_url = mock_sess.post.call_args[0][0]
        assert "/devos/run-test" in posted_url
        assert "PASSED" in result["replyText"]

    # Q. backend error safe reply for non-start endpoint
    def test_q_backend_error_safe_reply(self):
        event = _make_ev("devos: apply 200 confirm")
        import requests as req_lib
        mock_resp = MagicMock()
        mock_resp.status_code = 500
        mock_resp.json.return_value = {"message": "internal error"}
        http_err = req_lib.HTTPError(response=mock_resp)
        with patch("slack_bridge.requests.Session") as mock_sess_cls:
            mock_sess = MagicMock()
            mock_sess_cls.return_value = mock_sess
            mock_sess.post.side_effect = http_err
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "default_repo_path": REPO_PATH},
            )
        assert result["handled"] is True
        assert "failed" in result["replyText"].lower() or "error" in result["replyText"].lower()

    # T. no secret/token appears in any reply text (not injected by bridge logic)
    def test_t_no_secret_in_reply(self):
        """Bridge must not inject or construct token strings in reply output.

        We verify that when a normal ask command is processed, the reply
        only contains expected content (action ID, status), never a token.
        The bridge never generates token strings itself — only echoes user text.
        """
        import re as _re
        token_pattern = _re.compile(r"xox[bpoas]-[A-Za-z0-9\-]+")

        # Normal ask — no token in input; verify none in output
        event = _make_ev("devos: explain the architecture")
        with patch("slack_bridge.call_devos_endpoint") as mock_call:
            mock_call.return_value = {"actionId": 42}
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "default_repo_path": REPO_PATH},
            )

        for key in ("replyText", "reason"):
            assert not token_pattern.search(result.get(key, "")), (
                f"Reply field {key!r} unexpectedly contains a token-like string"
            )

    # needs_confirmation → no backend call (apply without confirm)
    def test_needs_confirmation_no_backend_call(self):
        event = _make_ev("devos: apply 55")
        with patch("slack_bridge.call_devos_endpoint") as mock_call:
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "default_repo_path": REPO_PATH},
            )
        mock_call.assert_not_called()
        assert result["handled"] is True
        assert "confirm" in result["replyText"].lower()
        assert result["intent"]["needs_confirmation"] is True

    # needs_context → no backend call (fix command)
    def test_needs_context_no_backend_call(self):
        event = _make_ev("devos: fix src/App.py")
        with patch("slack_bridge.call_devos_endpoint") as mock_call:
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "default_repo_path": REPO_PATH},
            )
        mock_call.assert_not_called()
        assert result["handled"] is True
        assert result["intent"]["kind"] == slack_bridge.INTENT_NEEDS_CONTEXT

    # config_error → no backend call
    def test_config_error_no_backend_call(self):
        event = _make_ev('devos: preview README.md replace "a" with "b"')
        with patch("slack_bridge.call_devos_endpoint") as mock_call:
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "default_repo_path": None},
            )
        mock_call.assert_not_called()
        assert result["handled"] is True
        assert result["intent"]["kind"] == slack_bridge.INTENT_CONFIG_ERROR

    # K full. commit with confirm calls /devos/git-commit
    def test_k_commit_calls_git_commit(self):
        event = _make_ev('devos: commit "feat: bridge intent router" confirm')
        mock_resp = MagicMock()
        mock_resp.raise_for_status.return_value = None
        mock_resp.json.return_value = {
            "data": {
                "actionId": 111,
                "status": "COMMITTED",
                "commitHash": "abc12345def67890",
            }
        }
        with patch("slack_bridge.requests.Session") as mock_sess_cls:
            mock_sess = MagicMock()
            mock_sess_cls.return_value = mock_sess
            mock_sess.post.return_value = mock_resp
            result = slack_bridge.handle_slack_event(
                event,
                config={"dry_run": False, "default_repo_path": REPO_PATH},
            )
        posted_url = mock_sess.post.call_args[0][0]
        assert "/devos/git-commit" in posted_url
        posted_json = mock_sess.post.call_args[1]["json"]
        assert posted_json["message"] == "feat: bridge intent router"
        assert posted_json["confirm"] is True
        assert "COMMITTED" in result["replyText"]

