package com.asyncaiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
import com.asyncaiflow.web.dto.ActionAssignmentResponse;
import com.asyncaiflow.web.dto.DevOsApplyPatchRequest;
import com.asyncaiflow.web.dto.DevOsApplyPatchResponse;
import com.asyncaiflow.web.dto.DevOsStartRequest;
import com.asyncaiflow.web.dto.DevOsStartResponse;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;
import com.asyncaiflow.web.dto.SubmitActionResultRequest;

/**
 * B-018 Human Confirm Apply Patch — 验收测试
 *
 * 覆盖场景：
 *   A. confirm=false → 400 rejected；文件不变
 *   B. same thread + valid patchPreview metadata → file modified，applied=true
 *   C. cross-thread apply → 403 FORBIDDEN；文件不变
 *   D. stale file hash mismatch → 409 CONFLICT；文件不变
 *   E. replaceFrom not found in current file → 409 CONFLICT；文件不变
 *
 * 核心不变量：
 *   - no git commit / push
 *   - no arbitrary shell execution
 *   - path safety enforced (relative paths only, no "..")
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(DevOsApplyPatchTest.QueueTestConfig.class)
class DevOsApplyPatchTest {

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

    private static final String WORKER_ID = "apply-patch-test-worker";
    private static final String THREAD_A  = "C-APPLY/1000000000.000001";
    private static final String THREAD_B  = "C-APPLY/9999999999.000001";

    private static final String ORIGINAL_TEXT = "Hello Old Title";
    private static final String REPLACE_TO    = "Hello Slack Dev OS";

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

    // ────────────────────────────────────────────────────────────
    // Scenario A: confirm=false → rejected
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioA_confirmFalse_rejected() throws Exception {
        Path fixture = createFixtureFile(ORIGINAL_TEXT);
        Long previewId = createSucceededPreviewAction(fixture, THREAD_A);

        DevOsApplyPatchRequest req = new DevOsApplyPatchRequest(previewId, THREAD_A, false);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.applyPatch(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "confirm=false must return 400");
        // File must be unchanged
        assertEquals(ORIGINAL_TEXT, readFile(fixture),
                "File must not be modified when confirm=false");
    }

    // ────────────────────────────────────────────────────────────
    // Scenario B: same thread + valid metadata → file modified
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioB_validApply_fileModified() throws Exception {
        Path fixture = createFixtureFile(ORIGINAL_TEXT);
        Long previewId = createSucceededPreviewAction(fixture, THREAD_A);

        DevOsApplyPatchRequest req = new DevOsApplyPatchRequest(previewId, THREAD_A, true);
        DevOsApplyPatchResponse resp = devOsService.applyPatch(req);

        assertEquals("APPLIED", resp.status(), "status must be APPLIED");
        assertTrue(resp.applied(), "applied must be true");
        assertNotNull(resp.filePath(), "filePath must not be null");
        // Real file must contain the new text
        String after = readFile(fixture);
        assertTrue(after.contains(REPLACE_TO),
                "File must contain replaceTo after apply: " + after);
        assertFalse(after.contains(ORIGINAL_TEXT),
                "File must not contain original text after apply: " + after);
    }

    // ────────────────────────────────────────────────────────────
    // Scenario C: cross-thread apply → 403
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioC_crossThread_forbidden() throws Exception {
        Path fixture = createFixtureFile(ORIGINAL_TEXT);
        Long previewId = createSucceededPreviewAction(fixture, THREAD_A);

        // Different thread attempting to apply
        DevOsApplyPatchRequest req = new DevOsApplyPatchRequest(previewId, THREAD_B, true);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.applyPatch(req));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus(),
                "Cross-thread apply must return 403");
        // File must be unchanged
        assertEquals(ORIGINAL_TEXT, readFile(fixture),
                "File must not be modified on cross-thread attempt");
    }

    // ────────────────────────────────────────────────────────────
    // Scenario D: stale file hash mismatch → rejected
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioD_staleHash_rejected() throws Exception {
        Path fixture = createFixtureFile(ORIGINAL_TEXT);
        Long previewId = createSucceededPreviewAction(fixture, THREAD_A);

        // Mutate file AFTER preview was created
        Files.writeString(fixture, "Someone else modified this file\n");

        DevOsApplyPatchRequest req = new DevOsApplyPatchRequest(previewId, THREAD_A, true);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.applyPatch(req));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus(),
                "Stale hash must return 409 CONFLICT");
        assertTrue(ex.getMessage().contains("hash mismatch"),
                "Error must mention hash mismatch: " + ex.getMessage());
        // File still has the mutated content (not the original, not the applied)
        assertFalse(readFile(fixture).contains(REPLACE_TO),
                "File must not have replaceTo applied");
    }

    // ────────────────────────────────────────────────────────────
    // Scenario E: replaceFrom not found → rejected
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioE_replaceFromNotFound_rejected() throws Exception {
        // File doesn't contain the replaceFrom text at all
        Path fixture = createFixtureFile("Completely different content here.");
        // Preview was generated for a different file content — build metadata manually
        // by injecting a preview action result with patchPreview that has replaceFrom not in file
        Long previewId = createSucceededPreviewActionWithCustomReplaceFrom(
                fixture, THREAD_A, "TEXT_THAT_DOES_NOT_EXIST");

        DevOsApplyPatchRequest req = new DevOsApplyPatchRequest(previewId, THREAD_A, true);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.applyPatch(req));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus(),
                "replaceFrom not found must return 409 CONFLICT");
        assertTrue(ex.getMessage().contains("replaceFrom"),
                "Error must mention replaceFrom: " + ex.getMessage());
        // File unchanged
        assertEquals("Completely different content here.", readFile(fixture),
                "File must not be modified when replaceFrom not found");
    }

    // ────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────

    private Path createFixtureFile(String content) throws IOException {
        Path f = tempDir.resolve("README.md");
        Files.writeString(f, content);
        return f;
    }

    private String readFile(Path p) throws IOException {
        return Files.readString(p);
    }

    private String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    /**
     * Creates a SUCCEEDED devos_chat action with patchPreview metadata in the result,
     * simulating a B-017 patch_preview execution.
     */
    private Long createSucceededPreviewAction(Path fixtureFile, String threadId) throws Exception {
        String repoPath = fixtureFile.getParent().toString();
        String filePath = fixtureFile.getFileName().toString();
        byte[] rawBytes = Files.readAllBytes(fixtureFile);
        String sha256 = sha256Hex(rawBytes);

        // 1. Start action
        DevOsStartResponse start = devOsService.start(new DevOsStartRequest(
                "Replace Hello Old Title with Hello Slack Dev OS",
                threadId,
                null, repoPath, filePath, true, null,
                "patch_preview", ORIGINAL_TEXT, REPLACE_TO
        ));

        // 2. Poll → RUNNING
        Optional<ActionAssignmentResponse> poll = actionService.pollAction(WORKER_ID);
        assertTrue(poll.isPresent(), "Worker should pick up the action");
        assertEquals(start.actionId(), poll.get().actionId());

        // 3. Submit SUCCEEDED with patchPreview metadata
        String resultJson = """
                {
                  "response": "[PATCH_PREVIEW]\\nFile: %s",
                  "notepad": "[patch-preview:%d] test",
                  "patchPreview": {
                    "mode": "replace",
                    "repoPath": "%s",
                    "filePath": "%s",
                    "replaceFrom": "%s",
                    "replaceTo": "%s",
                    "originalSha256": "%s",
                    "diff": "--- a/README.md\\n+++ b/README.md"
                  }
                }
                """.formatted(
                filePath, start.actionId(),
                repoPath.replace("\\", "\\\\"),
                filePath,
                ORIGINAL_TEXT,
                REPLACE_TO,
                sha256
        );

        actionService.submitResult(new SubmitActionResultRequest(
                WORKER_ID, start.actionId(), "SUCCEEDED", resultJson, null));

        return start.actionId();
    }

    /**
     * Creates a SUCCEEDED preview action with a custom replaceFrom (for Scenario E).
     * The file hash is computed from what's actually on disk (so hash check passes),
     * but replaceFrom won't be found in the file.
     */
    private Long createSucceededPreviewActionWithCustomReplaceFrom(
            Path fixtureFile, String threadId, String customReplaceFrom) throws Exception {
        String repoPath = fixtureFile.getParent().toString();
        String filePath = fixtureFile.getFileName().toString();
        byte[] rawBytes = Files.readAllBytes(fixtureFile);
        String sha256 = sha256Hex(rawBytes);

        DevOsStartResponse start = devOsService.start(new DevOsStartRequest(
                "Replace something that doesn't exist",
                threadId,
                null, repoPath, filePath, true, null,
                "patch_preview", customReplaceFrom, REPLACE_TO
        ));

        Optional<ActionAssignmentResponse> poll = actionService.pollAction(WORKER_ID);
        assertTrue(poll.isPresent());
        assertEquals(start.actionId(), poll.get().actionId());

        String resultJson = """
                {
                  "response": "[PATCH_PREVIEW]",
                  "notepad": "test",
                  "patchPreview": {
                    "mode": "replace",
                    "repoPath": "%s",
                    "filePath": "%s",
                    "replaceFrom": "%s",
                    "replaceTo": "%s",
                    "originalSha256": "%s",
                    "diff": ""
                  }
                }
                """.formatted(
                repoPath.replace("\\", "\\\\"),
                filePath,
                customReplaceFrom,
                REPLACE_TO,
                sha256
        );

        actionService.submitResult(new SubmitActionResultRequest(
                WORKER_ID, start.actionId(), "SUCCEEDED", resultJson, null));

        return start.actionId();
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
