package com.asyncaiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.test.util.ReflectionTestUtils;

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
import com.asyncaiflow.support.ApiException;
import com.asyncaiflow.web.dto.ActionAssignmentResponse;
import com.asyncaiflow.web.dto.ActionResponse;
import com.asyncaiflow.web.dto.CreateActionRequest;
import com.asyncaiflow.web.dto.CreateWorkflowRequest;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;
import com.asyncaiflow.web.dto.SubmitActionResultRequest;
import com.asyncaiflow.web.dto.WorkflowResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(SchedulerReliabilityIntegrationTest.QueueTestConfig.class)
class SchedulerReliabilityIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void pollAssignsLease() {
        registerWorker("worker-poll", List.of("design_solution"));
        WorkflowResponse workflow = createWorkflow("poll-flow");
        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                2,
                1,
                120,
                null
        ));

        Optional<ActionAssignmentResponse> assignment = actionService.pollAction("worker-poll");

        assertTrue(assignment.isPresent());
        ActionEntity updated = actionMapper.selectById(action.id());
        assertEquals(ActionStatus.RUNNING.name(), updated.getStatus());
        assertEquals("worker-poll", updated.getWorkerId());
        assertNotNull(updated.getLeaseExpireAt());
        assertNotNull(updated.getClaimTime());
        assertEquals(action.id(), assignment.get().actionId());
        assertNotNull(updated.getExecutionStartedAt());
    }

    @Test
    void capabilityMismatchWorkerCannotClaimAction() {
        registerWorker("worker-review", List.of("review_code"));
        WorkflowResponse workflow = createWorkflow("capability-mismatch-flow");
        actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                1,
                1,
                120,
                null
        ));

        Optional<ActionAssignmentResponse> assignment = actionService.pollAction("worker-review");
        assertTrue(assignment.isEmpty());
    }

    @Test
    void workersWithSameCapabilityCanCompeteForActions() {
        registerWorker("worker-compete-a", List.of("design_solution"));
        registerWorker("worker-compete-b", List.of("design_solution"));
        WorkflowResponse workflow = createWorkflow("capability-compete-flow");

        ActionResponse action1 = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                1,
                1,
                120,
                null
        ));
        ActionResponse action2 = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                1,
                1,
                120,
                null
        ));

        Optional<ActionAssignmentResponse> first = actionService.pollAction("worker-compete-a");
        Optional<ActionAssignmentResponse> second = actionService.pollAction("worker-compete-b");

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertNotEquals(first.get().actionId(), second.get().actionId());
        assertTrue(first.get().actionId().equals(action1.id()) || first.get().actionId().equals(action2.id()));
        assertTrue(second.get().actionId().equals(action1.id()) || second.get().actionId().equals(action2.id()));
    }

    @Test
    void renewLeaseExtendsLeaseForRunningOwnerAction() {
        registerWorker("worker-renew", List.of("design_solution"));
        WorkflowResponse workflow = createWorkflow("renew-lease-flow");
        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                2,
                1,
                120,
                null
        ));

        ActionEntity running = actionMapper.selectById(action.id());
        LocalDateTime oldLeaseExpireAt = LocalDateTime.now().plusSeconds(15);
        running.setStatus(ActionStatus.RUNNING.name());
        running.setWorkerId("worker-renew");
        running.setLeaseExpireAt(oldLeaseExpireAt);
        running.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(running);

        ActionResponse renewed = actionService.renewLease(action.id(), "worker-renew");

        assertEquals(ActionStatus.RUNNING.name(), renewed.status());
        assertNotNull(renewed.firstRenewTime());
        assertNotNull(renewed.lastRenewTime());
        assertEquals(1, renewed.leaseRenewSuccessCount());
        assertNotNull(renewed.lastLeaseRenewAt());
        ActionEntity reloaded = actionMapper.selectById(action.id());
        assertNotNull(reloaded.getLeaseExpireAt());
        assertTrue(reloaded.getLeaseExpireAt().isAfter(oldLeaseExpireAt));
    }

    @Test
    void renewLeaseRejectedForDifferentWorker() {
        registerWorker("worker-owner", List.of("design_solution"));
        registerWorker("worker-other", List.of("design_solution"));
        WorkflowResponse workflow = createWorkflow("renew-lease-owner-flow");
        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                2,
                1,
                120,
                null
        ));

        ActionEntity running = actionMapper.selectById(action.id());
        running.setStatus(ActionStatus.RUNNING.name());
        running.setWorkerId("worker-owner");
        running.setLeaseExpireAt(LocalDateTime.now().plusSeconds(30));
        running.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(running);

        ApiException exception = assertThrows(ApiException.class,
                () -> actionService.renewLease(action.id(), "worker-other"));
        assertTrue(exception.getMessage().contains("not assigned to worker"));
    }

    @Test
    void renewLeaseRejectedWhenLeaseAlreadyExpired() {
        registerWorker("worker-expired", List.of("design_solution"));
        WorkflowResponse workflow = createWorkflow("renew-lease-expired-flow");
        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                2,
                1,
                120,
                null
        ));

        ActionEntity running = actionMapper.selectById(action.id());
        running.setStatus(ActionStatus.RUNNING.name());
        running.setWorkerId("worker-expired");
        running.setLeaseExpireAt(LocalDateTime.now().minusSeconds(2));
        running.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(running);

        ApiException exception = assertThrows(ApiException.class,
                () -> actionService.renewLease(action.id(), "worker-expired"));
        assertTrue(exception.getMessage().contains("lease already expired"));

        ActionEntity reloaded = actionMapper.selectById(action.id());
        assertEquals(1, reloaded.getLeaseRenewFailureCount());
    }

    @Test
    void expiredLeaseCanBeReclaimed() {
        WorkflowResponse workflow = createWorkflow("reclaim-flow");
        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                2,
                1,
                60
        ,
        null
        ));

        ActionEntity running = actionMapper.selectById(action.id());
        running.setStatus(ActionStatus.RUNNING.name());
        running.setWorkerId("worker-timeout");
        running.setLeaseExpireAt(LocalDateTime.now().minusSeconds(10));
        running.setRetryCount(0);
        running.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(running);

        int reclaimed = actionService.reclaimExpiredLeases();

        assertEquals(1, reclaimed);
        ActionEntity reloaded = actionMapper.selectById(action.id());
        assertEquals(ActionStatus.RETRY_WAIT.name(), reloaded.getStatus());
        assertEquals(1, reloaded.getRetryCount());
        assertNotNull(reloaded.getNextRunAt());
        assertEquals("LEASE_EXPIRED", reloaded.getLastReclaimReason());
        assertNotNull(reloaded.getReclaimTime());
        assertNull(reloaded.getSubmitTime());
    }

    @Test
    void downstreamActionActivatesOnlyAfterUpstreamSuccess() {
        registerWorker("worker-downstream", List.of("design_solution", "generate_code"));
        WorkflowResponse workflow = createWorkflow("dependency-flow");
        ActionResponse upstream = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                1,
                1,
                120,
                null
        ));
        ActionResponse downstream = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "generate_code",
                "{}",
                List.of(upstream.id()),
                1,
                1,
                120,
                null
        ));

        ActionEntity upstreamRunning = actionMapper.selectById(upstream.id());
        upstreamRunning.setStatus(ActionStatus.RUNNING.name());
        upstreamRunning.setWorkerId("worker-downstream");
        upstreamRunning.setLeaseExpireAt(LocalDateTime.now().plusSeconds(60));
        upstreamRunning.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(upstreamRunning);

        actionService.submitResult(new SubmitActionResultRequest(
                "worker-downstream",
                upstream.id(),
                "SUCCEEDED",
                "done",
                null
        ));

        ActionEntity downstreamReloaded = actionMapper.selectById(downstream.id());
        assertEquals(ActionStatus.QUEUED.name(), downstreamReloaded.getStatus());

        Optional<ActionAssignmentResponse> secondAssignment = actionService.pollAction("worker-downstream");
        assertTrue(secondAssignment.isPresent());
        assertEquals(downstream.id(), secondAssignment.get().actionId());
    }

    @Test
    void dispatchAllRunnableActionsQueuesAllReadyFanOutNodes() {
        registerWorker("worker-fanout-context", List.of("build_context_pack"));
        registerWorker("worker-fanout-explain", List.of("generate_explanation"));
        registerWorker("worker-fanout-review", List.of("review_code"));

        WorkflowResponse workflow = createWorkflow("fanout-dispatch-flow");
        ActionResponse upstreamA = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "search_semantic",
            "{}",
            List.of(),
            1,
            1,
            120
        ,
        null
        ));
        ActionResponse upstreamB = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "search_code",
            "{}",
            List.of(),
            1,
            1,
            120
        ,
        null
        ));

        ActionResponse downstreamContext = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "build_context_pack",
            "{}",
            List.of(upstreamA.id(), upstreamB.id()),
            1,
            1,
            120
        ,
        null
        ));
        ActionResponse downstreamExplain = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "generate_explanation",
            "{}",
            List.of(upstreamA.id(), upstreamB.id()),
            1,
            1,
            120
        ,
        null
        ));
        ActionResponse downstreamReview = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "review_code",
            "{}",
            List.of(upstreamA.id(), upstreamB.id()),
            1,
            1,
            120
        ,
        null
        ));

        ActionEntity upstreamAReloaded = actionMapper.selectById(upstreamA.id());
        upstreamAReloaded.setStatus(ActionStatus.SUCCEEDED.name());
        upstreamAReloaded.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(upstreamAReloaded);

        ActionEntity upstreamBReloaded = actionMapper.selectById(upstreamB.id());
        upstreamBReloaded.setStatus(ActionStatus.SUCCEEDED.name());
        upstreamBReloaded.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(upstreamBReloaded);

        int dispatched = actionService.dispatchAllRunnableActions();
        assertEquals(3, dispatched);

        ActionEntity downstreamContextReloaded = actionMapper.selectById(downstreamContext.id());
        ActionEntity downstreamExplainReloaded = actionMapper.selectById(downstreamExplain.id());
        ActionEntity downstreamReviewReloaded = actionMapper.selectById(downstreamReview.id());
        assertEquals(ActionStatus.QUEUED.name(), downstreamContextReloaded.getStatus());
        assertEquals(ActionStatus.QUEUED.name(), downstreamExplainReloaded.getStatus());
        assertEquals(ActionStatus.QUEUED.name(), downstreamReviewReloaded.getStatus());

        Optional<ActionAssignmentResponse> contextAssignment = actionService.pollAction("worker-fanout-context");
        Optional<ActionAssignmentResponse> explainAssignment = actionService.pollAction("worker-fanout-explain");
        Optional<ActionAssignmentResponse> reviewAssignment = actionService.pollAction("worker-fanout-review");

        assertTrue(contextAssignment.isPresent());
        assertTrue(explainAssignment.isPresent());
        assertTrue(reviewAssignment.isPresent());

        List<Long> assignedIds = List.of(
            contextAssignment.get().actionId(),
            explainAssignment.get().actionId(),
            reviewAssignment.get().actionId()
        );
        assertTrue(assignedIds.contains(downstreamContext.id()));
        assertTrue(assignedIds.contains(downstreamExplain.id()));
        assertTrue(assignedIds.contains(downstreamReview.id()));

        int secondDispatch = actionService.dispatchAllRunnableActions();
        assertEquals(0, secondDispatch);
    }

    @Test
    void dispatchRespectsPerWorkflowParallelLimit() {
        registerWorker("worker-limit", List.of("generate_explanation"));

        WorkflowResponse workflow = createWorkflow("limit-dispatch-flow");
        ActionResponse upstream = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "search_semantic",
            "{}",
            List.of(),
            1,
            1,
            120
        ,
        null
        ));

        ActionResponse downstreamOne = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "generate_explanation",
            "{}",
            List.of(upstream.id()),
            1,
            1,
            120
        ,
        null
        ));
        ActionResponse downstreamTwo = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "generate_explanation",
            "{}",
            List.of(upstream.id()),
            1,
            1,
            120
        ,
        null
        ));
        ActionResponse downstreamThree = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "generate_explanation",
            "{}",
            List.of(upstream.id()),
            1,
            1,
            120
        ,
        null
        ));

        ActionEntity upstreamReloaded = actionMapper.selectById(upstream.id());
        upstreamReloaded.setStatus(ActionStatus.SUCCEEDED.name());
        upstreamReloaded.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(upstreamReloaded);

        ReflectionTestUtils.setField(actionService, "maxParallelActionsPerWorkflow", 2);
        try {
            int firstDispatch = actionService.dispatchAllRunnableActions();
            assertEquals(2, firstDispatch);

            List<ActionEntity> firstStates = List.of(
                actionMapper.selectById(downstreamOne.id()),
                actionMapper.selectById(downstreamTwo.id()),
                actionMapper.selectById(downstreamThree.id())
            );
            long queuedCount = firstStates.stream()
                .filter(action -> ActionStatus.QUEUED.name().equals(action.getStatus()))
                .count();
            long blockedCount = firstStates.stream()
                .filter(action -> ActionStatus.BLOCKED.name().equals(action.getStatus()))
                .count();
            assertEquals(2, queuedCount);
            assertEquals(1, blockedCount);

            Optional<ActionAssignmentResponse> firstAssignment = actionService.pollAction("worker-limit");
            Optional<ActionAssignmentResponse> secondAssignment = actionService.pollAction("worker-limit");
            assertTrue(firstAssignment.isPresent());
            assertTrue(secondAssignment.isPresent());

            actionService.submitResult(new SubmitActionResultRequest(
                "worker-limit",
                firstAssignment.get().actionId(),
                "SUCCEEDED",
                "ok",
                null
            ));

            int secondDispatch = actionService.dispatchAllRunnableActions();
            assertEquals(1, secondDispatch);

            List<ActionEntity> secondStates = List.of(
                actionMapper.selectById(downstreamOne.id()),
                actionMapper.selectById(downstreamTwo.id()),
                actionMapper.selectById(downstreamThree.id())
            );
            long blockedAfterSecondDispatch = secondStates.stream()
                .filter(action -> ActionStatus.BLOCKED.name().equals(action.getStatus()))
                .count();
            long inflightAfterSecondDispatch = secondStates.stream()
                .filter(action -> ActionStatus.QUEUED.name().equals(action.getStatus())
                    || ActionStatus.RUNNING.name().equals(action.getStatus()))
                .count();
            assertEquals(0, blockedAfterSecondDispatch);
            assertEquals(2, inflightAfterSecondDispatch);
        } finally {
            ReflectionTestUtils.setField(actionService, "maxParallelActionsPerWorkflow", 0);
        }
    }

    @Test
    void pollMaterializesInjectPayloadFromUpstreamResults() throws Exception {
        registerWorker("worker-semantic", List.of("search_semantic"));
        registerWorker("worker-context", List.of("build_context_pack"));
        WorkflowResponse workflow = createWorkflow("injection-flow");

        ActionResponse upstream = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "search_semantic",
                objectMapper.writeValueAsString(java.util.Map.of(
                        "schemaVersion", "v1",
                        "query", "resource mapping"
                )),
                List.of(),
                1,
                1,
                120,
                null
        ));

        ActionResponse downstream = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "build_context_pack",
                objectMapper.writeValueAsString(java.util.Map.of(
                        "schemaVersion", "v1",
                        "issue", "Explain resource mapping flow",
                        "query", "resource mapping",
                        "inject", java.util.Map.of(
                                "retrievalResults", "$upstream[0].result.matches"
                        )
                )),
                List.of(upstream.id()),
                1,
                1,
                120,
                null
        ));

        ActionEntity upstreamRunning = actionMapper.selectById(upstream.id());
        upstreamRunning.setStatus(ActionStatus.RUNNING.name());
        upstreamRunning.setWorkerId("worker-semantic");
        upstreamRunning.setLeaseExpireAt(LocalDateTime.now().plusSeconds(60));
        upstreamRunning.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(upstreamRunning);

        String upstreamResult = objectMapper.writeValueAsString(java.util.Map.of(
                "schemaVersion", "v1",
                "worker", "repository-worker",
                "query", "resource mapping",
                "engine", "local_fallback",
                "matchCount", 1,
                "matches", java.util.List.of(java.util.Map.of(
                        "path", "docs/architecture.md",
                        "score", 0.92,
                        "chunk", "resource mapping behavior",
                        "lineNumber", 12,
                        "source", "local_semantic"
                ))
        ));

        actionService.submitResult(new SubmitActionResultRequest(
                "worker-semantic",
                upstream.id(),
                "SUCCEEDED",
                upstreamResult,
                null
        ));

        Optional<ActionAssignmentResponse> assignment = actionService.pollAction("worker-context");
        assertTrue(assignment.isPresent());
        assertEquals(downstream.id(), assignment.get().actionId());

        JsonNode payload = objectMapper.readTree(assignment.get().payload());
        assertTrue(payload.path("inject").isMissingNode());
        assertEquals(1, payload.path("retrievalResults").size());
        assertEquals("docs/architecture.md", payload.path("retrievalResults").get(0).path("path").asText());

        ActionEntity storedDownstream = actionMapper.selectById(downstream.id());
        JsonNode storedPayload = objectMapper.readTree(storedDownstream.getPayload());
        assertTrue(storedPayload.path("inject").isObject());
    }

    @Test
    void pollMaterializesInjectPayloadUsingFallbackFromExpression() throws Exception {
        registerWorker("worker-semantic-fallback", List.of("search_semantic"));
        registerWorker("worker-review-fallback", List.of("review_code"));
        WorkflowResponse workflow = createWorkflow("injection-fallback-flow");

        ActionResponse upstream = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "search_semantic",
            objectMapper.writeValueAsString(java.util.Map.of(
                "schemaVersion", "v1",
                "query", "scene runtime"
            )),
            List.of(),
            1,
            1,
            120
        ,
        null
        ));

        ActionResponse downstream = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            "review_code",
            objectMapper.writeValueAsString(java.util.Map.of(
                "schemaVersion", "v1",
                "focus", "runtime abstraction",
                "inject", java.util.Map.of(
                    "code", java.util.Map.of(
                        "from", "$upstream[0].result.sources[0].content",
                        "fallbackFrom", java.util.List.of("$upstream[0].result.retrieval[0].chunk")
                    )
                )
            )),
            List.of(upstream.id()),
            1,
            1,
            120
        ,
        null
        ));

        ActionEntity upstreamRunning = actionMapper.selectById(upstream.id());
        upstreamRunning.setStatus(ActionStatus.RUNNING.name());
        upstreamRunning.setWorkerId("worker-semantic-fallback");
        upstreamRunning.setLeaseExpireAt(LocalDateTime.now().plusSeconds(60));
        upstreamRunning.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(upstreamRunning);

        String upstreamResult = objectMapper.writeValueAsString(java.util.Map.of(
            "schemaVersion", "v1",
            "worker", "repository-worker",
            "query", "scene runtime",
            "engine", "local_fallback",
            "matchCount", 1,
            "retrieval", java.util.List.of(java.util.Map.of(
                "path", "plugin/src/main/java/com/driftmc/story/SceneRuntime.java",
                "score", 0.93,
                "chunk", "public final class SceneRuntime {"
            ))
        ));

        actionService.submitResult(new SubmitActionResultRequest(
            "worker-semantic-fallback",
            upstream.id(),
            "SUCCEEDED",
            upstreamResult,
            null
        ));

        Optional<ActionAssignmentResponse> assignment = actionService.pollAction("worker-review-fallback");
        assertTrue(assignment.isPresent());
        assertEquals(downstream.id(), assignment.get().actionId());

        JsonNode payload = objectMapper.readTree(assignment.get().payload());
        assertTrue(payload.path("inject").isMissingNode());
        assertEquals("public final class SceneRuntime {", payload.path("code").asText());
    }

    @Test
    void retryCountIncrementsCorrectly() {
        registerWorker("worker-retry", List.of("design_solution"));
        WorkflowResponse workflow = createWorkflow("retry-flow");
        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                2,
                1,
                120,
                null
        ));

        ActionEntity firstRun = actionMapper.selectById(action.id());
        firstRun.setStatus(ActionStatus.RUNNING.name());
        firstRun.setWorkerId("worker-retry");
        firstRun.setLeaseExpireAt(LocalDateTime.now().plusSeconds(60));
        firstRun.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(firstRun);

        actionService.submitResult(new SubmitActionResultRequest(
                "worker-retry",
                action.id(),
                "FAILED",
                "failed once",
                "first failure"
        ));

        ActionEntity afterFirstFail = actionMapper.selectById(action.id());
        assertEquals(1, afterFirstFail.getRetryCount());
        assertEquals(ActionStatus.RETRY_WAIT.name(), afterFirstFail.getStatus());

        afterFirstFail.setStatus(ActionStatus.RUNNING.name());
        afterFirstFail.setWorkerId("worker-retry");
        afterFirstFail.setLeaseExpireAt(LocalDateTime.now().plusSeconds(60));
        afterFirstFail.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(afterFirstFail);

        actionService.submitResult(new SubmitActionResultRequest(
                "worker-retry",
                action.id(),
                "FAILED",
                "failed twice",
                "second failure"
        ));

        ActionEntity afterSecondFail = actionMapper.selectById(action.id());
        assertEquals(2, afterSecondFail.getRetryCount());
        assertEquals(ActionStatus.RETRY_WAIT.name(), afterSecondFail.getStatus());
    }

    @Test
    void duplicateResultSubmissionIsSafelyHandled() {
        registerWorker("worker-idempotent", List.of("design_solution"));
        WorkflowResponse workflow = createWorkflow("idempotent-flow");
        ActionResponse action = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "design_solution",
                "{}",
                List.of(),
                1,
                1,
                120,
                null
        ));

        ActionEntity running = actionMapper.selectById(action.id());
        running.setStatus(ActionStatus.RUNNING.name());
        running.setWorkerId("worker-idempotent");
        running.setLeaseExpireAt(LocalDateTime.now().plusSeconds(60));
        running.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(running);

        actionService.submitResult(new SubmitActionResultRequest(
                "worker-idempotent",
                action.id(),
                "SUCCEEDED",
                "first submit",
                null
        ));

        ActionResponse secondResponse = actionService.submitResult(new SubmitActionResultRequest(
                "worker-idempotent",
                action.id(),
                "SUCCEEDED",
                "duplicate submit",
                null
        ));

        ActionEntity finalState = actionMapper.selectById(action.id());
        List<ActionLogEntity> logs = actionLogMapper.selectList(new LambdaQueryWrapper<ActionLogEntity>()
                .eq(ActionLogEntity::getActionId, action.id()));

        assertEquals(ActionStatus.SUCCEEDED.name(), secondResponse.status());
        assertEquals(ActionStatus.SUCCEEDED.name(), finalState.getStatus());
        assertNotNull(finalState.getSubmitTime());
        assertNotNull(finalState.getLastExecutionDurationMs());
        assertEquals(1, logs.size());
    }

    private WorkflowResponse createWorkflow(String name) {
        return workflowService.createWorkflow(new CreateWorkflowRequest(name, "test workflow"));
    }

    private void registerWorker(String workerId, List<String> capabilities) {
        workerService.register(new RegisterWorkerRequest(workerId, capabilities));
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
            // no-op for test in-memory queue
        }

        void clear() {
            queues.clear();
            locks.clear();
        }

        private record LeaseState(String owner, LocalDateTime expireAt) {
        }
    }
}
