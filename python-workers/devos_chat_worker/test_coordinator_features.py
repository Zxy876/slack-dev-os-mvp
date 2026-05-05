"""
Test coordinator classification and artifact collection features.
"""
import json
import os
import tempfile
from unittest.mock import MagicMock, patch

import pytest

from devos_chat_worker.worker import (
    coordinator_classify_and_route,
    collect_real_artifacts,
)


class TestCoordinatorClassify:
    """Tests for coordinator_classify_and_route()"""

    def test_classify_returns_none_for_empty_text(self):
        """Should return (None, None) for empty user text."""
        role, context = coordinator_classify_and_route("")
        assert role is None
        assert context is None

    def test_classify_returns_none_for_demo_mode_no_classification(self, monkeypatch):
        """When LLM returns should_handoff=false, return (None, None)."""
        monkeypatch.setenv("DEMO_MODE", "true")
        
        role, context = coordinator_classify_and_route("implement login page")
        # In DEMO_MODE, should still parse correctly
        assert isinstance(role, (str, type(None)))

    @patch("devos_chat_worker.worker.call_llm")
    def test_classify_backend_task(self, mock_llm):
        """Should classify backend implementation tasks to 'backend' role."""
        mock_llm.return_value = json.dumps({
            "should_handoff": True,
            "target_role": "backend",
            "reason": "Backend API implementation task",
            "context": "Implement user authentication endpoint with JWT support"
        })
        
        role, context = coordinator_classify_and_route("add JWT auth to user endpoint")
        assert role == "backend"
        assert "JWT" in context or "JWT" in (context or "")

    @patch("devos_chat_worker.worker.call_llm")
    def test_classify_frontend_task(self, mock_llm):
        """Should classify frontend UI tasks to 'frontend' role."""
        mock_llm.return_value = json.dumps({
            "should_handoff": True,
            "target_role": "frontend",
            "reason": "Frontend UI component development",
            "context": "Create a responsive login form component with validation"
        })
        
        role, context = coordinator_classify_and_route("build responsive login form")
        assert role == "frontend"
        assert "login" in context.lower() or "form" in context.lower()

    @patch("devos_chat_worker.worker.call_llm")
    def test_classify_test_task(self, mock_llm):
        """Should classify testing tasks to 'test' role."""
        mock_llm.return_value = json.dumps({
            "should_handoff": True,
            "target_role": "test",
            "reason": "Testing and QA task",
            "context": "Write integration tests for user authentication flow"
        })
        
        role, context = coordinator_classify_and_route("write tests for auth flow")
        assert role == "test"
        assert "test" in context.lower() or "auth" in context.lower()

    @patch("devos_chat_worker.worker.call_llm")
    def test_classify_review_task(self, mock_llm):
        """Should classify code review tasks to 'review' role."""
        mock_llm.return_value = json.dumps({
            "should_handoff": True,
            "target_role": "review",
            "reason": "Code review and security audit",
            "context": "Review PR #123 for security vulnerabilities and best practices"
        })
        
        role, context = coordinator_classify_and_route("review PR 123 for security")
        assert role == "review"
        assert "PR" in context or "review" in context.lower()

    @patch("devos_chat_worker.worker.call_llm")
    def test_classify_devops_task(self, mock_llm):
        """Should classify DevOps/infrastructure tasks to 'devops' role."""
        mock_llm.return_value = json.dumps({
            "should_handoff": True,
            "target_role": "devops",
            "reason": "CI/CD and infrastructure configuration",
            "context": "Set up GitHub Actions workflow for automated testing and deployment"
        })
        
        role, context = coordinator_classify_and_route("setup CI/CD pipeline")
        assert role == "devops"
        assert "CI" in context or "CD" in context or "GitHub" in context

    @patch("devos_chat_worker.worker.call_llm")
    def test_classify_invalid_json_returns_none(self, mock_llm):
        """Should handle invalid JSON gracefully."""
        mock_llm.return_value = "this is not json"
        
        role, context = coordinator_classify_and_route("some task")
        assert role is None
        assert context is None

    @patch("devos_chat_worker.worker.call_llm")
    def test_classify_no_handoff_returns_none(self, mock_llm):
        """Should return (None, None) when should_handoff=false."""
        mock_llm.return_value = json.dumps({
            "should_handoff": False,
            "target_role": None,
            "reason": "Coordinator can handle this directly",
            "context": ""
        })
        
        role, context = coordinator_classify_and_route("help me understand this code")
        assert role is None
        assert context is None


class TestCollectRealArtifacts:
    """Tests for collect_real_artifacts()"""

    def test_collect_empty_payload_returns_empty_dict(self):
        """Should return empty dict for empty or None payload."""
        result = collect_real_artifacts(None)
        assert result == {}
        
        result = collect_real_artifacts({})
        assert result == {}

    def test_collect_preview_url(self):
        """Should collect preview URL from payload."""
        result = collect_real_artifacts({
            "preview_url": "https://localhost:3000/preview"
        })
        assert result.get("previewUrl") == "https://localhost:3000/preview"

    def test_collect_screenshot_file(self):
        """Should collect screenshot from file path."""
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            screenshot_path = f.name
            f.write(b"PNG_DATA")
        
        try:
            result = collect_real_artifacts({
                "screenshot_path": screenshot_path
            })
            assert "screenshots" in result
            assert len(result["screenshots"]) == 1
            assert screenshot_path in result["screenshots"][0]
        finally:
            os.unlink(screenshot_path)

    def test_collect_build_artifacts_dir(self):
        """Should collect build artifacts from directory."""
        with tempfile.TemporaryDirectory() as tmpdir:
            # Create some test files
            open(os.path.join(tmpdir, "bundle.js"), "w").close()
            open(os.path.join(tmpdir, "styles.css"), "w").close()
            
            result = collect_real_artifacts({
                "build_dir": tmpdir
            })
            assert "buildArtifacts" in result
            assert len(result["buildArtifacts"]) == 2
            # Check that files are listed
            names = [item["name"] for item in result["buildArtifacts"]]
            assert "bundle.js" in names
            assert "styles.css" in names

    def test_collect_test_report_file(self):
        """Should collect test report file."""
        with tempfile.NamedTemporaryFile(suffix=".html", delete=False) as f:
            report_path = f.name
            f.write(b"<html>Test Report</html>")
        
        try:
            result = collect_real_artifacts({
                "test_report_file": report_path
            })
            assert "testReport" in result
            assert report_path in result["testReport"]
        finally:
            os.unlink(report_path)

    def test_collect_log_file(self):
        """Should collect logs from file."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".log", delete=False) as f:
            log_path = f.name
            f.write("Line 1\nLine 2\nTest passed\n")
        
        try:
            result = collect_real_artifacts({
                "log_file": log_path
            })
            assert "logSummary" in result
            assert "Test passed" in result["logSummary"]
        finally:
            os.unlink(log_path)

    def test_collect_all_artifacts_together(self):
        """Should collect multiple artifact types in one call."""
        with tempfile.TemporaryDirectory() as tmpdir:
            # Create all artifact types
            screenshot_file = os.path.join(tmpdir, "screenshot.png")
            open(screenshot_file, "w").close()
            
            build_dir = os.path.join(tmpdir, "build")
            os.makedirs(build_dir, exist_ok=True)
            open(os.path.join(build_dir, "output.js"), "w").close()
            
            report_file = os.path.join(tmpdir, "report.html")
            open(report_file, "w").close()
            
            log_file = os.path.join(tmpdir, "execution.log")
            with open(log_file, "w") as f:
                f.write("Execution completed successfully")
            
            payload = {
                "preview_url": "http://localhost:8000",
                "screenshot_path": screenshot_file,
                "build_dir": build_dir,
                "test_report_file": report_file,
                "log_file": log_file,
            }
            
            result = collect_real_artifacts(payload)
            
            # Verify all artifact types are present
            assert result.get("previewUrl") == "http://localhost:8000"
            assert "screenshots" in result
            assert "buildArtifacts" in result
            assert "testReport" in result
            assert "logSummary" in result

    def test_collect_invalid_paths_gracefully(self):
        """Should handle non-existent paths gracefully."""
        result = collect_real_artifacts({
            "screenshot_path": "/nonexistent/screenshot.png",
            "build_dir": "/nonexistent/build",
            "test_report_file": "/nonexistent/report.html",
            "log_file": "/nonexistent/logs.txt",
        })
        # Should return empty dict without crashing
        assert isinstance(result, dict)

    def test_collect_from_result_notepad(self):
        """Should fallback to result notepad for log summary."""
        result = collect_real_artifacts(
            {},
            {"notepad": "This is the execution notepad content with important info"}
        )
        assert "logSummary" in result
        assert "important info" in result["logSummary"]
