"""
test_slack_posting.py — B-010-live-personal: Slack posting unit tests
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Tests post_to_slack() without calling the real Slack API.
All tests use mocked requests; no real SLACK_BOT_TOKEN is used or required.
"""
import os
import sys
from unittest.mock import MagicMock, patch

import pytest

# Ensure worker module is importable from this directory
sys.path.insert(0, os.path.dirname(__file__))

# Force DEMO_MODE=true so importing worker.py doesn't call LLM on import
os.environ.setdefault("DEMO_MODE", "true")

from worker import post_to_slack  # noqa: E402


# ─────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────

def _make_slack_ok_response():
    resp = MagicMock()
    resp.raise_for_status.return_value = None
    resp.json.return_value = {"ok": True, "ts": "1234567890.123456"}
    return resp


def _make_slack_error_response(error_code: str = "channel_not_found"):
    resp = MagicMock()
    resp.raise_for_status.return_value = None
    resp.json.return_value = {"ok": False, "error": error_code}
    return resp


# ─────────────────────────────────────────────────────────────
# Group A — Channel / Thread ID parsing
# ─────────────────────────────────────────────────────────────

class TestSlackThreadIdParsing:
    """Verify that post_to_slack correctly splits channel + thread_ts."""

    def test_channel_and_thread_ts_parsed(self, monkeypatch):
        """C123/1234567890.123456 → channel=C123, thread_ts=1234567890.123456"""
        monkeypatch.setenv("SLACK_BOT_TOKEN", "xoxb-test-token-fake")
        monkeypatch.setenv("REQUIRE_SLACK_POST", "false")

        captured = {}

        def fake_post(url, headers, json, timeout):
            captured["channel"] = json.get("channel")
            captured["thread_ts"] = json.get("thread_ts")
            return _make_slack_ok_response()

        with patch("worker._slack_session") as mock_session:
            mock_session.post.side_effect = fake_post
            result = post_to_slack("C123ABCDE/1714500000.123456", "hello")

        assert result is True
        assert captured["channel"] == "C123ABCDE"
        assert captured["thread_ts"] == "1714500000.123456"

    def test_channel_only_no_thread_ts(self, monkeypatch):
        """C123 → channel=C123, thread_ts NOT in payload"""
        monkeypatch.setenv("SLACK_BOT_TOKEN", "xoxb-test-token-fake")
        monkeypatch.setenv("REQUIRE_SLACK_POST", "false")

        captured = {}

        def fake_post(url, headers, json, timeout):
            captured["channel"] = json.get("channel")
            captured["thread_ts"] = json.get("thread_ts")  # should be absent
            return _make_slack_ok_response()

        with patch("worker._slack_session") as mock_session:
            mock_session.post.side_effect = fake_post
            result = post_to_slack("C123ABCDE", "hello from channel")

        assert result is True
        assert captured["channel"] == "C123ABCDE"
        assert captured["thread_ts"] is None  # key absent → dict.get returns None

    def test_channel_with_multiple_slashes_uses_first_split(self, monkeypatch):
        """C123/ts/extra → only splits on first slash; channel=C123, thread_ts=ts/extra"""
        monkeypatch.setenv("SLACK_BOT_TOKEN", "xoxb-test-token-fake")
        monkeypatch.setenv("REQUIRE_SLACK_POST", "false")

        captured = {}

        def fake_post(url, headers, json, timeout):
            captured["channel"] = json.get("channel")
            captured["thread_ts"] = json.get("thread_ts")
            return _make_slack_ok_response()

        with patch("worker._slack_session") as mock_session:
            mock_session.post.side_effect = fake_post
            result = post_to_slack("C123ABCDE/1714500000.123456/extra", "hi")

        assert result is True
        assert captured["channel"] == "C123ABCDE"
        assert captured["thread_ts"] == "1714500000.123456/extra"


# ─────────────────────────────────────────────────────────────
# Group B — Missing token behaviour
# ─────────────────────────────────────────────────────────────

class TestMissingToken:
    """Verify behaviour when SLACK_BOT_TOKEN is absent."""

    def test_missing_token_require_false_returns_false(self, monkeypatch):
        """No token + REQUIRE_SLACK_POST=false → returns False (action still succeeds)."""
        monkeypatch.delenv("SLACK_BOT_TOKEN", raising=False)
        monkeypatch.setenv("REQUIRE_SLACK_POST", "false")

        with patch("worker._slack_session") as mock_session:
            result = post_to_slack("C123ABCDE/1714500000.123456", "hi")
            mock_session.post.assert_not_called()

        assert result is False

    def test_missing_token_require_true_raises(self, monkeypatch):
        """No token + REQUIRE_SLACK_POST=true → RuntimeError (hard fail)."""
        monkeypatch.delenv("SLACK_BOT_TOKEN", raising=False)
        monkeypatch.setenv("REQUIRE_SLACK_POST", "true")

        with pytest.raises(RuntimeError, match="REQUIRE_SLACK_POST=true"):
            post_to_slack("C123ABCDE/1714500000.123456", "hi")

    def test_empty_token_string_treated_as_missing(self, monkeypatch):
        """SLACK_BOT_TOKEN='' + REQUIRE_SLACK_POST=false → returns False."""
        monkeypatch.setenv("SLACK_BOT_TOKEN", "")
        monkeypatch.setenv("REQUIRE_SLACK_POST", "false")

        with patch("worker._slack_session") as mock_session:
            result = post_to_slack("C123ABCDE", "hi")
            mock_session.post.assert_not_called()

        assert result is False


# ─────────────────────────────────────────────────────────────
# Group C — Slack API error handling
# ─────────────────────────────────────────────────────────────

class TestSlackApiErrors:
    """Verify that Slack API errors are handled safely without leaking tokens."""

    def test_slack_api_error_returns_false(self, monkeypatch):
        """Slack API returns ok=false → post_to_slack returns False (no exception)."""
        monkeypatch.setenv("SLACK_BOT_TOKEN", "xoxb-test-token-fake")
        monkeypatch.setenv("REQUIRE_SLACK_POST", "false")

        with patch("worker._slack_session") as mock_session:
            mock_session.post.return_value = _make_slack_error_response("channel_not_found")
            result = post_to_slack("C_INVALID/1234567890.123456", "hi")

        assert result is False

    def test_network_exception_returns_false(self, monkeypatch):
        """Network error (requests.ConnectionError) → returns False (no re-raise)."""
        monkeypatch.setenv("SLACK_BOT_TOKEN", "xoxb-test-token-fake")
        monkeypatch.setenv("REQUIRE_SLACK_POST", "false")

        with patch("worker._slack_session") as mock_session:
            mock_session.post.side_effect = ConnectionError("network unreachable")
            result = post_to_slack("C123ABCDE/1714500000.123456", "hi")

        assert result is False

    def test_token_not_in_logged_error(self, monkeypatch, caplog):
        """Slack API error log must NOT contain the real token value."""
        real_token = "xoxb-999888777-realtoken"
        monkeypatch.setenv("SLACK_BOT_TOKEN", real_token)
        monkeypatch.setenv("REQUIRE_SLACK_POST", "false")

        with patch("worker._slack_session") as mock_session:
            mock_session.post.return_value = _make_slack_error_response("invalid_auth")
            import logging
            with caplog.at_level(logging.WARNING, logger="worker"):
                post_to_slack("C123ABCDE/1714500000.123456", "hi")

        # Token must NOT appear in any log record
        for record in caplog.records:
            assert real_token not in record.getMessage(), (
                f"Token leaked in log: {record.getMessage()}"
            )

    def test_slack_ok_true_returns_true(self, monkeypatch):
        """Successful Slack post → returns True."""
        monkeypatch.setenv("SLACK_BOT_TOKEN", "xoxb-test-token-fake")
        monkeypatch.setenv("REQUIRE_SLACK_POST", "false")

        with patch("worker._slack_session") as mock_session:
            mock_session.post.return_value = _make_slack_ok_response()
            result = post_to_slack("C123ABCDE/1714500000.123456", "hello from live smoke")

        assert result is True
