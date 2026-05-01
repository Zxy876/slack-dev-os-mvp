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
import org.springframework.http.HttpStatus;
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
import com.asyncaiflow.support.ApiException;
import com.asyncaiflow.web.dto.ActionAssignmentResponse;
import com.asyncaiflow.web.dto.DevOsInterruptRequest;
import com.asyncaiflow.web.dto.DevOsInterruptResponse;
import com.asyncaiflow.web.dto.DevOsStartRequest;
import com.asyncaiflow.web.dto.DevOsStartResponse;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;
import com.asyncaiflow.web.dto.SubmitActionResultRequest;

/**
 * B-007 — Stage 6 Access Control / Ownership Guard 集成测试
 *
 * 验证 Slack Dev OS 内核的最小 ownership guard：
 *   slackThreadId 是 MVP resource scope boundary。
 *   跨 thread 的危险操作（context steal / unauthorized interrupt）必须被拒绝。
 *
 * 测试场景：
 *   A. 同一 slackThreadId 的 prevActionId 可以继承 notepad_ref（正向验证）
 *   B. 不同 slackThreadId 的 prevActionId 被拒绝（403），notepad 不泄漏（安全验证）
 *   C. 同一 slackThreadId 可以 interrupt 自己 thread 的 Action（正向验证）
 *   D. 不同 slackThreadId interrupt 被拒绝（403），目标 Action 状态不变（安全验证）
 *
 * 核心不变量：
 *   - Context Restore 不得跨 thread 泄漏 notepad
 *   - Interrupt 不得跨 thread 操作 Action
 *   - 终态保护仍然有效（由 ActionService 层保证，B-003 已覆盖）
 *   - 拒绝时必须返回 403 FORBIDDEN，目标资源状态不变
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(DevOsAccessControlTest.QueueTestConfig.class)
class DevOsAccessControlTest {

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

    private static final String WORKER_ID = "ac-test-worker";
    private static final String THREAD_ALPHA = "T-AC-ALPHA/5000000000.000001";
    private static final String THREAD_BETA  = "T-AC-BETA/5000000000.000002";

    @BeforeEach
    void cleanTables() {
        actionLogMapper.delete(null);
        actionDependencyMapper.delete(null);
        actionMapper.delete(null);
        workerMapper.delete(null);
        workflowMapper.delete(null);
        ((InMemoryActionQueueService) actionQueueService).clear();

        workerService.register(new RegisterWorkerRequest(WORKER_ID, List.of("devos_chat")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario A — 同一 slackThreadId：notepad 继承被允许
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 验收条件：
     *   - Action 1 (THREAD_ALPHA) 完成并写入 notepad_ref
     *   - Action 2 以 prevActionId=action1Id 且 slackThreadId=THREAD_ALPHA 请求
     *   - DevOsService.resolveNotepadRef 通过 ownership 校验，成功继承 notepad_ref
     *   - action2.notepad_ref == action1.notepad_ref
     */
    @Test
    void testSameThreadNotepadInheritanceAllowed() throws Exception {
        // ── Round 1: Action 1 (ALPHA thread) ──
        DevOsStartResponse resp1 = devOsService.start(new DevOsStartRequest(
                "alpha step 1 — design system",
                THREAD_ALPHA,
                null, null, null, null, null, null, null, null
        ));
        Long action1Id = resp1.actionId();

        // Worker claims Action 1
        Optional<ActionAssignmentResponse> poll1 = actionService.pollAction(WORKER_ID);
        assertTrue(poll1.isPresent(), "Worker should poll Action 1");
        assertEquals(action1Id, poll1.get().actionId());

        // Worker submits SUCCEEDED with notepad
        String resultJson = "{\"response\":\"[DEMO] design done\","
                + "\"notepad\":\"[action:" + action1Id + "] alpha step 1 → design done\"}";
        actionService.submitResult(new SubmitActionResultRequest(
                WORKER_ID, action1Id, "SUCCEEDED", resultJson, null));

        ActionEntity action1 = actionMapper.selectById(action1Id);
        assertNotNull(action1.getNotepadRef(), "action1.notepad_ref must be persisted after SUCCEEDED");

        // ── Round 2: Action 2 (same ALPHA thread, prevActionId = action1) ──
        DevOsStartResponse resp2 = devOsService.start(new DevOsStartRequest(
                "alpha step 2 — implement",
                THREAD_ALPHA,
                action1Id, null, null, null, null, null, null, null // same-thread context restore, null, null, null
        ));

        ActionEntity action2 = actionMapper.selectById(resp2.actionId());
        assertNotNull(action2.getNotepadRef(),
                "action2.notepad_ref must be inherited from action1 (same thread)");
        assertEquals(action1.getNotepadRef(), action2.getNotepadRef(),
                "Same-thread context restore must propagate notepad_ref");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario B — 不同 slackThreadId：prevActionId 跨 thread 被拒绝（403）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 验收条件：
     *   - Action A 属于 THREAD_ALPHA，notepad_ref = "alpha-secret"
     *   - THREAD_BETA 请求 prevActionId = alphaActionId → 403 FORBIDDEN
     *   - 异常后 THREAD_ALPHA 的 notepad_ref 未被修改（未泄漏）
     *   - 未创建新的 THREAD_BETA Action（事务回滚）
     */
    @Test
    void testCrossThreadNotepadInheritanceRejected() {
        // Create ALPHA action and set its notepad directly
        DevOsStartResponse respAlpha = devOsService.start(new DevOsStartRequest(
                "alpha task — contains secrets",
                THREAD_ALPHA,
                null, null, null, null, null, null, null, null
        ));
        Long alphaActionId = respAlpha.actionId();

        ActionEntity alphaAction = actionMapper.selectById(alphaActionId);
        alphaAction.setNotepadRef("alpha-secret-notepad-must-not-leak");
        actionMapper.updateById(alphaAction);

        long actionCountBefore = actionMapper.selectCount(null);

        // BETA tries to steal ALPHA's context via prevActionId
        ApiException ex = assertThrows(ApiException.class, () ->
                devOsService.start(new DevOsStartRequest(
                        "beta task attempting to steal alpha context",
                        THREAD_BETA,
                        alphaActionId, null, null, null, null, null, null, null // cross-thread prevActionId!, null, null, null
                )),
                "Cross-thread prevActionId must throw ApiException (403)"
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus(),
                "Cross-thread context restore must return 403 FORBIDDEN");

        // No new action was created (transaction rolled back)
        long actionCountAfter = actionMapper.selectCount(null);
        assertEquals(actionCountBefore, actionCountAfter,
                "Transaction must roll back — no new action should be created on cross-thread rejection");

        // ALPHA notepad must be intact (not leaked, not modified)
        ActionEntity alphaReloaded = actionMapper.selectById(alphaActionId);
        assertEquals("alpha-secret-notepad-must-not-leak", alphaReloaded.getNotepadRef(),
                "ALPHA notepad must not be modified by BETA's rejected cross-thread attempt");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario C — 同一 slackThreadId：可以中断自己 thread 的 Action
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 验收条件：
     *   - Action 属于 THREAD_ALPHA，状态 QUEUED
     *   - devOsService.interrupt 携带 slackThreadId=THREAD_ALPHA → 成功中断
     *   - Action 状态变为 FAILED，interrupted=true
     */
    @Test
    void testSameThreadInterruptAllowed() {
        // Create ALPHA action (QUEUED)
        DevOsStartResponse resp = devOsService.start(new DevOsStartRequest(
                "alpha task to cancel",
                THREAD_ALPHA,
                null, null, null, null, null, null, null, null
        ));
        Long actionId = resp.actionId();

        // Interrupt via devOsService (ownership: same thread)
        DevOsInterruptResponse result = devOsService.interrupt(
                new DevOsInterruptRequest(actionId, THREAD_ALPHA, "owner cancelled task"));

        assertEquals(actionId, result.actionId());
        assertEquals("FAILED", result.status());
        assertTrue(result.interrupted(),
                "Same-thread interrupt must succeed");

        ActionEntity action = actionMapper.selectById(actionId);
        assertEquals(ActionStatus.FAILED.name(), action.getStatus(),
                "Action must be FAILED after same-thread interrupt");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario D — 不同 slackThreadId：跨 thread interrupt 被拒绝（403），目标状态不变
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 验收条件：
     *   - Action 属于 THREAD_ALPHA，状态 QUEUED
     *   - devOsService.interrupt 携带 slackThreadId=THREAD_BETA → 403 FORBIDDEN
     *   - 目标 Action 状态仍为 QUEUED（未被修改）
     */
    @Test
    void testCrossThreadInterruptRejected() {
        // Create ALPHA action (QUEUED)
        DevOsStartResponse resp = devOsService.start(new DevOsStartRequest(
                "alpha task — should not be interruptible by beta",
                THREAD_ALPHA,
                null, null, null, null, null, null, null, null
        ));
        Long actionId = resp.actionId();

        // BETA tries to interrupt ALPHA's action
        ApiException ex = assertThrows(ApiException.class, () ->
                devOsService.interrupt(
                        new DevOsInterruptRequest(actionId, THREAD_BETA, "unauthorized interrupt attempt")),
                "Cross-thread interrupt must throw ApiException (403)"
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus(),
                "Cross-thread interrupt must return 403 FORBIDDEN");

        // ALPHA action must be unchanged
        ActionEntity action = actionMapper.selectById(actionId);
        assertEquals(ActionStatus.QUEUED.name(), action.getStatus(),
                "Target action must remain QUEUED after cross-thread interrupt rejection");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // In-Memory Queue（无 Redis 依赖）
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
                    LeaseState lease = locks.get(actionId);
                    if (lease == null || lease.expireAt().isBefore(now)) {
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
            // no-op
        }

        @Override
        public boolean tryAcquireWorkspaceLock(String workspaceKey, String owner, long ttlSeconds) {
            return workspaceLocks.putIfAbsent(workspaceKey, owner) == null;
        }

        @Override
        public void releaseWorkspaceLock(String workspaceKey, String owner) {
            workspaceLocks.remove(workspaceKey, owner);
        }

        void clear() {
            queues.clear();
            locks.clear();
            workspaceLocks.clear();
        }

        private record LeaseState(String owner, LocalDateTime expireAt) {}
    }
}
