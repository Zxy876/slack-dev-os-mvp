package com.asyncaiflow;

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
import com.asyncaiflow.mapper.ActionDependencyMapper;
import com.asyncaiflow.mapper.ActionLogMapper;
import com.asyncaiflow.mapper.ActionMapper;
import com.asyncaiflow.mapper.WorkerMapper;
import com.asyncaiflow.mapper.WorkflowMapper;
import com.asyncaiflow.service.ActionService;
import com.asyncaiflow.service.DevOsService;
import com.asyncaiflow.service.WorkerService;
import com.asyncaiflow.service.queue.ActionQueueService;
import com.asyncaiflow.web.dto.DevOsStartRequest;
import com.asyncaiflow.web.dto.DevOsStartResponse;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;

/**
 * B-005 — Stage 4 Page Fault / Repository File Retrieval — Java Payload 传递测试
 *
 * 验证 Java 层的 payload 构建逻辑：
 *   A. repoPath + filePath 写入 Action payload JSON
 *   B. 不提供 repoPath/filePath 时 payload 中不含 repo_path / file_path 字段
 *
 * 核心不变量：
 *   - Java 层仅做字段透传，不读取文件
 *   - payload 是传递给 worker 的 JSON 字符串
 *   - 新字段不影响无 repoPath/filePath 的普通请求
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(DevOsPageFaultRequestTest.QueueTestConfig.class)
class DevOsPageFaultRequestTest {

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

    private static final String WORKER_ID = "page-fault-test-worker";

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
    // Test A: repoPath + filePath 写入 Action payload
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scenario 4.1 — Page Fault payload 传递
     *
     * 验收条件：
     *   - DevOsService.start() 将 repoPath 写入 payload 为 repo_path 字段
     *   - DevOsService.start() 将 filePath 写入 payload 为 file_path 字段
     *   - Action 入库后 payload JSON 包含 repo_path 和 file_path
     */
    @Test
    void testPayloadContainsRepoPathAndFilePath() {
        DevOsStartResponse resp = devOsService.start(new DevOsStartRequest(
                "Explain this file",
                "CDEMO/1714500000.000100",
                null,
                "/tmp/devos-fixture-repo",
                "README.md"
        ));

        ActionEntity action = actionMapper.selectById(resp.actionId());
        assertNotNull(action, "Action must be created");
        String payload = action.getPayload();
        assertNotNull(payload, "payload must not be null");

        assertTrue(payload.contains("\"repo_path\""),
                "payload must contain repo_path key: " + payload);
        assertTrue(payload.contains("/tmp/devos-fixture-repo"),
                "payload must contain repoPath value: " + payload);
        assertTrue(payload.contains("\"file_path\""),
                "payload must contain file_path key: " + payload);
        assertTrue(payload.contains("README.md"),
                "payload must contain filePath value: " + payload);
        assertTrue(payload.contains("\"user_text\""),
                "payload must still contain user_text: " + payload);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test B: 无 repoPath/filePath 时 payload 不含这些字段
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scenario 4.2 — 普通请求不受影响
     *
     * 验收条件：
     *   - 不传 repoPath/filePath 时，payload 中不出现 repo_path / file_path 字段
     *   - 保持与 Stage 0/1/2/3 兼容
     */
    @Test
    void testPayloadWithoutRepoPathHasNoRepoFields() {
        DevOsStartResponse resp = devOsService.start(new DevOsStartRequest(
                "How do I reset a build?",
                "CDEMO/1714500000.000200",
                null, null, null  // 不提供 repoPath / filePath
        ));

        ActionEntity action = actionMapper.selectById(resp.actionId());
        assertNotNull(action, "Action must be created");
        String payload = action.getPayload();
        assertNotNull(payload, "payload must not be null");

        assertFalse(payload.contains("repo_path"),
                "payload must NOT contain repo_path when not provided: " + payload);
        assertFalse(payload.contains("file_path"),
                "payload must NOT contain file_path when not provided: " + payload);
        assertTrue(payload.contains("\"user_text\""),
                "payload must still contain user_text: " + payload);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // In-Memory Queue（无 Redis 依赖）
    // ─────────────────────────────────────────────────────────────────────────

    @TestConfiguration
    static class QueueTestConfig {
        @Bean
        @Primary
        public ActionQueueService inMemoryActionQueueService(StringRedisTemplate redisTemplate) {
            return new InMemoryActionQueueService();
        }
    }

    static class InMemoryActionQueueService extends ActionQueueService {
        private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> queues
                = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, LeaseState> locks = new ConcurrentHashMap<>();

        InMemoryActionQueueService() {
            super(new org.springframework.data.redis.core.StringRedisTemplate());
        }

        @Override
        public void enqueue(com.asyncaiflow.domain.entity.ActionEntity action) {
            enqueue(action, action.getType());
        }

        @Override
        public void enqueue(com.asyncaiflow.domain.entity.ActionEntity action, String capability) {
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
            // no-op
        }

        public void clear() {
            queues.clear();
            locks.clear();
        }

        record LeaseState(String workerId, LocalDateTime expireAt) {}
    }
}
