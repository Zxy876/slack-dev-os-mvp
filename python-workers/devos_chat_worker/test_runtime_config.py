"""
test_runtime_config.py
~~~~~~~~~~~~~~~~~~~~~~
B-010 Production Mode Config Tests.

Validates LLM backend selection, missing-key fail-fast, secret redaction,
Slack skip behavior, and validate_runtime_config() summary.

No external dependencies required (no LLM, no Redis, no Slack, no backend).
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))

import pytest

from worker import (
    is_demo_mode,
    select_llm_backend,
    validate_runtime_config,
    redact_secret,
    post_to_slack,
)


# ─────────────────────────────────────────────────────────────
# LLM Backend Selection
# ─────────────────────────────────────────────────────────────

class TestLlmBackendSelection:
    def test_demo_mode_true_returns_demo_even_with_real_keys(self, monkeypatch) -> None:
        """A: DEMO_MODE=true → backend=demo，即使 GLM/OpenAI key 都存在。"""
        monkeypatch.setenv("DEMO_MODE", "true")
        monkeypatch.setenv("GLM_API_KEY", "fake-glm-key-123")
        monkeypatch.setenv("OPENAI_API_KEY", "sk-fake-openai-key")
        assert select_llm_backend() == "demo"

    def test_demo_mode_false_glm_key_present_returns_glm(self, monkeypatch) -> None:
        """B: DEMO_MODE=false + GLM_API_KEY → backend=glm。"""
        monkeypatch.delenv("DEMO_MODE", raising=False)
        monkeypatch.setenv("GLM_API_KEY", "fake-glm-key-456")
        monkeypatch.delenv("OPENAI_API_KEY", raising=False)
        assert select_llm_backend() == "glm"

    def test_demo_mode_false_openai_key_present_returns_openai(self, monkeypatch) -> None:
        """C: DEMO_MODE=false + OPENAI_API_KEY → backend=openai。"""
        monkeypatch.delenv("DEMO_MODE", raising=False)
        monkeypatch.delenv("GLM_API_KEY", raising=False)
        monkeypatch.setenv("OPENAI_API_KEY", "sk-fake-openai-789")
        assert select_llm_backend() == "openai"

    def test_demo_mode_false_no_key_raises_runtime_error(self, monkeypatch) -> None:
        """D: DEMO_MODE=false + 无 key → 明确 RuntimeError（fail fast）。"""
        monkeypatch.delenv("DEMO_MODE", raising=False)
        monkeypatch.delenv("GLM_API_KEY", raising=False)
        monkeypatch.delenv("OPENAI_API_KEY", raising=False)
        with pytest.raises(RuntimeError, match="No LLM backend available"):
            select_llm_backend()

    def test_glm_takes_priority_over_openai(self, monkeypatch) -> None:
        """GLM_API_KEY 优先级高于 OPENAI_API_KEY。"""
        monkeypatch.delenv("DEMO_MODE", raising=False)
        monkeypatch.setenv("GLM_API_KEY", "fake-glm-priority")
        monkeypatch.setenv("OPENAI_API_KEY", "sk-fake-openai-lowpri")
        assert select_llm_backend() == "glm"


# ─────────────────────────────────────────────────────────────
# Secret Redaction
# ─────────────────────────────────────────────────────────────

class TestSecretRedaction:
    def test_redact_long_secret_hides_body(self) -> None:
        """E: redact_secret("sk-abc123xyz") 不泄漏完整 key。"""
        result = redact_secret("sk-abc123xyz")
        assert "abc123xyz" not in result
        assert "***" in result
        assert result.startswith("sk-a")

    def test_redact_empty_string_returns_not_set(self) -> None:
        assert redact_secret("") == "(not set)"

    def test_redact_short_value_returns_masked(self) -> None:
        assert redact_secret("ab") == "***"
        assert redact_secret("abcd") == "***"

    def test_redact_xoxb_token_hides_body(self) -> None:
        result = redact_secret("xoxb-123456789-abc")
        assert "123456789" not in result
        assert result.startswith("xoxb")
        assert "***" in result


# ─────────────────────────────────────────────────────────────
# Slack Post Skip Behavior
# ─────────────────────────────────────────────────────────────

class TestSlackSkipBehavior:
    def test_post_to_slack_skips_without_token_returns_false(self, monkeypatch) -> None:
        """F: SLACK_BOT_TOKEN 缺失时 post_to_slack 返回 False，不抛异常。"""
        monkeypatch.delenv("SLACK_BOT_TOKEN", raising=False)
        monkeypatch.delenv("REQUIRE_SLACK_POST", raising=False)
        result = post_to_slack("C12345/1234567890.123456", "test message")
        assert result is False

    def test_post_to_slack_require_slack_true_no_token_raises(self, monkeypatch) -> None:
        """REQUIRE_SLACK_POST=true 且无 token → RuntimeError（fail fast）。"""
        monkeypatch.delenv("SLACK_BOT_TOKEN", raising=False)
        monkeypatch.setenv("REQUIRE_SLACK_POST", "true")
        with pytest.raises(RuntimeError, match="REQUIRE_SLACK_POST"):
            post_to_slack("C12345/1234567890.123456", "test message")


# ─────────────────────────────────────────────────────────────
# validate_runtime_config() summary
# ─────────────────────────────────────────────────────────────

class TestValidateRuntimeConfig:
    def test_demo_mode_config_is_valid(self, monkeypatch) -> None:
        """DEMO_MODE=true → llm_ok=True, llm_backend=demo。"""
        monkeypatch.setenv("DEMO_MODE", "true")
        monkeypatch.delenv("GLM_API_KEY", raising=False)
        monkeypatch.delenv("OPENAI_API_KEY", raising=False)
        config = validate_runtime_config()
        assert config["llm_ok"] is True
        assert config["llm_backend"] == "demo"
        assert config["demo_mode"] is True
        assert config["llm_error"] is None

    def test_missing_key_config_reports_llm_ok_false(self, monkeypatch) -> None:
        """DEMO_MODE=false + 无 key → llm_ok=False, llm_backend=missing。"""
        monkeypatch.delenv("DEMO_MODE", raising=False)
        monkeypatch.delenv("GLM_API_KEY", raising=False)
        monkeypatch.delenv("OPENAI_API_KEY", raising=False)
        config = validate_runtime_config()
        assert config["llm_ok"] is False
        assert config["llm_backend"] == "missing"
        assert config["llm_error"] is not None
        assert "No LLM backend" in config["llm_error"]

    def test_config_redacts_secrets(self, monkeypatch) -> None:
        """validate_runtime_config() 返回的 token 字段已遮掩，不含完整 key。"""
        monkeypatch.setenv("DEMO_MODE", "true")
        monkeypatch.setenv("SLACK_BOT_TOKEN", "xoxb-abc123456789xyz")
        config = validate_runtime_config()
        assert "abc123456789xyz" not in config["slack_token_redacted"]
        assert "***" in config["slack_token_redacted"]
