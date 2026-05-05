"""
test_integration_status.py
~~~~~~~~~~~~~~~~~~~~~~~~~~
验证 worker 在 normal devos_chat 路径下对 integration_status 的输出行为。
"""

from __future__ import annotations

import json
import os
import sys
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(__file__))

from worker import execute


def _assignment() -> dict:
    return {
        "actionId": 101,
        "retryCount": 0,
        "slackThreadId": "C123/1700000000.000001",
        "payload": json.dumps({"user_text": "hello from test"}, ensure_ascii=False),
    }


class TestIntegrationStatus:
    def test_connected_when_openhands_footer_present(self, monkeypatch) -> None:
        monkeypatch.setenv("OPENHANDS_URL", "http://localhost:3000")

        with patch("worker.call_llm", return_value="done\n\n_[capability_source: openhands.core | conv:abc123]_"), \
             patch("worker.post_to_slack", return_value=True) as mock_post:
            status, result, err = execute(_assignment())

        assert status == "SUCCEEDED"
        assert err is None
        assert result["integration_status"] == "connected"
        assert "integration_status: connected" in result["response"]
        sent_text = mock_post.call_args[0][1]
        assert "integration_status: connected" in sent_text

    def test_degraded_when_openhands_configured_but_not_used(self, monkeypatch) -> None:
        monkeypatch.setenv("OPENHANDS_URL", "http://localhost:3000")

        with patch("worker.call_llm", return_value="native fallback response"), \
             patch("worker.post_to_slack", return_value=True) as mock_post:
            status, result, err = execute(_assignment())

        assert status == "SUCCEEDED"
        assert err is None
        assert result["integration_status"] == "degraded"
        assert "integration_status: degraded" in result["response"]
        sent_text = mock_post.call_args[0][1]
        assert "integration_status: degraded" in sent_text

    def test_disconnected_when_no_openhands_config(self, monkeypatch) -> None:
        monkeypatch.delenv("OPENHANDS_URL", raising=False)

        with patch("worker.call_llm", return_value="native llm only"), \
             patch("worker.post_to_slack", return_value=True) as mock_post:
            status, result, err = execute(_assignment())

        assert status == "SUCCEEDED"
        assert err is None
        assert result["integration_status"] == "disconnected"
        assert "integration_status: disconnected" in result["response"]
        sent_text = mock_post.call_args[0][1]
        assert "integration_status: disconnected" in sent_text
