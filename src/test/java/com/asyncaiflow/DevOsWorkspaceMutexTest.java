package com.asyncaiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.asyncaiflow.service.DevOsService;
import com.asyncaiflow.service.WorkerService;
import com.asyncaiflow.service.queue.ActionQueueService;
import com.asyncaiflow.web.dto.ActionAssignmentResponse;
import com.asyncaiflow.web.dto.DevOsStartRequest;
import com.asyncaiflow.web.dto.DevOsStartResponse;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;
import com.asyncaiflow.web.dto.SubmitActionResultRequest;

/**
 * B-006 — Stage 5 Workspace Single-Writer Mutex 集成测试
 *
 * 验证 Slack Dev OS 内核的 workspace 级写入互斥机制：
 *   同一 workspaceKey 的写任务（write_intent=true）在同一时间只能有一个进入 RUNNING；
 *   第一个 writer 完成（SUCCEEDED / FAILED / 中断）后才能释放锁，让第二个 writer 执行。
 *
 * 测试场景：
 *   5.1 — 同一 workspaceKey 两个写请求：第一个进入 RUNNING，第二个被阻塞（poll 返回 empty）
 *   5.2 — 第一个 writer SUCCEEDED → 锁释放 → 第二个 writer 可被 poll 进入 RUNNING
 *   5.3 — write_intent=false 的任务不受 workspace 锁限制（read-only action 自由调度）
 *   5.4 — RUNNING writer 被中断（interrupt）→ 锁释放 → 下一个 writer 可进入 RUNNING
 *
 * OS 类比：
 *   workspace 锁 = 系统互斥量（Mutex），保护共享资源（Git repo/branch）的临界区。
 *   write_intent=true = 进入临界区的请求；false = 非互斥只读访问。
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(DevOsWorkspaceMutexTest.QueueTestConfig.class)
class DevOsWorkspaceMutexTest {

    @Autowired
    private DevOsService devOsService;

    @Autowired
    private ActionService actionService;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private ActionMapper actionMapper;

    @Autowired
    private WorkflowMapper workflowMapper;

    @Autowired
    private ActionDependencyMapper actionDependencyMapper;

    @Autowired
    private ActionLogMapper actionLogMapper;

    @Autowired
    private WorkerMapper workerMapper;

    @Autowired
    private ActionQueueService actionQueueService;

    private static final String WORKER_ID = "mutex-test-worker";
    private static final String WORKSPACE_KEY = "repo:/tmp/devos-mutex-repo";

    @BeforeEach
    void cleanTables() {
        actionLogMapper.delete(null);
        actionDependencyMapper.delete(null);
        actionMapper.delete(null);
        workerMapper.delete(null);
        workflowMapper.delete(null);
        ((InMemoryActionQueueService) actionQueueService).clear();

        workerService.register(new RegisterWorkerRequest(
                WORKER_ID,
                List.of("devos_chat")
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 5.1 — 同一 workspaceKey 两个写请求：第二个被阻塞
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 验收条件：
     *   - Action 1（write_intent=true, workspace_key=K）poll → RUNNING，持有互斥锁
     *   - Action 2（write_intent=true, workspace_key=K）poll → empty（锁被 Action 1 持有）
     *   - Action 2 status 仍为 QUEUED（未被修改）
     */
    @Test
    void testSecondWriterBlockedWhileFirstIsRunning() {
        // Action 1: write intent
        DevOsStartResponse resp1 = devOsService.start(new DevOsStartRequest(
                "write to repo: step 1",
                "CMUTEX/1000000001.000001",
                null, null, null,
                true, WORKSPACE_KEY
        ));
        Long action1Id = resp1.actionId();

        // Action 2: same workspace key
        DevOsStartResponse resp2 = devOsService.start(new DevOsStartRequest(
                "write to repo: step 2",
                "CMUTEX/1000000001.000002",
                null, null, null,
                true, WORKSPACE_KEY
        ));
        Long action2Id = resp2.actionId();

        // Worker polls → claims Action 1 (FIFO: action1 was enqueued first)
        Optional<ActionAssignmentResponse> poll1 = actionService.pollAction(WORKER_ID);
        assertTrue(poll1.isPresent(), "First poll must return Action 1");
        assertEquals(action1Id, poll1.get().actionId(), "First poll must be Action 1");

        ActionEntity action1 = actionMapper.selectById(action1Id);
        assertEquals(ActionStatus.RUNNING.name(), action1.getStatus(),
                "Action 1 must be RUNNING — workspace lock acquired");

        // Worker polls again → Action 2 has write_intent → workspace locked → re-enqueue → empty
        Optional<ActionAssignmentResponse> poll2 = actionService.pollAction(WORKER_ID);
        assertFalse(poll2.isPresent(), "Second poll must return empty — workspace is locked by Action 1");

        ActionEntity action2 = actionMapper.selectById(action2Id);
        assertEquals(ActionStatus.QUEUED.name(), action2.getStatus(),
                "Action 2 must remain QUEUED — blocked by workspace mutex");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 5.2 — Writer SUCCEEDED → 锁释放 → 第二个 writer 可被调度
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 验收条件：
     *   - Action 1 poll → RUNNING；submitResult SUCCEEDED → 锁释放
     *   - Action 2 poll → RUNNING（互斥锁已释放）
     */
    @Test
    void testSecondWriterRunsAfterFirstSucceeds() {
        DevOsStartResponse resp1 = devOsService.start(new DevOsStartRequest(
                "write to repo: commit A",
                "CMUTEX/1000000002.000001",
                null, null, null,
                true, WORKSPACE_KEY
        ));
        Long action1Id = resp1.actionId();

        DevOsStartResponse resp2 = devOsService.start(new DevOsStartRequest(
                "write to repo: commit B",
                "CMUTEX/1000000002.000002",
                null, null, null,
                true, WORKSPACE_KEY
        ));
        Long action2Id = resp2.actionId();

        // Worker 1: poll → Action 1 RUNNING
        Optional<ActionAssignmentResponse> poll1 = actionService.pollAction(WORKER_ID);
        assertTrue(poll1.isPresent());
        assertEquals(action1Id, poll1.get().actionId());

        // Action 1 SUCCEEDED → workspace lock released
        actionService.submitResult(new SubmitActionResultRequest(
                WORKER_ID, action1Id, "SUCCEEDED",
                "{\"response\":\"[DEMO] commit A done\",\"notepad\":\"wrote commit A\"}",
                null
        ));

        ActionEntity action1 = actionMapper.selectById(action1Id);
        assertEquals(ActionStatus.SUCCEEDED.name(), action1.getStatus(),
                "Action 1 must be SUCCEEDED");

        // Worker polls again → Action 2 can now acquire workspace lock → RUNNING
        Optional<ActionAssignmentResponse> poll2 = actionService.pollAction(WORKER_ID);
        assertTrue(poll2.isPresent(), "After Action 1 SUCCEEDED, Action 2 must be pollable");
        assertEquals(action2Id, poll2.get().actionId(), "Poll must return Action 2");

        ActionEntity action2 = actionMapper.selectById(action2Id);
        assertEquals(ActionStatus.RUNNING.name(), action2.getStatus(),
                "Action 2 must be RUNNING — workspace lock was released by Action 1");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 5.3 — write_intent=false 不受 workspace 锁限制
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 验收条件：
     *   - Action 1（write_intent=true）持有 workspace 锁，处于 RUNNING
     *   - Action 2（write_intent=false，同 workspace_key）poll → RUNNING（只读不阻塞）
     */
    @Test
    void testReadOnlyActionNotBlockedByWorkspaceLock() {
        // Action 1: writer — takes the workspace lock
        DevOsStartResponse resp1 = devOsService.start(new DevOsStartRequest(
                "write to repo: exclusive",
                "CMUTEX/1000000003.000001",
                null, null, null,
                true, WORKSPACE_KEY
        ));
        Long action1Id = resp1.actionId();

        // Action 2: reader — write_intent=false → no mutex needed
        DevOsStartResponse resp2 = devOsService.start(new DevOsStartRequest(
                "read from repo: read-only check",
                "CMUTEX/1000000003.000002",
                null, null, null,
                false, WORKSPACE_KEY
        ));
        Long action2Id = resp2.actionId();

        // Poll 1: Action 1 enters RUNNING (holds workspace lock)
        Optional<ActionAssignmentResponse> poll1 = actionService.pollAction(WORKER_ID);
        assertTrue(poll1.isPresent(), "First poll must return Action 1 (writer)");
        assertEquals(action1Id, poll1.get().actionId());

        ActionEntity action1 = actionMapper.selectById(action1Id);
        assertEquals(ActionStatus.RUNNING.name(), action1.getStatus(),
                "Action 1 (writer) must be RUNNING");

        // Poll 2: Action 2 (reader) — write_intent=false → bypasses workspace lock → RUNNING
        Optional<ActionAssignmentResponse> poll2 = actionService.pollAction(WORKER_ID);
        assertTrue(poll2.isPresent(), "Read-only action must not be blocked by workspace lock");
        assertEquals(action2Id, poll2.get().actionId(),
                "Poll must return Action 2 (reader)");

        ActionEntity action2 = actionMapper.selectById(action2Id);
        assertEquals(ActionStatus.RUNNING.name(), action2.getStatus(),
                "Action 2 (reader) must be RUNNING — not blocked by writer's workspace lock");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 5.4 — RUNNING writer 被中断 → 锁释放 → 下一个 writer 可调度
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 验收条件：
     *   - Action 1 RUNNING（持有锁）→ interruptAction → FAILED + 锁释放
     *   - Action 2 poll → RUNNING（锁已释放）
     */
    @Test
    void testInterruptRunningWriterReleasesWorkspaceLock() {
        DevOsStartResponse resp1 = devOsService.start(new DevOsStartRequest(
                "write to repo: blocked writer",
                "CMUTEX/1000000004.000001",
                null, null, null,
                true, WORKSPACE_KEY
        ));
        Long action1Id = resp1.actionId();

        DevOsStartResponse resp2 = devOsService.start(new DevOsStartRequest(
                "write to repo: waiting writer",
                "CMUTEX/1000000004.000002",
                null, null, null,
                true, WORKSPACE_KEY
        ));
        Long action2Id = resp2.actionId();

        // Poll 1: Action 1 RUNNING (holds workspace lock)
        Optional<ActionAssignmentResponse> poll1 = actionService.pollAction(WORKER_ID);
        assertTrue(poll1.isPresent());
        assertEquals(action1Id, poll1.get().actionId());

        // Verify Action 2 is blocked
        Optional<ActionAssignmentResponse> pollBlocked = actionService.pollAction(WORKER_ID);
        assertFalse(pollBlocked.isPresent(), "Action 2 must be blocked while Action 1 holds workspace lock");

        // Interrupt Action 1 (user cancels) → FAILED + workspace lock released
        actionService.interruptAction(action1Id, "test interrupt");

        ActionEntity action1 = actionMapper.selectById(action1Id);
        assertEquals(ActionStatus.FAILED.name(), action1.getStatus(),
                "Action 1 must be FAILED after interrupt");

        // Poll again → Action 2 can now acquire workspace lock → RUNNING
        Optional<ActionAssignmentResponse> poll2 = actionService.pollAction(WORKER_ID);
        assertTrue(poll2.isPresent(),
                "After interrupting Action 1, Action 2 must be pollable");
        assertEquals(action2Id, poll2.get().actionId(),
                "Poll must return Action 2 (next writer)");

        ActionEntity action2 = actionMapper.selectById(action2Id);
        assertEquals(ActionStatus.RUNNING.name(), action2.getStatus(),
                "Action 2 must be RUNNING — workspace lock released by interrupted Action 1");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // In-Memory Queue + Workspace Lock（无 Redis 依赖）
    // ─────────────────────────────────────────────────────────────────────────

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
        private final ConcurrentHashMap<String, String> workspaceLocks = new ConcurrentHashMap<>();

        InMemoryActionQueueService() {
            super(new StringRedisTemplate());
        }

        @Override
        public void enqueue(ActionEntity action) {
            enqueue(action, action.getType());
        }

        @Override
        public void enqueue(ActionEntity action, String capability) {
            String cap = (capability == null || capability.isBlank()) ? action.getType() : capability;
            queues.computeIfAbsent(cap, k -> new ConcurrentLinkedDeque<>()).addFirst(action.getId());
        }

        @Override
        public Optional<Long> claimNextAction(List<String> capabilities, String workerId) {
            LocalDateTime now = LocalDateTime.now();
            for (String capability : capabilities) {
                ConcurrentLinkedDeque<Long> queue = queues.get(capability);
                if (queue == null) continue;
                while (true) {
                    Long actionId = queue.pollLast();
                    if (actionId == null) break;
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

        @Override
        public boolean tryAcquireWorkspaceLock(String workspaceKey, String owner, long ttlSeconds) {
            return workspaceLocks.putIfAbsent(workspaceKey, owner) == null;
        }

        @Override
        public void releaseWorkspaceLock(String workspaceKey, String owner) {
            workspaceLocks.remove(workspaceKey, owner);
        }

        public void clear() {
            queues.clear();
            locks.clear();
            workspaceLocks.clear();
        }

        record LeaseState(String workerId, LocalDateTime expireAt) {}
    }
}
