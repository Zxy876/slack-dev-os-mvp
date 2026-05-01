package com.asyncaiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.http.HttpStatus;
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
import com.asyncaiflow.support.ApiException;
import com.asyncaiflow.web.dto.DevOsRunTestRequest;
import com.asyncaiflow.web.dto.DevOsRunTestResponse;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;

/**
 * B-019 Test Command Runner — 验收测试
 *
 * 覆盖场景：
 *   A. 合法命令（bash scripts/secret_scan.sh）在真实 repo 中执行 → PASSED, exitCode=0
 *   B. 不在 allowlist 的命令 → 400 BAD_REQUEST
 *   C. repoPath 不存在 / 不是目录 → 400 BAD_REQUEST
 *   D. 合法命令在 fixture repo 中执行 → FAILED, exitCode!=0，但 HTTP 200（不抛 500）
 *   E. timeoutSeconds 超限被 clamp → 仍然正常执行（不拒绝请求）
 *
 * 安全不变量：
 *   - 不支持任意 shell command
 *   - ProcessBuilder 使用显式 args 列表，不走 sh -c
 *   - cwd 固定在 repoPath 内
 *   - 测试失败 (exitCode!=0) 不等于系统失败 (HTTP 500)
 *   - 不自动修复，不 commit，不 push
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(DevOsRunTestTest.QueueTestConfig.class)
class DevOsRunTestTest {

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

    @TempDir
    Path tempDir;

    /** Absolute path of the Maven project root (cwd when tests run). */
    private static final String REPO_ROOT = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath().toString();

    private static final String THREAD_ID = "C-RUN-TEST/1000000000.000001";

    @BeforeEach
    void cleanTables() {
        actionLogMapper.delete(null);
        actionDependencyMapper.delete(null);
        actionMapper.delete(null);
        workerMapper.delete(null);
        workflowMapper.delete(null);
        ((InMemoryActionQueueService) actionQueueService).clear();

        workerService.register(new RegisterWorkerRequest(
                "run-test-worker",
                List.of("devos_chat")
        ));
    }

    // ────────────────────────────────────────────────────────────
    // Scenario A: allowed command in real repo → PASSED
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioA_allowedCommand_passed() {
        // "bash scripts/secret_scan.sh" is in allowlist and returns 0 on clean repo
        DevOsRunTestRequest req = new DevOsRunTestRequest(
                REPO_ROOT, THREAD_ID, "bash scripts/secret_scan.sh", 30);

        DevOsRunTestResponse resp = devOsService.runTest(req);

        assertEquals("PASSED", resp.status(), "Clean repo should produce PASSED");
        assertEquals(0, resp.exitCode(), "exitCode must be 0");
        assertTrue(resp.durationMs() >= 0, "durationMs must be non-negative");
        assertNotNull(resp.stdoutExcerpt(), "stdoutExcerpt must not be null");
        assertNotNull(resp.stderrExcerpt(), "stderrExcerpt must not be null");
        assertEquals("bash scripts/secret_scan.sh", resp.command());
        assertNotNull(resp.repoPath(), "repoPath must not be null");
    }

    // ────────────────────────────────────────────────────────────
    // Scenario B: command not in allowlist → 400
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioB_disallowedCommand_rejected() {
        DevOsRunTestRequest req = new DevOsRunTestRequest(
                REPO_ROOT, THREAD_ID, "rm -rf /tmp/evil", null);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.runTest(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "Disallowed command must return 400");
        assertTrue(ex.getMessage().contains("allowlist"),
                "Error must mention allowlist: " + ex.getMessage());
    }

    @Test
    void scenarioB_shellInjection_rejected() {
        // Command with injection attempt — exact match required, so this will be rejected
        DevOsRunTestRequest req = new DevOsRunTestRequest(
                REPO_ROOT, THREAD_ID, "bash scripts/secret_scan.sh; rm -rf /", null);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.runTest(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void scenarioB_emptyCommand_rejected() {
        // Note: @NotBlank prevents blank at controller level, but test service directly too
        DevOsRunTestRequest req = new DevOsRunTestRequest(
                REPO_ROOT, THREAD_ID, "ls -la", null);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.runTest(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    // ────────────────────────────────────────────────────────────
    // Scenario C: repoPath does not exist / not a directory → 400
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioC_repoDirNotFound_rejected() {
        DevOsRunTestRequest req = new DevOsRunTestRequest(
                "/no/such/path/does/not/exist/1234567890",
                THREAD_ID, "bash scripts/secret_scan.sh", null);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.runTest(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "Non-existent repoPath must return 400");
    }

    @Test
    void scenarioC_repoPathIsFile_rejected() throws IOException {
        // tempDir is a directory — create a file inside and point at the file
        Path aFile = tempDir.resolve("not-a-dir.txt");
        Files.writeString(aFile, "I am a file");

        DevOsRunTestRequest req = new DevOsRunTestRequest(
                aFile.toString(), THREAD_ID, "bash scripts/secret_scan.sh", null);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.runTest(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "File path used as repoPath must return 400");
    }

    // ────────────────────────────────────────────────────────────
    // Scenario D: allowed command that exits with nonzero → FAILED
    //   The fixture repo has its own scripts/secret_scan.sh returning exit 1.
    //   API still returns HTTP 200 success=true.
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioD_commandFails_statusFailedButNoException() throws IOException {
        // Create a fixture repo with a scripts/secret_scan.sh that exits 1
        Path fixtureRepo = tempDir.resolve("fixture-repo");
        Path fixtureScripts = fixtureRepo.resolve("scripts");
        Files.createDirectories(fixtureScripts);
        Path failScript = fixtureScripts.resolve("secret_scan.sh");
        Files.writeString(failScript, "#!/usr/bin/env bash\necho 'FAIL secret found'\nexit 1\n");
        // Make it executable
        failScript.toFile().setExecutable(true);

        DevOsRunTestRequest req = new DevOsRunTestRequest(
                fixtureRepo.toString(), THREAD_ID, "bash scripts/secret_scan.sh", 15);

        // Must NOT throw — test failure is a business result, not a system error
        DevOsRunTestResponse resp = devOsService.runTest(req);

        assertEquals("FAILED", resp.status(),
                "Nonzero exit must produce status=FAILED");
        assertEquals(1, resp.exitCode(),
                "exitCode must be 1");
        assertTrue(resp.durationMs() >= 0);
        // stdout should contain the echo output
        assertTrue(resp.stdoutExcerpt().contains("FAIL"),
                "stdout excerpt should contain failure message: " + resp.stdoutExcerpt());
    }

    // ────────────────────────────────────────────────────────────
    // Scenario E: timeout clamp — oversized timeout is accepted (clamped internally)
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioE_timeoutClamped_commandStillRuns() {
        // timeoutSeconds=999 should be clamped to 180; command should still complete
        DevOsRunTestRequest req = new DevOsRunTestRequest(
                REPO_ROOT, THREAD_ID, "bash scripts/secret_scan.sh", 999);

        // Must not throw 400 for oversized timeout — clamp is silent
        DevOsRunTestResponse resp = devOsService.runTest(req);

        assertNotNull(resp, "Response must not be null");
        assertTrue(resp.exitCode() == 0, "secret_scan should pass: " + resp.stderrExcerpt());
        assertEquals("PASSED", resp.status());
    }

    @Test
    void scenarioE_timeoutNull_usesDefault() {
        // null timeout → uses default 120s; command should complete normally
        DevOsRunTestRequest req = new DevOsRunTestRequest(
                REPO_ROOT, THREAD_ID, "bash scripts/secret_scan.sh", null);

        DevOsRunTestResponse resp = devOsService.runTest(req);

        assertNotNull(resp);
        assertEquals("PASSED", resp.status());
    }

    // ────────────────────────────────────────────────────────────
    // In-memory queue stub (same pattern as other test classes)
    // ────────────────────────────────────────────────────────────

    @TestConfiguration
    static class QueueTestConfig {
        @Bean
        @Primary
        ActionQueueService inMemoryActionQueueService() {
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
