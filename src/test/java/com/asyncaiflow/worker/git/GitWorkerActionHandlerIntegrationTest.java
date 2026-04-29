package com.asyncaiflow.worker.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class GitWorkerActionHandlerIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path workspaceRoot;

    @Test
    void createBranchActionCreatesAndChecksOutBranch() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git command not available");

        Path repoRoot = initRepository("repo-alpha");
        GitWorkerActionHandler handler = newHandler();

        String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "repoPath", "repo-alpha",
                "branchName", "feature/load-code",
                "checkout", true));

        WorkerExecutionResult result = handler.execute(action("create_branch", payload));
        assertEquals("SUCCEEDED", result.status(), result.errorMessage());

        JsonNode resultNode = OBJECT_MAPPER.readTree(result.result());
        assertEquals("feature/load-code", resultNode.path("branchName").asText());
        assertEquals("feature/load-code", resultNode.path("currentBranch").asText());
        assertEquals("feature/load-code", runGitChecked(repoRoot, List.of("rev-parse", "--abbrev-ref", "HEAD")).trim());
    }

    @Test
    void applyPatchAndCommitActionsCanBeExecutedSequentially() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git command not available");

        Path repoRoot = initRepository("repo-beta");
        Path sourceFile = repoRoot.resolve("src/Main.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class Main {\n}\n");
        runGitChecked(repoRoot, List.of("add", "src/Main.java"));
        runGitChecked(repoRoot, List.of("commit", "-m", "add main class"));

        Files.writeString(sourceFile, "class Main {\n  String mode = \"new\";\n}\n");
        String patch = runGitChecked(repoRoot, List.of("diff", "--", "src/Main.java"));
        runGitChecked(repoRoot, List.of("checkout", "--", "src/Main.java"));

        GitWorkerActionHandler handler = newHandler();

        String applyPayload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "repoPath", "repo-beta",
                "patch", patch,
                "threeWay", true));

        WorkerExecutionResult applyResult = handler.execute(action("apply_patch", applyPayload));
        assertEquals("SUCCEEDED", applyResult.status(), applyResult.errorMessage());

        JsonNode applyNode = OBJECT_MAPPER.readTree(applyResult.result());
        assertTrue(applyNode.path("changedFileCount").asInt() >= 1);
        assertTrue(applyNode.path("changedFiles").toString().contains("src/Main.java"));
        assertTrue(Files.readString(sourceFile).contains("String mode = \"new\";"));

        String commitPayload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "repoPath", "repo-beta",
                "message", "Apply Main mode patch"));

        WorkerExecutionResult commitResult = handler.execute(action("commit_changes", commitPayload));
        assertEquals("SUCCEEDED", commitResult.status(), commitResult.errorMessage());

        JsonNode commitNode = OBJECT_MAPPER.readTree(commitResult.result());
        assertTrue(commitNode.path("commitHash").asText().length() >= 7);
        assertEquals("Apply Main mode patch", runGitChecked(repoRoot, List.of("log", "-1", "--pretty=%s")).trim());
    }

        @Test
        void applyPatchSupportsWorkspaceRelativePatchPathsForNestedRepository() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git command not available");

        Path repoRoot = initRepository("godot-runtime");
        Path sourceFile = repoRoot.resolve("backend/app/core/runtime_ir/runtime_adapter.py");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile,
            "from typing import Any, Dict, List\n\n"
                + "class RuntimeAdapter:\n"
                + "    def compile(self, actions: List[Any]) -> Dict[str, Any]:\n"
                + "        raise NotImplementedError\n");
        runGitChecked(repoRoot, List.of("add", "backend/app/core/runtime_ir/runtime_adapter.py"));
        runGitChecked(repoRoot, List.of("commit", "-m", "add runtime adapter"));

        Files.writeString(sourceFile,
            "from typing import Any, Callable, Dict, List, Optional\n\n"
                + "class RuntimeAdapter:\n"
                + "    def compile(self, actions: List[Any], progress_callback: Optional[Callable[[float], None]] = None) -> Dict[str, Any]:\n"
                + "        raise NotImplementedError\n");
        String nestedPatch = runGitChecked(repoRoot,
            List.of("diff", "--", "backend/app/core/runtime_ir/runtime_adapter.py"));
        runGitChecked(repoRoot, List.of("checkout", "--", "backend/app/core/runtime_ir/runtime_adapter.py"));

        String workspaceRelativePatch = nestedPatch
            .replace("a/backend/app/core/runtime_ir/runtime_adapter.py",
                "a/godot-runtime/backend/app/core/runtime_ir/runtime_adapter.py")
            .replace("b/backend/app/core/runtime_ir/runtime_adapter.py",
                "b/godot-runtime/backend/app/core/runtime_ir/runtime_adapter.py");

        GitWorkerActionHandler handler = newHandler();
        String applyPayload = OBJECT_MAPPER.writeValueAsString(Map.of(
            "schemaVersion", "v1",
            "repoPath", "godot-runtime/backend/app/core/runtime_ir/runtime_adapter.py",
            "patch", workspaceRelativePatch,
            "threeWay", true));

        WorkerExecutionResult applyResult = handler.execute(action("apply_patch", applyPayload));
        assertEquals("SUCCEEDED", applyResult.status(), applyResult.errorMessage());

        JsonNode applyNode = OBJECT_MAPPER.readTree(applyResult.result());
        assertTrue(applyNode.path("changedFileCount").asInt() >= 1);
        assertTrue(applyNode.path("changedFiles").toString().contains("backend/app/core/runtime_ir/runtime_adapter.py"));
        assertTrue(Files.readString(sourceFile).contains("progress_callback"));
        }

    @Test
    void createBranchFailsWhenWorkspaceContainsMultipleRepositoriesAndRepoPathIsMissing() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git command not available");

        initRepository("repo-one");
        initRepository("repo-two");

        GitWorkerActionHandler handler = newHandler();
        String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "branchName", "feature/ambiguous"));

        WorkerExecutionResult result = handler.execute(action("create_branch", payload));
        assertEquals("FAILED", result.status());
        assertTrue(result.errorMessage().contains("multiple git repositories"));
    }

    private GitWorkerActionHandler newHandler() {
        ActionSchemaResolver resolver = new ActionSchemaResolver("schemas/v1");
        ActionSchemaValidator validator = new ActionSchemaValidator(OBJECT_MAPPER, resolver);
        return new GitWorkerActionHandler(
                OBJECT_MAPPER,
                workspaceRoot,
                validator,
                SchemaValidationMode.STRICT,
                262144,
                65536,
                20000L);
    }

    private Path initRepository(String relativeRepoPath) throws Exception {
        Path repoRoot = workspaceRoot.resolve(relativeRepoPath);
        Files.createDirectories(repoRoot);
        runGitChecked(repoRoot, List.of("init"));
        runGitChecked(repoRoot, List.of("config", "user.email", "asyncaiflow@example.com"));
        runGitChecked(repoRoot, List.of("config", "user.name", "AsyncAIFlow Test"));

        Path readme = repoRoot.resolve("README.md");
        Files.writeString(readme, "# test repo\n", StandardCharsets.UTF_8);
        runGitChecked(repoRoot, List.of("add", "README.md"));
        runGitChecked(repoRoot, List.of("commit", "-m", "initial commit"));
        return repoRoot;
    }

    private static ActionAssignment action(String type, String payload) {
        return new ActionAssignment(301L, 21L, type, payload, 0, LocalDateTime.now().plusMinutes(2));
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

        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "git command failed (" + process.exitValue() + "): " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
