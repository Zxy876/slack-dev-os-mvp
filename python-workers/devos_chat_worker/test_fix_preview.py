"""
test_fix_preview.py
~~~~~~~~~~~~~~~~~~~
B-020: 针对 fix preview executor 的单元测试。

测试清单：
  A. test_fix_preview_demo_returns_fix_plan_only    — DEMO_MODE → [FIX_PLAN_ONLY] in response
  B. test_fix_preview_reads_file_safely             — file content accessible in DEMO response
  C. test_fix_preview_failure_context_embedded      — failure_context fields appear in notepad
  D. test_fix_preview_does_not_mutate_original_file — original file unchanged after execute
  E. test_unsafe_absolute_file_path_rejected        — absolute file_path → FAILED
  F. test_dotdot_file_path_rejected                 — ../traversal file_path → FAILED
  G. test_missing_repo_path_returns_failed          — empty repo_path → FAILED
  H. test_missing_file_path_returns_failed          — empty file_path → FAILED
  I. test_nonexistent_file_returns_failed           — file does not exist → FAILED
  J. test_fix_preview_slack_post_called             — slack_thread_id passed → post_to_slack called
  K. test_fix_preview_has_patch_false               — fixPreview.hasPatch always False
  L. test_fix_preview_routing_from_execute          — mode=fix_preview routes to execute_fix_preview
"""
from __future__ import annotations

import os
import sys
import tempfile
from unittest.mock import patch, MagicMock

import pytest

# ─────────────────────────────────────────────────────────────
# Set DEMO_MODE before importing worker
# ─────────────────────────────────────────────────────────────
os.environ.setdefault("DEMO_MODE", "true")
os.environ.setdefault("ASYNCAIFLOW_URL", "http://localhost:8080")

from worker import (  # noqa: E402
    execute_fix_preview,
    execute,
    TOOL_MANAGER,
    ToolCall,
    DEMO_MODE,
    is_demo_mode,
)


# ─────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────

def _make_repo(content: str = "def foo():\n    return 1\n") -> tuple[str, str]:
    """Create a temp dir with a single Python file; return (repo_path, file_path)."""
    repo_dir = tempfile.mkdtemp(prefix="devos-fix-test-")
    fname = "app.py"
    with open(os.path.join(repo_dir, fname), "w") as fh:
        fh.write(content)
    return repo_dir, fname


def _basic_fc(
    test_status: str = "FAILED",
    exit_code: int = 1,
    stdout: str = "build failed",
    stderr: str = "AssertionError",
    hint: str = "",
) -> dict:
    return {
        "test_status": test_status,
        "exit_code": exit_code,
        "stdout_excerpt": stdout,
        "stderr_excerpt": stderr,
        "hint": hint,
    }


def _basic_payload(repo_path: str, file_path: str, **fc_overrides) -> dict:
    return {
        "repo_path": repo_path,
        "file_path": file_path,
        "failure_context": _basic_fc(**fc_overrides),
    }


# ─────────────────────────────────────────────────────────────
# A. DEMO_MODE returns [FIX_PLAN_ONLY]
# ─────────────────────────────────────────────────────────────

def test_fix_preview_demo_returns_fix_plan_only():
    repo, fpath = _make_repo()
    try:
        assert is_demo_mode(), "Must run with DEMO_MODE=true"
        status, result, err = execute_fix_preview(1, _basic_payload(repo, fpath), None)

        assert status == "SUCCEEDED", f"Expected SUCCEEDED, got {status!r}: {err}"
        assert err is None
        assert "response" in result
        assert "[FIX_PLAN_ONLY]" in result["response"], (
            "DEMO_MODE response must contain [FIX_PLAN_ONLY]"
        )
        assert "notepad" in result
        assert "fixPreview" in result
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# B. File content is safely readable
# ─────────────────────────────────────────────────────────────

def test_fix_preview_reads_file_safely():
    """The response / notepad references the target file without error."""
    repo, fpath = _make_repo("def bar():\n    raise ValueError('oops')\n")
    try:
        status, result, err = execute_fix_preview(2, _basic_payload(repo, fpath), None)

        assert status == "SUCCEEDED", f"Expected SUCCEEDED: {err}"
        # The notepad should reference the file name
        assert fpath in result["notepad"], "notepad must mention target file"
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# C. failure_context fields embedded in notepad
# ─────────────────────────────────────────────────────────────

def test_fix_preview_failure_context_embedded():
    repo, fpath = _make_repo()
    try:
        payload = _basic_payload(
            repo, fpath,
            test_status="FAILED",
            exit_code=42,
            stdout="test output here",
            stderr="FATAL: assertion failed",
            hint="check null",
        )
        status, result, err = execute_fix_preview(3, payload, None)

        assert status == "SUCCEEDED", f"Unexpected: {err}"
        notepad = result["notepad"]
        # notepad must contain test_status and exit_code
        assert "FAILED" in notepad or "test_status" in notepad, (
            "notepad must reference test_status"
        )
        assert "42" in notepad or "exit_code" in notepad, (
            "notepad must reference exit_code"
        )
        # fixPreview metadata
        fp = result["fixPreview"]
        assert fp["testStatus"] == "FAILED"
        assert fp["exitCode"] == 42
        assert fp["filePath"] == fpath
        assert fp["repoPath"] == repo
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# D. Original file must NOT be mutated
# ─────────────────────────────────────────────────────────────

def test_fix_preview_does_not_mutate_original_file():
    original_content = "def immutable():\n    pass\n"
    repo, fpath = _make_repo(original_content)
    target = os.path.join(repo, fpath)
    try:
        execute_fix_preview(4, _basic_payload(repo, fpath), None)

        with open(target) as f:
            after = f.read()
        assert after == original_content, (
            "execute_fix_preview MUST NOT modify the original file"
        )
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# E. Absolute file_path rejected
# ─────────────────────────────────────────────────────────────

def test_unsafe_absolute_file_path_rejected():
    repo, _ = _make_repo()
    try:
        abs_path = os.path.join(repo, "app.py")  # absolute path as file_path
        payload = {
            "repo_path": repo,
            "file_path": abs_path,  # must be rejected
            "failure_context": _basic_fc(),
        }
        status, result, err = execute_fix_preview(5, payload, None)
        assert status == "FAILED", (
            f"Absolute file_path must return FAILED, got {status!r}"
        )
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# F. ../dotdot traversal rejected
# ─────────────────────────────────────────────────────────────

def test_dotdot_file_path_rejected():
    repo, _ = _make_repo()
    try:
        payload = {
            "repo_path": repo,
            "file_path": "../../etc/passwd",
            "failure_context": _basic_fc(),
        }
        status, result, err = execute_fix_preview(6, payload, None)
        assert status == "FAILED", (
            f"Path traversal must return FAILED, got {status!r}"
        )
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# G. Missing repo_path → FAILED
# ─────────────────────────────────────────────────────────────

def test_missing_repo_path_returns_failed():
    payload = {
        "repo_path": "",
        "file_path": "README.md",
        "failure_context": _basic_fc(),
    }
    status, _, err = execute_fix_preview(7, payload, None)
    assert status == "FAILED"
    assert err is not None


# ─────────────────────────────────────────────────────────────
# H. Missing file_path → FAILED
# ─────────────────────────────────────────────────────────────

def test_missing_file_path_returns_failed():
    repo, _ = _make_repo()
    try:
        payload = {
            "repo_path": repo,
            "file_path": "",
            "failure_context": _basic_fc(),
        }
        status, _, err = execute_fix_preview(8, payload, None)
        assert status == "FAILED"
        assert err is not None
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# I. Nonexistent file → FAILED
# ─────────────────────────────────────────────────────────────

def test_nonexistent_file_returns_failed():
    repo, _ = _make_repo()
    try:
        payload = {
            "repo_path": repo,
            "file_path": "nonexistent_file.py",
            "failure_context": _basic_fc(),
        }
        status, _, err = execute_fix_preview(9, payload, None)
        assert status == "FAILED", (
            f"Nonexistent file should return FAILED, got {status!r}: {err}"
        )
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# J. Slack post called when slack_thread_id provided
# ─────────────────────────────────────────────────────────────

def test_fix_preview_slack_post_called():
    repo, fpath = _make_repo()
    try:
        with patch("worker.post_to_slack") as mock_post:
            execute_fix_preview(10, _basic_payload(repo, fpath), "C-TEST/123.456")
            mock_post.assert_called_once()
            args = mock_post.call_args[0]
            assert args[0] == "C-TEST/123.456", "Must post to correct thread"
            assert "[FIX_PLAN_ONLY]" in args[1], "Slack message must contain [FIX_PLAN_ONLY]"
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# J2. No Slack post when slack_thread_id is None
# ─────────────────────────────────────────────────────────────

def test_fix_preview_no_slack_when_thread_id_none():
    repo, fpath = _make_repo()
    try:
        with patch("worker.post_to_slack") as mock_post:
            execute_fix_preview(11, _basic_payload(repo, fpath), None)
            mock_post.assert_not_called()
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# K. fixPreview.hasPatch always False
# ─────────────────────────────────────────────────────────────

def test_fix_preview_has_patch_false():
    repo, fpath = _make_repo()
    try:
        status, result, err = execute_fix_preview(12, _basic_payload(repo, fpath), None)
        assert status == "SUCCEEDED", f"Unexpected: {err}"
        assert result["fixPreview"]["hasPatch"] is False, (
            "v1 fix-preview must always set hasPatch=False"
        )
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)


# ─────────────────────────────────────────────────────────────
# L. mode=fix_preview routes to execute_fix_preview via execute()
# ─────────────────────────────────────────────────────────────

def test_fix_preview_routing_from_execute(monkeypatch):
    """Verify execute() routes mode=fix_preview to execute_fix_preview."""
    called_with: list[dict] = []

    def fake_fix_preview(action_id, payload, thread_id):
        called_with.append({
            "action_id": action_id,
            "payload": payload,
            "thread_id": thread_id,
        })
        return "SUCCEEDED", {"response": "[FIX_PLAN_ONLY] stub", "notepad": "", "fixPreview": {}}, None

    monkeypatch.setattr("worker.execute_fix_preview", fake_fix_preview)

    repo, fpath = _make_repo()
    try:
        action_payload = {
            "user_text": "fix test",
            "slack_thread_id": "C-TEST/1.1",
            "mode": "fix_preview",
            "repo_path": repo,
            "file_path": fpath,
            "failure_context": _basic_fc(),
        }
        import json
        assignment = {
            "actionId": 99,
            "slackThreadId": "C-TEST/1.1",
            "notepadRef": "",
            "payload": json.dumps(action_payload),
        }
        status, result, err = execute(assignment)
        assert len(called_with) == 1, (
            "execute() must delegate to execute_fix_preview exactly once"
        )
        assert called_with[0]["action_id"] == 99
    finally:
        import shutil; shutil.rmtree(repo, ignore_errors=True)
