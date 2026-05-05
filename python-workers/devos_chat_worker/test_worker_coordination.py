from __future__ import annotations

import json
import os
import shutil
import tempfile
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("DEMO_MODE", "true")
os.environ.setdefault("ASYNCAIFLOW_URL", "http://localhost:8080")

from worker import (  # noqa: E402
    acquire_workspace_lock,
    build_artifact_summary,
    execute,
    poll_and_notify_handoffs,
    release_workspace_lock,
)


def _make_repo() -> str:
    repo_dir = tempfile.mkdtemp(prefix="devos-lock-test-")
    Path(repo_dir, "app.py").write_text("print('hello')\n", encoding="utf-8")
    return repo_dir


def _assignment(repo_path: str, **payload_overrides) -> dict:
    payload = {
        "user_text": "please inspect app.py",
        "repo_path": repo_path,
        "file_path": "app.py",
    }
    payload.update(payload_overrides)
    return {
        "actionId": 501,
        "retryCount": 0,
        "slackThreadId": "C-LOCK/1.0",
        "notepadRef": None,
        "payload": json.dumps(payload),
    }


def test_execute_returns_failed_when_workspace_is_busy(monkeypatch):
    repo = _make_repo()
    lock_root = tempfile.mkdtemp(prefix="devos-lock-root-")
    try:
        monkeypatch.setenv("DEVOS_WORKSPACE_LOCK_ROOT", lock_root)
        monkeypatch.setenv("DEVOS_WORKSPACE_LOCK_TIMEOUT_S", "0.05")
        lock = acquire_workspace_lock(repo, "external-owner", timeout_s=0.01)
        try:
            with patch("worker.post_to_slack") as mock_post:
                status, result, err = execute(_assignment(repo))
            assert status == "FAILED"
            assert "workspace busy" in (err or "")
            assert "Workspace busy" in result["response"]
            mock_post.assert_called_once()
        finally:
            release_workspace_lock(lock)
    finally:
        shutil.rmtree(repo, ignore_errors=True)
        shutil.rmtree(lock_root, ignore_errors=True)


def test_execute_releases_workspace_lock_after_success(monkeypatch):
    repo = _make_repo()
    lock_root = tempfile.mkdtemp(prefix="devos-lock-root-")
    try:
        monkeypatch.setenv("DEVOS_WORKSPACE_LOCK_ROOT", lock_root)
        with patch("worker.post_to_slack"), patch("worker.call_llm", return_value="done"):
            status, result, err = execute(_assignment(repo))
        assert status == "SUCCEEDED"
        assert err is None
        lock = acquire_workspace_lock(repo, "after-success", timeout_s=0.01)
        release_workspace_lock(lock)
    finally:
        shutil.rmtree(repo, ignore_errors=True)
        shutil.rmtree(lock_root, ignore_errors=True)


def test_build_artifact_summary_formats_preview_and_logs():
    summary, normalized = build_artifact_summary({
        "preview_url": "https://preview.example/app",
        "screenshots": ["https://cdn.example/screen-1.png"],
        "build_artifacts": [
            {"name": "bundle.js", "url": "https://cdn.example/bundle.js"},
            "https://cdn.example/report.html",
        ],
        "test_report": "https://ci.example/report/123",
        "log_summary": "vite build passed with 2 warnings",
    })
    assert "Preview" in summary
    assert "Screenshot" in summary
    assert "Build" in summary
    assert "Test report" in summary
    assert normalized["previewUrl"] == "https://preview.example/app"
    assert normalized["logSummary"].startswith("vite build passed")


def test_execute_appends_artifacts_to_response(monkeypatch):
    repo = _make_repo()
    lock_root = tempfile.mkdtemp(prefix="devos-lock-root-")
    try:
        monkeypatch.setenv("DEVOS_WORKSPACE_LOCK_ROOT", lock_root)
        assignment = _assignment(
            repo,
            preview_url="https://preview.example/app",
            screenshots=["https://cdn.example/screen-1.png"],
            build_artifacts=["https://cdn.example/build.zip"],
            test_report="https://ci.example/report/123",
            log_summary="tests passed",
        )
        with patch("worker.post_to_slack"), patch("worker.call_llm", return_value="implemented"):
            status, result, err = execute(assignment)
        assert status == "SUCCEEDED"
        assert err is None
        assert "*Artifacts*" in result["response"]
        assert result["artifacts"]["previewUrl"] == "https://preview.example/app"
    finally:
        shutil.rmtree(repo, ignore_errors=True)
        shutil.rmtree(lock_root, ignore_errors=True)


def test_poll_and_notify_handoffs_posts_ack(monkeypatch):
    handoff_dir = tempfile.mkdtemp(prefix="devos-handoff-test-")
    try:
        monkeypatch.setenv("AGENT_NAME", "agent-beta")
        monkeypatch.setenv("AGENT_MODE", "executor")
        monkeypatch.setenv("HANDOFF_DIR", handoff_dir)
        monkeypatch.setenv("SLACK_CHANNEL_ID", "C-HANDOFF")
        Path(handoff_dir, "agent-beta_1.json").write_text(
            json.dumps({
                "from_agent": "agent-alpha",
                "to_agent": "agent-beta",
                "context": "run regression tests",
                "conv_id": "conv-1",
                "notepad": "focus on flaky suite",
                "timestamp": 1.0,
            }),
            encoding="utf-8",
        )

        with patch("worker.post_to_slack") as mock_post:
            notifications = poll_and_notify_handoffs()
        assert len(notifications) == 1
        mock_post.assert_called_once()
        assert "工作交接" in notifications[0]["text"]
    finally:
        shutil.rmtree(handoff_dir, ignore_errors=True)
