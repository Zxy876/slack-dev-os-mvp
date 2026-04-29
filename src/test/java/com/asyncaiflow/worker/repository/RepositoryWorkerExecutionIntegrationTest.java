package com.asyncaiflow.worker.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
import com.asyncaiflow.domain.entity.ActionLogEntity;
import com.asyncaiflow.domain.enums.ActionStatus;
import com.asyncaiflow.mapper.ActionDependencyMapper;
import com.asyncaiflow.mapper.ActionLogMapper;
import com.asyncaiflow.mapper.ActionMapper;
import com.asyncaiflow.mapper.WorkerMapper;
import com.asyncaiflow.mapper.WorkflowMapper;
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
import com.asyncaiflow.worker.gpt.GptWorkerActionHandler;
import com.asyncaiflow.worker.gpt.GptWorkerProperties;
import com.asyncaiflow.worker.gpt.OpenAiCompatibleLlmClient;
import com.asyncaiflow.worker.gpt.validation.ActionSchemaResolver;
import com.asyncaiflow.worker.gpt.validation.ActionSchemaValidator;
import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(RepositoryWorkerExecutionIntegrationTest.QueueTestConfig.class)
class RepositoryWorkerExecutionIntegrationTest {

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
    void searchCodeActionCanExecuteAndSubmitResult() throws Exception {
        Path sourceFile = writeWorkspaceFile("src/demo/AuthService.java", "class AuthService {\n  String token = \"AlphaToken\";\n}\n");
        String workerId = "repository-search-worker";
        workerService.register(new RegisterWorkerRequest(workerId, List.of("search_code")));

        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("search-code-demo", "repository worker search integration test"));

        String payload = objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "query", "AlphaToken",
                "scope", Map.of("paths", List.of(workspaceRoot.relativize(sourceFile).toString().replace('\\', '/')))));

        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "search_code",
                payload,
                List.of(),
                1,
                1,
                120,
                null
        ));

        Optional<ActionAssignmentResponse> assignment = actionService.pollAction(workerId);
        assertTrue(assignment.isPresent());
        assertEquals("search_code", assignment.get().type());

        WorkerExecutionResult executionResult = newRepositoryHandler().execute(new ActionAssignment(
                assignment.get().actionId(),
                assignment.get().workflowId(),
                assignment.get().type(),
                assignment.get().payload(),
                assignment.get().retryCount(),
                assignment.get().leaseExpireAt()));

        assertEquals("SUCCEEDED", executionResult.status());

        ActionResponse submitted = actionService.submitResult(new SubmitActionResultRequest(
                workerId,
                action.id(),
                executionResult.status(),
                executionResult.result(),
                executionResult.errorMessage()
        ));

        assertEquals(ActionStatus.SUCCEEDED.name(), submitted.status());

        JsonNode resultNode = loadSingleResultNode(action.id());
        assertEquals("v1", resultNode.path("schemaVersion").asText());
        assertEquals("repository-worker", resultNode.path("worker").asText());
        assertEquals(1, resultNode.path("matchCount").asInt());
        assertEquals("src/demo/AuthService.java", resultNode.path("matches").get(0).path("path").asText());
        assertTrue(resultNode.path("matches").get(0).path("lineText").asText().contains("AlphaToken"));
    }

    @Test
    void searchSemanticAndContextPackActionsCanExecuteAndSubmitResult() throws Exception {
        Path roadmap = writeWorkspaceFile(
                "docs/roadmap.md",
                "Near-term priorities include streaming support and persistence.\nHuman-in-the-loop remains planned.\n");
        String relativeRoadmap = workspaceRoot.relativize(roadmap).toString().replace('\\', '/');

        String semanticWorkerId = "repository-semantic-worker";
        String contextWorkerId = "repository-context-worker";
        workerService.register(new RegisterWorkerRequest(semanticWorkerId, List.of("search_semantic")));
        workerService.register(new RegisterWorkerRequest(contextWorkerId, List.of("build_context_pack")));

        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("semantic-context-demo", "repository semantic/context integration test"));

        ActionResponse semanticAction = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "search_semantic",
                objectMapper.writeValueAsString(Map.of(
                        "schemaVersion", "v1",
                        "query", "streaming support persistence",
                        "topK", 3,
                        "scope", Map.of("paths", List.of("docs")))),
                List.of(),
                1,
                1,
                120,
                null
        ));

        ActionResponse contextAction = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "build_context_pack",
                objectMapper.writeValueAsString(Map.of(
                        "schemaVersion", "v1",
                        "issue", "Find pending roadmap priorities",
                        "query", "streaming support persistence",
                        "maxFiles", 2,
                        "maxCharsPerFile", 1000,
                        "retrievalResults", List.of(Map.of(
                                "path", relativeRoadmap,
                                "score", 0.91,
                                "chunk", "Near-term priorities include streaming support and persistence.")))),
                List.of(semanticAction.id()),
                1,
                1,
                120,
                null
        ));

        processAssignment(semanticWorkerId, newRepositoryHandler(), "search_semantic");
        processAssignment(contextWorkerId, newRepositoryHandler(), "build_context_pack");

        JsonNode semanticNode = loadSingleResultNode(semanticAction.id());
        assertEquals("local_fallback", semanticNode.path("engine").asText());
        assertTrue(semanticNode.path("matchCount").asInt() >= 1);
                boolean containsRoadmap = false;
                for (JsonNode matchNode : semanticNode.path("matches")) {
                        if (matchNode.path("path").asText().contains("docs/roadmap.md")) {
                                containsRoadmap = true;
                                break;
                        }
                }
                assertTrue(containsRoadmap);

        JsonNode contextNode = loadSingleResultNode(contextAction.id());
        assertEquals("payload", contextNode.path("engine").asText());
        assertTrue(contextNode.path("sourceCount").asInt() >= 1);
        assertTrue(contextNode.path("sources").get(0).path("content").asText().contains("streaming support"));
    }

    @Test
    void readFileActionCanExecuteAndSubmitResult() throws Exception {
        Path sourceFile = writeWorkspaceFile("src/demo/MappingService.java", "class MappingService {\n  void map() {}\n}\n");
        String workerId = "repository-read-worker";
        workerService.register(new RegisterWorkerRequest(workerId, List.of("read_file")));

        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("read-file-demo", "repository worker read integration test"));

        String payload = objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "path", workspaceRoot.relativize(sourceFile).toString().replace('\\', '/')));

        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "read_file",
                payload,
                List.of(),
                1,
                1,
                120,
                null
        ));

        Optional<ActionAssignmentResponse> assignment = actionService.pollAction(workerId);
        assertTrue(assignment.isPresent());
        assertEquals("read_file", assignment.get().type());

        WorkerExecutionResult executionResult = newRepositoryHandler().execute(new ActionAssignment(
                assignment.get().actionId(),
                assignment.get().workflowId(),
                assignment.get().type(),
                assignment.get().payload(),
                assignment.get().retryCount(),
                assignment.get().leaseExpireAt()));

        ActionResponse submitted = actionService.submitResult(new SubmitActionResultRequest(
                workerId,
                action.id(),
                executionResult.status(),
                executionResult.result(),
                executionResult.errorMessage()
        ));

        assertEquals(ActionStatus.SUCCEEDED.name(), submitted.status());

        JsonNode resultNode = loadSingleResultNode(action.id());
        assertEquals("src/demo/MappingService.java", resultNode.path("path").asText());
        assertTrue(resultNode.path("content").asText().contains("MappingService"));
        assertEquals(3, resultNode.path("lineCount").asInt());
    }

    @Test
    void loadCodeActionCanExecuteAndSubmitResult() throws Exception {
        Path sourceFile = writeWorkspaceFile("src/demo/SceneRuntime.java", "class SceneRuntime {\n  void tick() {}\n}\n");
        String workerId = "repository-load-code-worker";
        workerService.register(new RegisterWorkerRequest(workerId, List.of("load_code")));

        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("load-code-demo", "repository worker load_code integration test"));

        String payload = objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "paths", List.of(workspaceRoot.relativize(sourceFile).toString().replace('\\', '/')),
                "maxFiles", 1,
                "maxCharsPerFile", 1000));

        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "load_code",
                payload,
                List.of(),
                1,
                1,
                120,
                null
        ));

        Optional<ActionAssignmentResponse> assignment = actionService.pollAction(workerId);
        assertTrue(assignment.isPresent());
        assertEquals("load_code", assignment.get().type());

        WorkerExecutionResult executionResult = newRepositoryHandler().execute(new ActionAssignment(
                assignment.get().actionId(),
                assignment.get().workflowId(),
                assignment.get().type(),
                assignment.get().payload(),
                assignment.get().retryCount(),
                assignment.get().leaseExpireAt()));

        ActionResponse submitted = actionService.submitResult(new SubmitActionResultRequest(
                workerId,
                action.id(),
                executionResult.status(),
                executionResult.result(),
                executionResult.errorMessage()
        ));

        assertEquals(ActionStatus.SUCCEEDED.name(), submitted.status());

        JsonNode resultNode = loadSingleResultNode(action.id());
        assertEquals(1, resultNode.path("loadedFileCount").asInt());
        assertEquals("src/demo/SceneRuntime.java", resultNode.path("files").get(0).path("path").asText());
        assertTrue(resultNode.path("code").asText().contains("class SceneRuntime"));
    }

    @Test
    void repositoryWorkerAndGptWorkerCanExecutePlannerStyleDag() throws Exception {
        Path sourceFile = writeWorkspaceFile(
                "src/demo/ResourceMapper.java",
                "class ResourceMapper {\n  boolean broken = true;\n}\n");

        String repositoryWorkerId = "repository-worker-1";
        String gptWorkerId = "gpt-worker-1";
        workerService.register(new RegisterWorkerRequest(
                repositoryWorkerId,
                List.of("search_code", "read_file", "load_code", "search_semantic", "build_context_pack")));
        workerService.register(new RegisterWorkerRequest(gptWorkerId, List.of("generate_explanation")));

        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("planner-like-dag", "Explain why resource mapping fails"));

        String relativePath = workspaceRoot.relativize(sourceFile).toString().replace('\\', '/');

        ActionResponse searchAction = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "search_semantic",
                objectMapper.writeValueAsString(Map.of(
                        "schemaVersion", "v1",
                        "query", "resource mapping",
                        "topK", 5,
                        "scope", Map.of("paths", List.of(relativePath)))),
                List.of(),
                1,
                1,
                120,
                null
        ));

        ActionResponse analyzeAction = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "build_context_pack",
                objectMapper.writeValueAsString(Map.of(
                        "schemaVersion", "v1",
                        "issue", "Explain why resource mapping fails",
                        "query", "resource mapping",
                        "repo_context", "resource mapping subsystem",
                        "file", relativePath,
                        "maxFiles", 2,
                        "maxCharsPerFile", 1200)),
                List.of(searchAction.id()),
                1,
                1,
                120,
                null
        ));

        ActionResponse explainAction = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "generate_explanation",
                objectMapper.writeValueAsString(Map.of(
                        "schemaVersion", "v1",
                        "issue", "Explain why resource mapping fails",
                        "repo_context", "resource mapping subsystem",
                        "file", relativePath,
                        "module", "resource mapper")),
                List.of(analyzeAction.id()),
                1,
                1,
                120,
                null
        ));

        processAssignment(repositoryWorkerId, newRepositoryHandler(), "search_semantic");
        processAssignment(repositoryWorkerId, newRepositoryHandler(), "build_context_pack");
        processAssignment(gptWorkerId, newGptHandler(), "generate_explanation");

        WorkflowExecutionResponse execution = workflowService.getWorkflowExecution(workflow.id());
        assertEquals("COMPLETED", execution.status());
        assertEquals(3, execution.actions().size());
        assertTrue(execution.actions().stream().allMatch(action -> "COMPLETED".equals(action.status())));

        List<ActionEntity> actions = actionMapper.selectList(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getWorkflowId, workflow.id()));
        assertEquals(List.of(
                ActionStatus.SUCCEEDED.name(),
                ActionStatus.SUCCEEDED.name(),
                ActionStatus.SUCCEEDED.name()),
                actions.stream().map(ActionEntity::getStatus).sorted().toList());

        List<ActionLogEntity> logs = actionLogMapper.selectList(new LambdaQueryWrapper<ActionLogEntity>()
                .eq(ActionLogEntity::getActionId, explainAction.id()));
        assertEquals(1, logs.size());
        JsonNode explanationNode = objectMapper.readTree(logs.get(0).getResult());
        assertTrue(explanationNode.path("content").asText().contains("[MOCK_EXPLANATION]"));
    }

    private void processAssignment(String workerId, Object handler, String expectedType) throws Exception {
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

        WorkerExecutionResult executionResult;
        if (handler instanceof RepositoryWorkerActionHandler repositoryWorkerActionHandler) {
            executionResult = repositoryWorkerActionHandler.execute(actionAssignment);
        } else if (handler instanceof GptWorkerActionHandler gptWorkerActionHandler) {
            executionResult = gptWorkerActionHandler.execute(actionAssignment);
        } else {
            throw new IllegalArgumentException("Unsupported handler type: " + handler);
        }

        assertEquals("SUCCEEDED", executionResult.status());

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
                40,
                65536,
                List.of(".git", ".idea", ".aiflow", "target", "build", "node_modules"),
                5,
                3,
                4000,
                null);
    }

    private GptWorkerActionHandler newGptHandler() {
        GptWorkerProperties.LlmProperties llmProperties = new GptWorkerProperties.LlmProperties();
        llmProperties.setMockFallbackEnabled(true);

        ActionSchemaResolver resolver = new ActionSchemaResolver("schemas/v1");
        ActionSchemaValidator validator = new ActionSchemaValidator(objectMapper, resolver);
        OpenAiCompatibleLlmClient llmClient = new OpenAiCompatibleLlmClient(objectMapper, llmProperties);
        return new GptWorkerActionHandler(objectMapper, llmClient, validator, SchemaValidationMode.STRICT);
    }

    private JsonNode loadSingleResultNode(Long actionId) throws Exception {
        List<ActionLogEntity> logs = actionLogMapper.selectList(new LambdaQueryWrapper<ActionLogEntity>()
                .eq(ActionLogEntity::getActionId, actionId));
        assertEquals(1, logs.size());
        assertEquals(ActionStatus.SUCCEEDED.name(), logs.get(0).getStatus());
        return objectMapper.readTree(logs.get(0).getResult());
    }

    private Path writeWorkspaceFile(String relativePath, String content) throws Exception {
        Path target = workspaceRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        return Files.writeString(target, content);
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