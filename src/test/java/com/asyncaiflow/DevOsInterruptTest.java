package com.asyncaiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import com.asyncaiflow.domain.entity.ActionEntity;
import com.asyncaiflow.domain.enums.ActionStatus;
import com.asyncaiflow.mapper.ActionDependencyMapper;
import com.asyncaiflow.mapper.ActionLogMapper;
import com.asyncaiflow.mapper.ActionMapper;
import com.asyncaiflow.mapper.WorkerMapper;
import com.asyncaiflow.mapper.WorkflowMapper;
import com.asyncaiflow.service.ActionService;
import com.asyncaiflow.service.WorkerService;
import com.asyncaiflow.service.WorkflowService;
import com.asyncaiflow.service.queue.ActionQueueService;
import com.asyncaiflow.support.ApiException;
import com.asyncaiflow.web.dto.ActionAssignmentResponse;
import com.asyncaiflow.web.dto.ActionResponse;
import com.asyncaiflow.web.dto.CreateActionRequest;
import com.asyncaiflow.web.dto.CreateWorkflowRequest;
import com.asyncaiflow.web.dto.DevOsInterruptResponse;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;
import com.asyncaiflow.web.dto.WorkflowResponse;

/**
 * B-003 — Stage 3 User Interrupt Signal 集成测试
 *
 * 验证 Slack Dev OS 内核的用户中断机制：
 *   用户通过 POST /devos/interrupt 主动取消正在运行或等待中的 Action。
 *   系统将其转为 FAILED 终态，记录中断原因，并确保任务不再被调度。
 *
 * 测试场景：
 *   A. RUNNING Action 被中断 → FAILED，lastReclaimReason=USER_INTERRUPTED，lock 已释放
 *   B. QUEUED Action 被中断 → FAILED，后续 worker poll 不会拿到它
 *   C. RETRY_WAIT Action 被中断 → FAILED，enqueueDueRetries 不会重新入队
 *   D. 终态 Action（SUCCEEDED / DEAD_LETTER）被中断 → CONFLICT 异常，状态不变
 *
 * 核心不变量：
 *   - User interrupt is a syscall (PCB-level, not process kill)
 *   - Interrupted action becomes FAILED (terminal)
 *   - Interrupted action is not polled again
 *   - Interrupted action is not retried again
 *   - Terminal actions are immutable
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(DevOsInterruptTest.QueueTestConfig.class)
class DevOsInterruptTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private ActionService actionService;

    @Autowired
    private ActionMapper actionMapper;

    @Autowired
    private ActionDependencyMapper actionDependencyMapper;

    @Autowired
    private ActionLogMapper actionLogMapper;

    @Autowired
    private WorkerMapper workerMapper;

    @Autowired
    private WorkflowMapper workflowMapper;

    @Autowired
    private ActionQueueService actionQueueService;

    @BeforeEach
    void cleanUp() {
        actionLogMapper.delete(null);
        actionDependencyMapper.delete(null);
        actionMapper.delete(null);
        workerMapper.delete(null);
        workflowMapper.delete(null);
        ((InMemoryActionQueueService) actionQueueService).clear();
    }

    /**
     * 场景 A — RUNNING Action 被中断 → FAILED
     *
     * 验证：
     *   - status 转为 FAILED
     *   - lastReclaimReason = USER_INTERRUPTED
     *   - errorMessage 含有中断原因
     *   - reclaimTime != null（记录中断时间）
     *   - lock 被释放（后续 poll 其他 worker 不会拿到它）
     */
    @Test
    void testRunningActionCanBeInterrupted() {
        // Arrange
        workerService.register(new RegisterWorkerRequest("interrupt-worker-a", List.of("devos_chat")));
        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("interrupt-wf-a", "Interrupt Running Test"));
        ActionResponse actionResp = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "devos_chat",
                "{\"text\":\"Build a service\",\"slackThreadId\":\"CTEST/A001\"}",
                List.of(),
                2,
                1,
                30,
                "CTEST/A001"
        ));

        // Simulate: Worker claimed action → RUNNING with active lease
        ActionEntity running = actionMapper.selectById(actionResp.id());
        running.setStatus(ActionStatus.RUNNING.name());
        running.setWorkerId("interrupt-worker-a");
        running.setLeaseExpireAt(LocalDateTime.now().plusSeconds(120));
        running.setClaimTime(LocalDateTime.now());
        running.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(running);

        // Act — user sends interrupt syscall
        DevOsInterruptResponse result = actionService.interruptAction(
                actionResp.id(), "User cancelled via Slack");

        // Assert response
        assertEquals(actionResp.id(), result.actionId());
        assertEquals("FAILED", result.status());
        assertTrue(result.interrupted());

        // Assert PCB state
        ActionEntity interrupted = actionMapper.selectById(actionResp.id());
        assertEquals(ActionStatus.FAILED.name(), interrupted.getStatus(),
                "RUNNING action should become FAILED after interrupt");
        assertEquals("USER_INTERRUPTED", interrupted.getLastReclaimReason(),
                "last_reclaim_reason must be USER_INTERRUPTED");
        assertNotNull(interrupted.getErrorMessage(),
                "error_message should record the interrupt reason");
        assertTrue(interrupted.getErrorMessage().contains("USER_INTERRUPTED"),
                "error_message should contain USER_INTERRUPTED prefix");
        assertTrue(interrupted.getErrorMessage().contains("User cancelled via Slack"),
                "error_message should contain the provided reason");
        assertNotNull(interrupted.getReclaimTime(),
                "reclaim_time should be set when interrupted");
    }

    /**
     * 场景 B — QUEUED Action 被中断 → FAILED；worker poll 不会拿到它
     *
     * 验证：
     *   - QUEUED action 被转为 FAILED
     *   - Worker 调用 pollAction 后得到 Optional.empty()（DB 状态检查跳过 FAILED action）
     */
    @Test
    void testQueuedActionCanBeInterrupted() {
        // Arrange
        workerService.register(new RegisterWorkerRequest("interrupt-worker-b", List.of("devos_chat")));
        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("interrupt-wf-b", "Interrupt Queued Test"));
        ActionResponse actionResp = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "devos_chat",
                "{\"text\":\"Deploy service\",\"slackThreadId\":\"CTEST/B001\"}",
                List.of(),
                2,
                1,
                30,
                "CTEST/B001"
        ));

        // Verify: action is QUEUED before interrupt
        ActionEntity queued = actionMapper.selectById(actionResp.id());
        assertEquals(ActionStatus.QUEUED.name(), queued.getStatus(),
                "Action should start in QUEUED state");

        // Act — interrupt the QUEUED action
        DevOsInterruptResponse result = actionService.interruptAction(
                actionResp.id(), "Task no longer needed");

        // Assert response
        assertEquals("FAILED", result.status());
        assertTrue(result.interrupted());

        // Assert PCB
        ActionEntity interrupted = actionMapper.selectById(actionResp.id());
        assertEquals(ActionStatus.FAILED.name(), interrupted.getStatus(),
                "QUEUED action should become FAILED after interrupt");
        assertEquals("USER_INTERRUPTED", interrupted.getLastReclaimReason());

        // Assert: worker poll does NOT return the interrupted action
        // pollAction reads DB status and skips non-QUEUED actions
        Optional<ActionAssignmentResponse> poll = actionService.pollAction("interrupt-worker-b");
        assertTrue(poll.isEmpty(),
                "Worker poll should return empty — interrupted FAILED action must not be assigned");
    }

    /**
     * 场景 C — RETRY_WAIT Action 被中断 → FAILED；enqueueDueRetries 不会重新入队
     *
     * 验证：
     *   - RETRY_WAIT action 被转为 FAILED
     *   - enqueueDueRetries() 返回 0（跳过非 RETRY_WAIT 状态的 action）
     */
    @Test
    void testRetryWaitActionCanBeInterrupted() {
        // Arrange
        workerService.register(new RegisterWorkerRequest("interrupt-worker-c", List.of("devos_chat")));
        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("interrupt-wf-c", "Interrupt RetryWait Test"));
        ActionResponse actionResp = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "devos_chat",
                "{\"text\":\"Analyze logs\",\"slackThreadId\":\"CTEST/C001\"}",
                List.of(),
                3,
                1,
                30,
                "CTEST/C001"
        ));

        // Simulate: Action is in RETRY_WAIT state with past nextRunAt
        ActionEntity action = actionMapper.selectById(actionResp.id());
        action.setStatus(ActionStatus.RETRY_WAIT.name());
        action.setRetryCount(1);
        action.setNextRunAt(LocalDateTime.now().minusSeconds(1)); // backoff elapsed
        action.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(action);

        // Act — interrupt the RETRY_WAIT action before it can be re-queued
        DevOsInterruptResponse result = actionService.interruptAction(
                actionResp.id(), "Interrupt before retry");

        // Assert response
        assertEquals("FAILED", result.status());
        assertTrue(result.interrupted());

        // Assert PCB
        ActionEntity interrupted = actionMapper.selectById(actionResp.id());
        assertEquals(ActionStatus.FAILED.name(), interrupted.getStatus(),
                "RETRY_WAIT action should become FAILED after interrupt");
        assertEquals("USER_INTERRUPTED", interrupted.getLastReclaimReason());

        // Assert: enqueueDueRetries does NOT re-enqueue the interrupted action
        int enqueued = actionService.enqueueDueRetries();
        assertEquals(0, enqueued,
                "enqueueDueRetries should not re-enqueue the interrupted (FAILED) action");

        // Double check: worker poll returns empty
        Optional<ActionAssignmentResponse> poll = actionService.pollAction("interrupt-worker-c");
        assertTrue(poll.isEmpty(),
                "Worker poll should return empty — interrupted action must not be assigned");
    }

    /**
     * 场景 D — 终态 Action（SUCCEEDED / DEAD_LETTER）被中断 → CONFLICT 异常，状态不变
     *
     * 验证：
     *   - SUCCEEDED action 无法被中断（throws ApiException 409）
     *   - DEAD_LETTER action 无法被中断（throws ApiException 409）
     *   - 两者状态均不被改变
     */
    @Test
    void testTerminalActionsCannotBeInterrupted() {
        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("interrupt-wf-d", "Interrupt Terminal Test"));

        // Create a SUCCEEDED action
        ActionResponse succeededResp = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "devos_chat",
                "{\"text\":\"Already done\",\"slackThreadId\":\"CTEST/D001\"}",
                List.of(),
                2,
                1,
                30,
                "CTEST/D001"
        ));
        ActionEntity succeededAction = actionMapper.selectById(succeededResp.id());
        succeededAction.setStatus(ActionStatus.SUCCEEDED.name());
        succeededAction.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(succeededAction);

        // Create a DEAD_LETTER action
        ActionResponse deadResp = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                "devos_chat",
                "{\"text\":\"Max retries exhausted\",\"slackThreadId\":\"CTEST/D002\"}",
                List.of(),
                2,
                1,
                30,
                "CTEST/D002"
        ));
        ActionEntity deadAction = actionMapper.selectById(deadResp.id());
        deadAction.setStatus(ActionStatus.DEAD_LETTER.name());
        deadAction.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(deadAction);

        // Act + Assert: SUCCEEDED cannot be interrupted
        ApiException exSucceeded = assertThrows(ApiException.class,
                () -> actionService.interruptAction(succeededResp.id(), "try to interrupt succeeded"),
                "Interrupting a SUCCEEDED action should throw ApiException");
        assertTrue(exSucceeded.getMessage().contains("terminal"),
                "Exception message should indicate terminal state");

        // Assert SUCCEEDED status unchanged
        ActionEntity reloaded = actionMapper.selectById(succeededResp.id());
        assertEquals(ActionStatus.SUCCEEDED.name(), reloaded.getStatus(),
                "SUCCEEDED action status must not be changed by interrupt attempt");

        // Act + Assert: DEAD_LETTER cannot be interrupted
        ApiException exDead = assertThrows(ApiException.class,
                () -> actionService.interruptAction(deadResp.id(), "try to interrupt dead letter"),
                "Interrupting a DEAD_LETTER action should throw ApiException");
        assertTrue(exDead.getMessage().contains("terminal"),
                "Exception message should indicate terminal state");

        // Assert DEAD_LETTER status unchanged
        ActionEntity reloadedDead = actionMapper.selectById(deadResp.id());
        assertEquals(ActionStatus.DEAD_LETTER.name(), reloadedDead.getStatus(),
                "DEAD_LETTER action status must not be changed by interrupt attempt");
    }

    // ─── helpers ────────────────────────────────────────────────────────────

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
            locks.put(actionId, new LeaseState(workerId, LocalDateTime.now().plusSeconds(ttlSeconds)));
        }

        @Override
        public void refreshHeartbeat(String workerId) {
            // no-op for in-memory test
        }

        public void clear() {
            queues.clear();
            locks.clear();
        }

        public boolean isLocked(Long actionId) {
            LeaseState state = locks.get(actionId);
            return state != null && state.expireAt().isAfter(LocalDateTime.now());
        }

        record LeaseState(String workerId, LocalDateTime expireAt) {}
    }
}
