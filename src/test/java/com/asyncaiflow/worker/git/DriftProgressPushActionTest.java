package com.asyncaiflow.worker.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.asyncaiflow.worker.gpt.validation.ActionSchemaResolver;
import com.asyncaiflow.worker.gpt.validation.ActionSchemaValidator;
import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration tests for the {@code drift_progress_push} action type in
 * {@link GitWorkerActionHandler}.
 *
 * <p>Requires a local {@code git} binary. Tests are skipped when git is
 * unavailable (CI environments that strip it).
 */
class DriftProgressPushActionTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path workspaceRoot;

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void driftProgressPush_creates_branch_and_commits() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git command not available");

        Path repoRoot = initRepository("drift-repo");
        GitWorkerActionHandler handler = newHandler();

        // Write a file that will be committed
        Files.writeString(repoRoot.resolve("drift_patch.diff"),
                "--- a/x.py\n+++ b/x.py\n@@ -1 +1 @@\n-old\n+new\n",
                StandardCharsets.UTF_8);

        String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "repoPath", "drift-repo",
                "branch_prefix", "drift/hackathon",
                "issue_text", "修复 NPC 生成逻辑",
                "player_id", "Steve"));

        WorkerExecutionResult result = handler.execute(action("drift_progress_push", payload));

        assertEquals("SUCCEEDED", result.status(), result.errorMessage());

        JsonNode resultNode = OBJECT_MAPPER.readTree(result.result());
        assertNotNull(resultNode.path("branch").asText(null), "branch field must be present");
        assertNotNull(resultNode.path("commitHash").asText(null), "commitHash field must be present");
        assertEquals("Steve", resultNode.path("playerId").asText());

        // Verify branch name starts with the expected prefix
        String branch = resultNode.path("branch").asText();
        assertTrue(branch.startsWith("drift/hackathon/"),
                "Branch name must start with 'drift/hackathon/' but was: " + branch);

        // Verify branch exists locally
        String currentBranch = runGitChecked(repoRoot,
                List.of("rev-parse", "--abbrev-ref", "HEAD")).trim();
        assertEquals(branch, currentBranch);
    }

    @Test
    void driftProgressPush_defaultBranchPrefix_when_missing() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git command not available");

        Path repoRoot = initRepository("drift-repo-default");
        GitWorkerActionHandler handler = newHandler();

        String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "repoPath", "drift-repo-default",
                "issue_text", "Some issue",
                "player_id", "Alex"));

        WorkerExecutionResult result = handler.execute(action("drift_progress_push", payload));

        assertEquals("SUCCEEDED", result.status(), result.errorMessage());
        JsonNode resultNode = OBJECT_MAPPER.readTree(result.result());
        String branch = resultNode.path("branch").asText("");
        assertFalse(branch.isBlank(), "branch must not be blank even with default prefix");
        assertTrue(branch.startsWith("drift/session/"), "Default prefix must be 'drift/session/'");
    }

    @Test
    void driftProgressPush_commit_message_contains_player_and_issue() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git command not available");

        Path repoRoot = initRepository("drift-repo-msg");
        GitWorkerActionHandler handler = newHandler();

        String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "repoPath", "drift-repo-msg",
                "branch_prefix", "drift/test",
                "issue_text", "NPC 问题修复",
                "player_id", "Notch"));

        WorkerExecutionResult result = handler.execute(action("drift_progress_push", payload));
        assertEquals("SUCCEEDED", result.status(), result.errorMessage());

        // Read the actual commit message from git log
        String commitMsg = runGitChecked(repoRoot, List.of("log", "-1", "--pretty=%s")).trim();
        assertTrue(commitMsg.contains("Notch"), "Commit message must contain player_id");
        assertTrue(commitMsg.contains("NPC"), "Commit message must contain issue text excerpt");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Negative: unsupported action type still rejected
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void unsupported_action_type_returns_failed() throws Exception {
        GitWorkerActionHandler handler = newHandler();
        WorkerExecutionResult result = handler.execute(action("drift_unknown", "{}"));
        assertEquals("FAILED", result.status());
        // result field holds the short failure code; errorMessage holds the detail
        assertTrue(result.result().contains("unsupported action type")
                || (result.errorMessage() != null && result.errorMessage().contains("unsupported")),
                "failure reason must mention unsupported action type");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private GitWorkerActionHandler newHandler() {
        ActionSchemaResolver resolver = new ActionSchemaResolver("schemas/v1");
        ActionSchemaValidator validator = new ActionSchemaValidator(OBJECT_MAPPER, resolver);
        return new GitWorkerActionHandler(
                OBJECT_MAPPER,
                workspaceRoot,
                validator,
                SchemaValidationMode.WARN,   // WARN so unknown types don't block
                262144,
                65536,
                30000L);
    }

    private Path initRepository(String relativeRepoPath) throws Exception {
        Path repoRoot = workspaceRoot.resolve(relativeRepoPath);
        Files.createDirectories(repoRoot);
        runGitChecked(repoRoot, List.of("init"));
        runGitChecked(repoRoot, List.of("config", "user.email", "drift@example.com"));
        runGitChecked(repoRoot, List.of("config", "user.name", "Drift Test"));

        Path readme = repoRoot.resolve("README.md");
        Files.writeString(readme, "# drift test repo\n", StandardCharsets.UTF_8);
        runGitChecked(repoRoot, List.of("add", "README.md"));
        runGitChecked(repoRoot, List.of("commit", "-m", "initial commit"));
        return repoRoot;
    }

    private static ActionAssignment action(String type, String payload) {
        return new ActionAssignment(401L, 31L, type, payload, 0,
                LocalDateTime.now().plusMinutes(2));
    }

    private static boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String runGitChecked(Path repoRoot, List<String> args) throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repoRoot.toString());
        command.addAll(args);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new RuntimeException("git timed out: " + command);
        }
        if (process.exitValue() != 0) {
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("git failed (" + process.exitValue() + "): " + out);
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
