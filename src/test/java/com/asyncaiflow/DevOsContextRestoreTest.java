package com.asyncaiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * B-001 — Stage 2 Context Restore 隔离集成测试
 *
 * 测试场景：
 *   1. 同一 slackThreadId：Action 1 完成后，Action 2 通过 prevActionId 继承 notepad_ref
 *   2. 不同 slackThreadId：T-BETA 不继承 T-ALPHA 的 notepad（隔离验证）
 *   3. prevActionId 不存在时：新 Action notepad_ref 为 null（健壮性）
 *
 * 数据流（方案 A）：
 *   Action 1 SUCCEEDED
 *     → extractNotepadFromResult() → action1.notepad_ref = "..."
 *   POST /devos/start { prevActionId: action1.id }
 *     → DevOsService.resolveNotepadRef(action1.id)
 *     → action2.notepad_ref = action1.notepad_ref
 *   Worker polls Action 2
 *     → ActionAssignmentResponse.notepadRef = action2.notepad_ref
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(DevOsContextRestoreTest.QueueTestConfig.class)
class DevOsContextRestoreTest {

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
    private WorkerMapper workerMapper;

    @Autowired
    private ActionDependencyMapper actionDependencyMapper;

    @Autowired
    private ActionLogMapper actionLogMapper;

    @Autowired
    private ActionQueueService actionQueueService;

    private static final String WORKER_ID = "ctx-restore-test-worker";

    @BeforeEach
    void cleanTables() {
        actionLogMapper.delete(null);
        actionDependencyMapper.delete(null);
        actionMapper.delete(null);
        workerMapper.delete(null);
        workflowMapper.delete(null);
        ((InMemoryActionQueueService) actionQueueService).clear();

        // 注册测试 Worker
        workerService.register(new RegisterWorkerRequest(
                WORKER_ID,
                List.of("devos_chat")
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 测试 1：同一 slackThreadId，notepad 顺序传播
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scenario 2.1 — 顺序 notepad 传播
     *
     * 验收条件：
     *   - action1.notepad_ref ≠ null（extractNotepadFromResult 正确持久化）
     *   - action2.notepad_ref == action1.notepad_ref（DevOsService 正确继承）
     *   - Worker poll for Action 2 的 notepadRef == action2.notepad_ref（AssignmentResponse 正确下发）
     */
    @Test
    void testNotepadPropagatesAcrossSequentialActions() throws Exception {
        // ── Round 1: 创建 Action 1，Worker 执行，提交 SUCCEEDED ──
        DevOsStartResponse resp1 = devOsService.start(new DevOsStartRequest(
                "design the system architecture",
                "T-ALPHA/1000000000.000001",
                null   // 第一轮无 prevActionId
        ));
        Long action1Id = resp1.actionId();

        // Worker poll → Action 1 认领
        Optional<ActionAssignmentResponse> poll1 = actionService.pollAction(WORKER_ID);
        assertTrue(poll1.isPresent(), "Worker should pick up Action 1");
        assertEquals(action1Id, poll1.get().actionId());
        assertNull(poll1.get().notepadRef(), "First action should have null notepadRef");

        // 构建包含 notepad 的 result JSON（模拟 worker.py 输出）
        String resultJson = "{\"response\":\"[DEMO] architecture drafted\","
                + "\"notepad\":\"[action:" + action1Id + "] design the system architecture → architecture drafted\"}";

        // Worker 提交 SUCCEEDED
        actionService.submitResult(new SubmitActionResultRequest(
                WORKER_ID,
                action1Id,
                "SUCCEEDED",
                resultJson,
                null
        ));

        // 验证 action1.notepad_ref 已持久化
        ActionEntity action1 = actionMapper.selectById(action1Id);
        assertNotNull(action1.getNotepadRef(), "action1.notepad_ref must be persisted after SUCCEEDED");
        assertTrue(action1.getNotepadRef().contains("[action:" + action1Id + "]"),
                "notepad_ref should contain action marker");

        // ── Round 2: 创建 Action 2，传入 prevActionId = action1Id ──
        DevOsStartResponse resp2 = devOsService.start(new DevOsStartRequest(
                "continue the previous step",
                "T-ALPHA/1000000000.000001",
                action1Id  // Context Restore: 继承 Action 1 的 notepad
        ));
        Long action2Id = resp2.actionId();

        // 验证 action2.notepad_ref == action1.notepad_ref（继承）
        ActionEntity action2 = actionMapper.selectById(action2Id);
        assertNotNull(action2.getNotepadRef(), "action2.notepad_ref must be inherited from action1");
        assertEquals(action1.getNotepadRef(), action2.getNotepadRef(),
                "action2.notepad_ref must equal action1.notepad_ref");

        // Worker poll → Action 2 认领，notepadRef 应随 AssignmentResponse 下发
        Optional<ActionAssignmentResponse> poll2 = actionService.pollAction(WORKER_ID);
        assertTrue(poll2.isPresent(), "Worker should pick up Action 2");
        assertEquals(action2Id, poll2.get().actionId());
        assertNotNull(poll2.get().notepadRef(),
                "Action 2 poll response must include notepadRef for Context Restore");
        assertEquals(action1.getNotepadRef(), poll2.get().notepadRef(),
                "notepadRef in poll response must match action1's notepad_ref");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 测试 2：不同 slackThreadId，notepad 隔离
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scenario 2.2 — 跨 Thread 隔离
     *
     * 验收条件：
     *   - T-BETA 的 Action B1 不继承 T-ALPHA 的 notepad（未传 prevActionId）
     *   - T-ALPHA 的 notepad 在 B1 完成后不被覆盖
     */
    @Test
    void testNotepadIsolatedAcrossThreads() throws Exception {
        // ── T-ALPHA: Action A1 完成，notepad 写入 ──
        DevOsStartResponse respA = devOsService.start(new DevOsStartRequest(
                "alpha workflow step",
                "T-ALPHA/2000000000.000001",
                null
        ));
        Long actionAId = respA.actionId();

        Optional<ActionAssignmentResponse> pollA = actionService.pollAction(WORKER_ID);
        assertTrue(pollA.isPresent());

        String alphaResult = "{\"response\":\"[DEMO] alpha done\","
                + "\"notepad\":\"[action:" + actionAId + "] alpha workflow step → alpha done\"}";
        actionService.submitResult(new SubmitActionResultRequest(
                WORKER_ID, actionAId, "SUCCEEDED", alphaResult, null));

        ActionEntity actionA = actionMapper.selectById(actionAId);
        assertNotNull(actionA.getNotepadRef(), "T-ALPHA action notepad must be persisted");
        String alphaNotepad = actionA.getNotepadRef();

        // ── T-BETA: Action B1，不传 prevActionId（独立 thread，无上下文继承）──
        DevOsStartResponse respB = devOsService.start(new DevOsStartRequest(
                "beta workflow step",
                "T-BETA/2000000000.000002",
                null  // 故意不传 prevActionId —— 跨 thread 无关联
        ));
        Long actionBId = respB.actionId();

        // 验证 B1 的 notepad_ref 为 null（不含 T-ALPHA 的数据）
        ActionEntity actionB = actionMapper.selectById(actionBId);
        assertNull(actionB.getNotepadRef(),
                "T-BETA action must NOT inherit T-ALPHA notepad");

        // Worker poll T-BETA Action → notepadRef 应为 null
        Optional<ActionAssignmentResponse> pollB = actionService.pollAction(WORKER_ID);
        assertTrue(pollB.isPresent(), "Worker should pick up T-BETA Action");
        assertEquals(actionBId, pollB.get().actionId());
        assertNull(pollB.get().notepadRef(),
                "T-BETA poll response must have null notepadRef — no cross-thread contamination");

        // T-BETA 完成
        String betaResult = "{\"response\":\"[DEMO] beta done\","
                + "\"notepad\":\"[action:" + actionBId + "] beta workflow step → beta done\"}";
        actionService.submitResult(new SubmitActionResultRequest(
                WORKER_ID, actionBId, "SUCCEEDED", betaResult, null));

        // 验证 T-ALPHA 的 notepad 未被修改
        ActionEntity actionAAfter = actionMapper.selectById(actionAId);
        assertEquals(alphaNotepad, actionAAfter.getNotepadRef(),
                "T-ALPHA notepad must not be modified after T-BETA completes");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 测试 3：prevActionId 指向不存在的 Action，新 Action notepad_ref 为 null
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 健壮性：prevActionId 不存在时不抛异常，notepad_ref = null。
     */
    @Test
    void testPrevActionIdNotFoundFallsBackToNull() {
        DevOsStartResponse resp = devOsService.start(new DevOsStartRequest(
                "some task",
                "T-GAMMA/3000000000.000001",
                999999999L  // 不存在的 actionId
        ));

        ActionEntity action = actionMapper.selectById(resp.actionId());
        assertNull(action.getNotepadRef(),
                "notepad_ref must be null when prevActionId does not exist");
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

        void clear() {
            queues.clear();
            locks.clear();
        }

        private record LeaseState(String owner, LocalDateTime expireAt) {}
    }
}
