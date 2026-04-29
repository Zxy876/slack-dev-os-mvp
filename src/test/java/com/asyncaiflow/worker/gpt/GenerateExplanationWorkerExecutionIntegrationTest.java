package com.asyncaiflow.worker.gpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.asyncaiflow.web.dto.WorkflowResponse;
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
@Import(GenerateExplanationWorkerExecutionIntegrationTest.QueueTestConfig.class)
class GenerateExplanationWorkerExecutionIntegrationTest {

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
    void generateExplanationActionCanExecuteAndSubmitResult() throws Exception {
        String workerId = "gpt-explanation-worker";
        workerService.register(new RegisterWorkerRequest(workerId, List.of("generate_explanation")));

        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("drift-explanation-demo", "generate explanation integration test"));

        String payload = objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "issue", "Explain how Drift story engine interacts with the Minecraft plugin",
                "repo_context", "DriftSystem backend story routes and Minecraft plugin integration",
                "file", "backend/app/routers/story.py",
                "module", "story engine",
                "gathered_context", Map.of(
                        "plugin_classes", List.of("StoryCreativeManager", "IntentRouter2"),
                        "backend_routes", List.of("backend/app/routers/story.py"))));

        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "generate_explanation",
                payload,
                List.of(),
                1,
                1,
                120,
                null
        ));

        Optional<ActionAssignmentResponse> assignment = actionService.pollAction(workerId);
        assertTrue(assignment.isPresent());
        assertEquals(action.id(), assignment.get().actionId());

        WorkerExecutionResult executionResult = newHandler().execute(new ActionAssignment(
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

        List<ActionLogEntity> logs = actionLogMapper.selectList(new LambdaQueryWrapper<ActionLogEntity>()
                .eq(ActionLogEntity::getActionId, action.id()));

        assertEquals(1, logs.size());
        assertEquals(ActionStatus.SUCCEEDED.name(), logs.get(0).getStatus());

        JsonNode resultNode = objectMapper.readTree(logs.get(0).getResult());
        assertEquals("v1", resultNode.path("schemaVersion").asText());
        assertEquals("gpt-worker", resultNode.path("worker").asText());
        assertEquals("gpt-4.1-mini", resultNode.path("model").asText());
        assertTrue(resultNode.path("summary").asText().contains("[MOCK_EXPLANATION]"));
        assertTrue(resultNode.path("content").asText().contains("[MOCK_EXPLANATION]"));
        assertTrue(resultNode.path("confidence").asDouble() > 0D);
    }

    private GptWorkerActionHandler newHandler() {
        GptWorkerProperties.LlmProperties llmProperties = new GptWorkerProperties.LlmProperties();
        llmProperties.setMockFallbackEnabled(true);

        ActionSchemaResolver resolver = new ActionSchemaResolver("schemas/v1");
        ActionSchemaValidator validator = new ActionSchemaValidator(objectMapper, resolver);
        OpenAiCompatibleLlmClient llmClient = new OpenAiCompatibleLlmClient(objectMapper, llmProperties);
        return new GptWorkerActionHandler(objectMapper, llmClient, validator, SchemaValidationMode.STRICT);
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