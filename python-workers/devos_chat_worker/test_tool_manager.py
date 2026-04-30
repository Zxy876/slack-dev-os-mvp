"""
test_tool_manager.py
~~~~~~~~~~~~~~~~~~~~
B-008 Tool Manager smoke tests.

Validates the minimal ToolCall / ToolResponse / ToolManager protocol:
  - Valid repo.read_file tool call returns ok=True with content.
  - Unknown tool returns ok=False (no exception raised).
  - Path traversal attack is rejected with ok=False.
  - Absolute file_path is rejected with ok=False.
  - Registering a non-whitelisted tool raises ValueError.

No external dependencies required (no LLM, no Redis, no backend).
"""
from __future__ import annotations

import os
import sys

# Make worker module importable when tests are run from the repo root.
sys.path.insert(0, os.path.dirname(__file__))

import pytest

from worker import ToolCall, ToolManager, ToolResponse, TOOL_MANAGER


# ─────────────────────────────────────────────────────────────
# ToolManager whitelist enforcement
# ─────────────────────────────────────────────────────────────

class TestToolManagerWhitelist:
    def test_register_non_whitelisted_tool_raises_value_error(self) -> None:
        tm = ToolManager()
        with pytest.raises(ValueError, match="not in the whitelist"):
            tm.register("shell.exec", lambda args: None)

    def test_execute_unknown_tool_returns_ok_false(self) -> None:
        response = TOOL_MANAGER.execute(
            ToolCall(name="arbitrary.command", args={"cmd": "whoami"})
        )
        assert response.ok is False
        assert "Unknown tool" in response.error
        assert response.name == "arbitrary.command"

    def test_execute_unknown_tool_does_not_raise(self) -> None:
        # Must never raise — always return a ToolResponse.
        response = TOOL_MANAGER.execute(ToolCall(name="not.a.tool", args={}))
        assert isinstance(response, ToolResponse)
        assert response.ok is False


# ─────────────────────────────────────────────────────────────
# repo.read_file — happy path
# ─────────────────────────────────────────────────────────────

class TestRepoReadFileTool:
    def test_valid_file_returns_ok_true_with_content(self, tmp_path) -> None:
        test_file = tmp_path / "hello.md"
        test_file.write_text("# Hello\nThis is test content for B-008.")

        response = TOOL_MANAGER.execute(
            ToolCall(
                name="repo.read_file",
                args={"repo_path": str(tmp_path), "file_path": "hello.md"},
            )
        )

        assert response.ok is True
        assert "Hello" in response.content
        assert "B-008" in response.content
        assert response.name == "repo.read_file"
        assert response.error == ""

    # ─── security boundary tests ─────────────────────────────

    def test_path_traversal_returns_ok_false(self, tmp_path) -> None:
        response = TOOL_MANAGER.execute(
            ToolCall(
                name="repo.read_file",
                args={"repo_path": str(tmp_path), "file_path": "../etc/passwd"},
            )
        )
        assert response.ok is False
        assert response.error != ""

    def test_absolute_file_path_returns_ok_false(self, tmp_path) -> None:
        response = TOOL_MANAGER.execute(
            ToolCall(
                name="repo.read_file",
                args={"repo_path": str(tmp_path), "file_path": "/etc/passwd"},
            )
        )
        assert response.ok is False
        assert response.error != ""

    def test_nonexistent_file_returns_ok_false(self, tmp_path) -> None:
        response = TOOL_MANAGER.execute(
            ToolCall(
                name="repo.read_file",
                args={"repo_path": str(tmp_path), "file_path": "does_not_exist.txt"},
            )
        )
        assert response.ok is False
        assert response.error != ""
