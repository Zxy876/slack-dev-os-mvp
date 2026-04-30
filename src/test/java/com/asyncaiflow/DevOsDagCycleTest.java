package com.asyncaiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.asyncaiflow.domain.entity.ActionDependencyEntity;
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
import com.asyncaiflow.web.dto.ActionResponse;
import com.asyncaiflow.web.dto.CreateActionRequest;
import com.asyncaiflow.web.dto.CreateWorkflowRequest;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;
import com.asyncaiflow.web.dto.SubmitActionResultRequest;
import com.asyncaiflow.web.dto.WorkflowResponse;

/**
 * B-004 — Stage 3 DAG 环检测集成测试
 *
 * 验证 Slack Dev OS 内核的 DAG 安全机制：
 *   - 合法有向无环图（A→B→C）可正常创建，BLOCKED 任务逐级解锁
 *   - 直接环（A→B，再添加 B→A）被 wouldCreateCycle() 正确识别
 *   - 间接环（A→B→C，再添加 C→A）被 wouldCreateCycle() 正确识别
 *   - 自环（A depends on A）被 wouldCreateCycle() 识别
 *   - 非法 upstream（不存在）被 createAction 拒绝且无残留数据
 *
 * 数据流（B-004 核心路径）：
 *   createAction(upstream=[]) → QUEUED（无依赖，直接入队）
 *   createAction(upstream=[A]) → BLOCKED（等待 A 完成）
 *   submitResult(A, SUCCEEDED) → triggerDownstreamActions → B: BLOCKED→QUEUED
 *   wouldCreateCycle(downstreamId, [proposedUpstream]) → BFS 检测有向环
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(DevOsDagCycleTest.QueueTestConfig.class)
class DevOsDagCycleTest {

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

    // ─── 场景 4.1 — 合法链式 DAG：A → B → C 逐级解锁 ─────────────────────────

    /**
     * 验证：正常链式 DAG A → B → C 可被创建；B/C 初始为 BLOCKED；
     * A 完成后 B 变 QUEUED；B 完成后 C 变 QUEUED。
     * 证明 BLOCKED → QUEUED 依赖解锁机制正确、无环危险。
     */
    @Test
    void testLinearChainBlocksAndUnlocksInOrder() {
        workerService.register(new RegisterWorkerRequest("dag-worker-chain", List.of("devos_chat")));

        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("dag-wf-chain", "DAG Chain Test"));

        // Create A (no deps → QUEUED)
        ActionResponse a = actionService.createAction(new CreateActionRequest(
                workflow.id(), "devos_chat", "{\"step\":\"A\"}", List.of(), 1, 1, 30, null));

        // Create B (upstream: A → BLOCKED)
        ActionResponse b = actionService.createAction(new CreateActionRequest(
                workflow.id(), "devos_chat", "{\"step\":\"B\"}", List.of(a.id()), 1, 1, 30, null));

        // Create C (upstream: B → BLOCKED)
        ActionResponse c = actionService.createAction(new CreateActionRequest(
                workflow.id(), "devos_chat", "{\"step\":\"C\"}", List.of(b.id()), 1, 1, 30, null));

        // Assert initial state
        assertEquals(ActionStatus.QUEUED.name(), actionMapper.selectById(a.id()).getStatus(),
                "A should be QUEUED (no upstream)");
        assertEquals(ActionStatus.BLOCKED.name(), actionMapper.selectById(b.id()).getStatus(),
                "B should be BLOCKED (waiting for A)");
        assertEquals(ActionStatus.BLOCKED.name(), actionMapper.selectById(c.id()).getStatus(),
                "C should be BLOCKED (waiting for B)");

        // Assert 3 dependency rows exist: A→B and B→C
        assertEquals(2, actionDependencyMapper.selectCount(null),
                "Two dependency edges should exist: A→B and B→C");

        // Simulate A runs and succeeds
        ActionEntity aRunning = actionMapper.selectById(a.id());
        aRunning.setStatus(ActionStatus.RUNNING.name());
        aRunning.setWorkerId("dag-worker-chain");
        aRunning.setLeaseExpireAt(LocalDateTime.now().plusSeconds(60));
        aRunning.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(aRunning);

        actionService.submitResult(new SubmitActionResultRequest(
                "dag-worker-chain", a.id(), "SUCCEEDED", "{\"result\":\"A done\"}", null));

        // B should now be QUEUED (triggered by A's success)
        assertEquals(ActionStatus.QUEUED.name(), actionMapper.selectById(b.id()).getStatus(),
                "B should be QUEUED after A succeeds");
        assertEquals(ActionStatus.BLOCKED.name(), actionMapper.selectById(c.id()).getStatus(),
                "C should still be BLOCKED (B hasn't succeeded yet)");

        // Simulate B runs and succeeds
        ActionEntity bRunning = actionMapper.selectById(b.id());
        bRunning.setStatus(ActionStatus.RUNNING.name());
        bRunning.setWorkerId("dag-worker-chain");
        bRunning.setLeaseExpireAt(LocalDateTime.now().plusSeconds(60));
        bRunning.setUpdatedAt(LocalDateTime.now());
        actionMapper.updateById(bRunning);

        actionService.submitResult(new SubmitActionResultRequest(
                "dag-worker-chain", b.id(), "SUCCEEDED", "{\"result\":\"B done\"}", null));

        // C should now be QUEUED
        assertEquals(ActionStatus.QUEUED.name(), actionMapper.selectById(c.id()).getStatus(),
                "C should be QUEUED after B succeeds");
    }

    // ─── 场景 4.2 — 直接环检测：A → B 存在时，B → A 被识别为环 ────────────────

    /**
     * 验证 wouldCreateCycle 正确识别直接反向依赖形成的环。
     * 现有图：A → B（B 依赖 A）
     * 测试：若添加 B → A（A 依赖 B），则 A → B → A 构成环 → 应返回 true
     * 反向验证：A → B 已存在，添加 A → B 不构成新环 → 应返回 false（重复边，非环）
     */
    @Test
    void testDirectReverseCycleDetected() {
        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("dag-wf-direct-cycle", "Direct Cycle Test"));

        ActionResponse a = actionService.createAction(new CreateActionRequest(
                workflow.id(), "devos_chat", "{}", List.of(), 1, 1, 30, null));
        ActionResponse b = actionService.createAction(new CreateActionRequest(
                workflow.id(), "devos_chat", "{}", List.of(a.id()), 1, 1, 30, null));

        // Existing graph: A → B (edge: upstream=A, downstream=B)
        // Test: would adding edge (upstream=B, downstream=A) create a cycle?
        // BFS from A: A's downstreams → {B}; B is in proposedUpstreams {B} → CYCLE
        assertTrue(actionService.wouldCreateCycle(a.id(), List.of(b.id())),
                "A depends on B when A→B already exists would create cycle A→B→A");

        // Test: would adding edge (upstream=A, downstream=B) create a cycle?
        // (This is the already-existing edge A→B, just re-adding it)
        // BFS from B: B's downstreams → {} (B has no downstream edges yet) → no cycle
        assertFalse(actionService.wouldCreateCycle(b.id(), List.of(a.id())),
                "Adding A→B when A→B already exists is a duplicate edge, not a new cycle");
    }

    // ─── 场景 4.3 — 间接环检测：A → B → C 存在时，C → A 被识别为环 ───────────

    /**
     * 验证 wouldCreateCycle 正确识别 3 节点间接环。
     * 现有图：A → B → C
     * 测试：若添加 C → A（A 依赖 C），则 A → B → C → A 构成环 → 应返回 true
     * 反向验证：若添加 A → C（C 依赖 A，跳过 B），DAG 仍合法 → 应返回 false
     */
    @Test
    void testIndirectThreeNodeCycleDetected() {
        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("dag-wf-indirect-cycle", "Indirect Cycle Test"));

        ActionResponse a = actionService.createAction(new CreateActionRequest(
                workflow.id(), "devos_chat", "{}", List.of(), 1, 1, 30, null));
        ActionResponse b = actionService.createAction(new CreateActionRequest(
                workflow.id(), "devos_chat", "{}", List.of(a.id()), 1, 1, 30, null));
        ActionResponse c = actionService.createAction(new CreateActionRequest(
                workflow.id(), "devos_chat", "{}", List.of(b.id()), 1, 1, 30, null));

        // Existing graph: A → B → C
        // Test: would adding edge (upstream=C, downstream=A) create a cycle?
        // BFS from A: A→B→C; C is in proposedUpstreams {C} → CYCLE (A→B→C→A)
        assertTrue(actionService.wouldCreateCycle(a.id(), List.of(c.id())),
                "Adding C→A when A→B→C already exists creates indirect cycle A→B→C→A");

        // Test: would adding edge (upstream=A, downstream=C) create a cycle?
        // (C depending on A as an additional edge — diamond/shortcut, valid DAG)
        // BFS from C: C has no downstream edges → no cycle
        assertFalse(actionService.wouldCreateCycle(c.id(), List.of(a.id())),
                "Adding A→C as extra edge (A→B→C plus A→C) does not create a cycle");

        // Self-loop on C: C depends on C
        assertTrue(actionService.wouldCreateCycle(c.id(), List.of(c.id())),
                "Self-loop: C depending on itself is always a cycle");
    }

    // ─── 场景 4.4 — createAction 拒绝不存在 upstream + 无残留数据 ──────────────

    /**
     * 验证：createAction 拒绝不存在的 upstream ID 并通过事务回滚确保无残留数据。
     * - 抛出 ApiException(400)
     * - Action 记录未被写入 action 表（事务回滚）
     * - action_dependency 表中无残留行
     */
    @Test
    void testCreateActionRejectsNonexistentUpstreamAndLeavesNoResidue() {
        WorkflowResponse workflow = workflowService.createWorkflow(
                new CreateWorkflowRequest("dag-wf-reject", "Rejection Test"));

        long actionCountBefore = actionMapper.selectCount(null);
        long depCountBefore = actionDependencyMapper.selectCount(null);

        ApiException ex = assertThrows(ApiException.class, () ->
                actionService.createAction(new CreateActionRequest(
                        workflow.id(),
                        "devos_chat",
                        "{}",
                        List.of(Long.MAX_VALUE),  // nonexistent upstream ID
                        1, 1, 30, null
                )));

        assertTrue(ex.getMessage().contains("do not exist"),
                "Error message should mention nonexistent upstreams");

        // Verify no residuals: action count unchanged
        assertEquals(actionCountBefore, actionMapper.selectCount(null),
                "No new action should be persisted after rejected createAction");
        assertEquals(depCountBefore, actionDependencyMapper.selectCount(null),
                "No dependency rows should be persisted after rejected createAction");
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

        /** Exposed so ActionService can access the bean by type for clear(). */
        public ActionQueueService getActionQueueService() {
            return this;
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

        record LeaseState(String workerId, LocalDateTime expireAt) {}
    }
}
