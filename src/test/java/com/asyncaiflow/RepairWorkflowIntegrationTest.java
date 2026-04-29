package com.asyncaiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.asyncaiflow.domain.entity.ActionEntity;
import com.asyncaiflow.domain.enums.ActionStatus;
import com.asyncaiflow.mapper.ActionDependencyMapper;
import com.asyncaiflow.mapper.ActionLogMapper;
import com.asyncaiflow.mapper.ActionMapper;
import com.asyncaiflow.mapper.WorkerMapper;
import com.asyncaiflow.mapper.WorkflowMapper;
import com.asyncaiflow.planner.PlanDraftStep;
import com.asyncaiflow.planner.WorkflowPlanGenerator;
import com.asyncaiflow.service.ActionService;
import com.asyncaiflow.service.WorkflowService;
import com.asyncaiflow.service.WorkerService;
import com.asyncaiflow.service.queue.ActionQueueService;
import com.asyncaiflow.web.dto.ActionAssignmentResponse;
import com.asyncaiflow.web.dto.ActionResponse;
import com.asyncaiflow.web.dto.CreateActionRequest;
import com.asyncaiflow.web.dto.CreateWorkflowRequest;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;
import com.asyncaiflow.web.dto.SubmitActionResultRequest;
import com.asyncaiflow.web.dto.WorkflowExecutionResponse;
import com.asyncaiflow.web.dto.WorkflowResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.asyncaiflow.worker.git.GitWorkerActionHandler;
import com.asyncaiflow.worker.gpt.GptWorkerActionHandler;
import com.asyncaiflow.worker.gpt.GptWorkerProperties;
import com.asyncaiflow.worker.gpt.OpenAiCompatibleLlmClient;
import com.asyncaiflow.worker.gpt.validation.ActionSchemaResolver;
import com.asyncaiflow.worker.gpt.validation.ActionSchemaValidator;
import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;
import com.asyncaiflow.worker.repository.RepositoryWorkerActionHandler;
import com.asyncaiflow.worker.sdk.WorkerActionHandler;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(RepairWorkflowIntegrationTest.QueueTestConfig.class)
class RepairWorkflowIntegrationTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private ActionService actionService;

    @Autowired
    private WorkflowMapper workflowMapper;

    @Autowired
    private WorkerMapper workerMapper;

    @Autowired
    private ActionMapper actionMapper;

    @Autowired
    private ActionDependencyMapper actionDependencyMapper;

    @Autowired
    private ActionLogMapper actionLogMapper;

    @Autowired
    private ActionQueueService actionQueueService;

    @TempDir
    Path workspaceRoot;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowPlanGenerator generator = new WorkflowPlanGenerator();

    @BeforeEach
    void cleanTables() {
        actionLogMapper.delete(null);
        actionDependencyMapper.delete(null);
        actionMapper.delete(null);
        workerMapper.delete(null);
        workflowMapper.delete(null);
        ((InMemoryActionQueueService) actionQueueService).clear();
    }

    @Test
    void fixTodoInSceneRuntimeRunsFullRepairWorkflow() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git command not available");

        Path repoRoot = initRepository("scene-runtime-repo");
        Path sceneFile = repoRoot.resolve("src/main/java/demo/SceneRuntime.java");
        Files.createDirectories(sceneFile.getParent());

        String originalCode = """
                package demo;

                class SceneRuntime {
                    String nextScene() {
                        // TODO: implement next scene selection
                        return \"TODO\";
                    }
                }
                """;
        Files.writeString(sceneFile, originalCode, StandardCharsets.UTF_8);
        runGitChecked(repoRoot, List.of("add", "src/main/java/demo/SceneRuntime.java"));
        runGitChecked(repoRoot, List.of("commit", "-m", "Add SceneRuntime TODO"));

        String fixedCode = """
                package demo;

                class SceneRuntime {
                    String nextScene() {
                        return \"scene-ready\";
                    }
                }
                """;
        Files.writeString(sceneFile, fixedCode, StandardCharsets.UTF_8);
        String patch = runGitChecked(repoRoot, List.of("diff", "--", "src/main/java/demo/SceneRuntime.java"));
        runGitChecked(repoRoot, List.of("checkout", "--", "src/main/java/demo/SceneRuntime.java"));

        String relativeFile = workspaceRoot.relativize(sceneFile).toString().replace('\\', '/');
        List<PlanDraftStep> plan = generator.generatePlan(
                "Fix TODO in SceneRuntime",
                "scene runtime transition selection",
                relativeFile);

        assertEquals(List.of(
                "search_semantic",
                "load_code",
                "build_context_pack",
                "design_solution",
                "generate_patch",
                "review_patch",
                "apply_patch",
                "commit_changes"), plan.stream().map(PlanDraftStep::type).toList());

        workerService.register(new RegisterWorkerRequest(
                "repository-worker-e2e",
                List.of("search_semantic", "load_code", "build_context_pack")));
        workerService.register(new RegisterWorkerRequest(
                "gpt-worker-e2e",
                List.of("design_solution", "generate_patch", "review_patch")));
        workerService.register(new RegisterWorkerRequest(
                "git-worker-e2e",
                List.of("apply_patch", "commit_changes")));

        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("repair-scene-runtime", "End-to-end repair workflow"));
        createActionsFromPlan(workflow.id(), plan);

        RepositoryWorkerActionHandler repositoryHandler = newRepositoryHandler();
        GptWorkerActionHandler gptHandler = newPatchGptHandler(patch);
        GitWorkerActionHandler gitHandler = newGitHandler();

        processAssignment("repository-worker-e2e", repositoryHandler, "search_semantic");
        processAssignment("repository-worker-e2e", repositoryHandler, "load_code");
        processAssignment("repository-worker-e2e", repositoryHandler, "build_context_pack");
        processAssignment("gpt-worker-e2e", gptHandler, "design_solution");
        processAssignment("gpt-worker-e2e", gptHandler, "generate_patch");
        processAssignment("gpt-worker-e2e", gptHandler, "review_patch");
        processApplyPatchAssignment("git-worker-e2e", gitHandler, relativeFile);
        processAssignment("git-worker-e2e", gitHandler, "commit_changes");

        WorkflowExecutionResponse execution = workflowService.getWorkflowExecution(workflow.id());
        assertEquals("COMPLETED", execution.status());
        assertEquals(8, execution.actions().size());
        assertTrue(execution.actions().stream().allMatch(action -> "COMPLETED".equals(action.status())));

        List<ActionEntity> actions = actionMapper.selectBatchIds(execution.actions().stream().map(action -> action.actionId()).toList());
        assertTrue(actions.stream().allMatch(action -> ActionStatus.SUCCEEDED.name().equals(action.getStatus())));
        assertTrue(Files.readString(sceneFile).contains("scene-ready"));
        assertEquals("Apply fix: Fix TODO in SceneRuntime",
                runGitChecked(repoRoot, List.of("log", "-1", "--pretty=%s")).trim());
    }

    private void createActionsFromPlan(Long workflowId, List<PlanDraftStep> plan) throws Exception {
        Map<Integer, Long> actionIdsByIndex = new HashMap<>();
        for (int index = 0; index < plan.size(); index++) {
            PlanDraftStep step = plan.get(index);
            List<Long> upstreamActionIds = step.dependsOn().stream()
                    .map(actionIdsByIndex::get)
                    .toList();

            ActionResponse action = actionService.createAction(new CreateActionRequest(
                    workflowId,
                    step.type(),
                    objectMapper.writeValueAsString(step.payload()),
                    upstreamActionIds,
                    1,
                    1,
                    120,
                    null
            ));
            actionIdsByIndex.put(index, action.id());
        }
    }

    private void processAssignment(String workerId, WorkerActionHandler handler, String expectedType) throws Exception {
        Optional<ActionAssignmentResponse> assignment = actionService.pollAction(workerId);
        assertTrue(assignment.isPresent());
        assertEquals(expectedType, assignment.get().type());

        ActionAssignment actionAssignment = new ActionAssignment(
                assignment.get().actionId(),
                assignment.get().workflowId(),
                assignment.get().type(),
                assignment.get().payload(),
                assignment.get().retryCount(),
                assignment.get().leaseExpireAt());

        WorkerExecutionResult executionResult = handler.execute(actionAssignment);
        assertEquals("SUCCEEDED", executionResult.status(), executionResult.errorMessage());

        ActionResponse submitted = actionService.submitResult(new SubmitActionResultRequest(
                workerId,
                assignment.get().actionId(),
                executionResult.status(),
                executionResult.result(),
                executionResult.errorMessage()
        ));
        assertEquals(ActionStatus.SUCCEEDED.name(), submitted.status());
    }

    private void processApplyPatchAssignment(String workerId, WorkerActionHandler handler, String expectedRepoPath)
            throws Exception {
        Optional<ActionAssignmentResponse> assignment = actionService.pollAction(workerId);
        assertTrue(assignment.isPresent());
        assertEquals("apply_patch", assignment.get().type());

        JsonNode materializedPayload = objectMapper.readTree(assignment.get().payload());
        assertEquals(expectedRepoPath, materializedPayload.path("repoPath").asText());

        ActionAssignment actionAssignment = new ActionAssignment(
                assignment.get().actionId(),
                assignment.get().workflowId(),
                assignment.get().type(),
                assignment.get().payload(),
                assignment.get().retryCount(),
                assignment.get().leaseExpireAt());

        WorkerExecutionResult executionResult = handler.execute(actionAssignment);
        assertEquals("SUCCEEDED", executionResult.status(), executionResult.errorMessage());

        ActionResponse submitted = actionService.submitResult(new SubmitActionResultRequest(
                workerId,
                assignment.get().actionId(),
                executionResult.status(),
                executionResult.result(),
                executionResult.errorMessage()
        ));
        assertEquals(ActionStatus.SUCCEEDED.name(), submitted.status());
    }

    private RepositoryWorkerActionHandler newRepositoryHandler() {
        ActionSchemaResolver resolver = new ActionSchemaResolver("schemas/v1");
        ActionSchemaValidator validator = new ActionSchemaValidator(objectMapper, resolver);
        return new RepositoryWorkerActionHandler(
                objectMapper,
                workspaceRoot,
                validator,
                SchemaValidationMode.STRICT,
                50,
                65536,
                List.of(".git", ".idea", ".aiflow", "target", "build", "node_modules"),
                5,
                3,
                4000,
                null);
    }

    private GptWorkerActionHandler newPatchGptHandler(String patch) {
        GptWorkerProperties.LlmProperties llmProperties = new GptWorkerProperties.LlmProperties();
        llmProperties.setMockFallbackEnabled(true);

        ActionSchemaResolver resolver = new ActionSchemaResolver("schemas/v1");
        ActionSchemaValidator validator = new ActionSchemaValidator(objectMapper, resolver);
        OpenAiCompatibleLlmClient llmClient = new StubPatchLlmClient(patch, llmProperties);
        return new GptWorkerActionHandler(objectMapper, llmClient, validator, SchemaValidationMode.STRICT);
    }

    private GitWorkerActionHandler newGitHandler() {
        ActionSchemaResolver resolver = new ActionSchemaResolver("schemas/v1");
        ActionSchemaValidator validator = new ActionSchemaValidator(objectMapper, resolver);
        return new GitWorkerActionHandler(
                objectMapper,
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

    private static final class StubPatchLlmClient extends OpenAiCompatibleLlmClient {

        private final String patch;

        private StubPatchLlmClient(String patch, GptWorkerProperties.LlmProperties properties) {
            super(new ObjectMapper(), properties);
            this.patch = patch;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            String actionType = extractActionType(userPrompt);
            return switch (actionType) {
                case "design_solution" -> """
                        结论
                        需要去掉 SceneRuntime 中的 TODO 返回值并给出稳定结果。

                        发现
                        当前实现保留 TODO 占位并返回字符串 TODO。

                        代码位置
                        - SceneRuntime.nextScene

                        建议修复
                        - 直接返回稳定的 scene-ready 值

                        风险
                        - 低，变更范围仅限一个方法。
                        """;
                case "generate_patch" -> patchCompletion("统一补丁");
                case "review_patch" -> patchCompletion("修订补丁");
                default -> "unexpected action: " + actionType;
            };
        }

        @Override
        public String modelName() {
            return "stub-patch-model";
        }

        private String patchCompletion(String patchSectionTitle) {
            return """
                    结论
                    补丁满足最小修复目标。

                    发现
                    仅修改 TODO 占位逻辑，没有扩散到其他方法。

                    是否可应用
                    可以应用。

                    %s
                    ```diff
                    %s
                    ```

                    风险
                    低。
                    """.formatted(patchSectionTitle, patch.trim());
        }

        private String extractActionType(String userPrompt) {
            if (userPrompt == null || userPrompt.isBlank()) {
                return "";
            }
            for (String line : userPrompt.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Action:")) {
                    return trimmed.substring("Action:".length()).trim();
                }
            }
            return "";
        }
    }

    @TestConfiguration
    static class QueueTestConfig {

        @Bean
        @Primary
        ActionQueueService actionQueueService() {
            return new InMemoryActionQueueService();
        }
    }

    static class InMemoryActionQueueService extends ActionQueueService {

        private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> queues = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, LeaseState> locks = new ConcurrentHashMap<>();

        InMemoryActionQueueService() {
            super(new StringRedisTemplate());
        }

        @Override
        public void enqueue(ActionEntity action) {
            enqueue(action, action.getType());
        }

        @Override
        public void enqueue(ActionEntity action, String capability) {
            String queueCapability = (capability == null || capability.isBlank()) ? action.getType() : capability;
            queues.computeIfAbsent(queueCapability, key -> new ConcurrentLinkedDeque<>()).addFirst(action.getId());
        }

        @Override
        public Optional<Long> claimNextAction(List<String> capabilities, String workerId) {
            LocalDateTime now = LocalDateTime.now();
            for (String capability : capabilities) {
                ConcurrentLinkedDeque<Long> queue = queues.get(capability);
                if (queue == null) {
                    continue;
                }
                while (true) {
                    Long actionId = queue.pollLast();
                    if (actionId == null) {
                        break;
                    }
                    LeaseState leaseState = locks.get(actionId);
                    if (leaseState == null || leaseState.expireAt().isBefore(now)) {
                        locks.put(actionId, new LeaseState(workerId, now.plusMinutes(10)));
                        return Optional.of(actionId);
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public void releaseLock(Long actionId) {
            locks.remove(actionId);
        }

        @Override
        public void refreshActionLock(Long actionId, String workerId, long ttlSeconds) {
            LocalDateTime now = LocalDateTime.now();
            LeaseState current = locks.get(actionId);
            if (current != null && current.owner().equals(workerId)) {
                locks.put(actionId, new LeaseState(workerId, now.plusSeconds(Math.max(1L, ttlSeconds))));
            }
        }

        @Override
        public void refreshHeartbeat(String workerId) {
            // no-op for in-memory queue test configuration
        }

        void clear() {
            queues.clear();
            locks.clear();
        }

        private record LeaseState(String owner, LocalDateTime expireAt) {
        }
    }
}