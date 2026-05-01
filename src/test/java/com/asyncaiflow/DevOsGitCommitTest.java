package com.asyncaiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.asyncaiflow.web.dto.DevOsGitCommitRequest;
import com.asyncaiflow.web.dto.DevOsGitCommitResponse;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;

/**
 * B-021 Human Git Commit Snapshot — 验收测试
 *
 * 覆盖场景：
 *   A. confirm=false → 400 BAD_REQUEST，无 commit
 *   B. repoPath 不在 git 仓库中 → 400 BAD_REQUEST
 *   C. git 仓库无改动 → NO_CHANGES（HTTP 200）
 *   D. git 仓库有改动 + confirm=true → COMMITTED，commitHash 非空
 *   E. commit message 超 200 字符 → 400 BAD_REQUEST
 *   F. repoPath 不存在或是文件 → 400 BAD_REQUEST
 *
 * 安全不变量：
 *   - confirm=false 时立即拒绝，不执行任何 git 操作
 *   - ProcessBuilder 使用显式 args 列表，不走 sh -c
 *   - 只执行本地 commit，不 push，不修改 remote，不写全局 git config
 *   - NO_CHANGES 是业务正常状态，返回 HTTP 200（不抛异常）
 *   - git commit 不修改 main project 的工作区（使用 @TempDir fixture）
 */
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Import(DevOsGitCommitTest.QueueTestConfig.class)
class DevOsGitCommitTest {

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

    private static final String THREAD_ID = "C-GIT-COMMIT/2000000000.000001";

    @BeforeEach
    void cleanTables() {
        actionLogMapper.delete(null);
        actionDependencyMapper.delete(null);
        actionMapper.delete(null);
        workerMapper.delete(null);
        workflowMapper.delete(null);
        ((InMemoryActionQueueService) actionQueueService).clear();

        workerService.register(new RegisterWorkerRequest(
                "git-commit-worker",
                List.of("devos_chat")
        ));
    }

    // ────────────────────────────────────────────────────────────
    // Scenario A: confirm=false → 400, no commit executed
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioA_confirmFalse_rejected() throws Exception {
        Path gitRepo = initGitRepo(tempDir.resolve("repo-a"));

        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                gitRepo.toString(), THREAD_ID, "should not commit", false);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.gitCommit(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "confirm=false must return 400");
        assertTrue(ex.getMessage().contains("confirm"),
                "Error must mention confirm: " + ex.getMessage());

        // Verify no new commit was made (only the initial commit exists)
        int commitCount = countCommits(gitRepo);
        assertEquals(1, commitCount, "No commit should have been created");
    }

    @Test
    void scenarioA_confirmNull_rejected() throws Exception {
        Path gitRepo = initGitRepo(tempDir.resolve("repo-a2"));

        // confirm=null should behave like confirm=false
        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                gitRepo.toString(), THREAD_ID, "should not commit", null);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.gitCommit(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "confirm=null must return 400");
    }

    // ────────────────────────────────────────────────────────────
    // Scenario B: repoPath not inside a git repo → 400
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioB_nonGitDir_rejected() throws Exception {
        // tempDir is created by JUnit but NOT a git repo
        Path nonGit = tempDir.resolve("not-a-git-repo");
        Files.createDirectories(nonGit);

        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                nonGit.toString(), THREAD_ID, "test commit", true);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.gitCommit(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "Non-git directory must return 400");
    }

    // ────────────────────────────────────────────────────────────
    // Scenario C: git repo with no uncommitted changes → NO_CHANGES (HTTP 200)
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioC_noChanges_returnsNoChanges() throws Exception {
        // initGitRepo leaves the repo clean (initial commit done, no pending changes)
        Path gitRepo = initGitRepo(tempDir.resolve("repo-c"));

        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                gitRepo.toString(), THREAD_ID, "nothing to commit", true);

        // Must NOT throw — NO_CHANGES is a valid business state
        DevOsGitCommitResponse resp = devOsService.gitCommit(req);

        assertEquals("NO_CHANGES", resp.status(),
                "Clean repo must return NO_CHANGES");
        assertNull(resp.commitHash(), "commitHash must be null when NO_CHANGES");
        assertTrue(resp.changedFiles().isEmpty(), "changedFiles must be empty");

        // Verify commit count unchanged
        assertEquals(1, countCommits(gitRepo), "Commit count must remain 1");
    }

    // ────────────────────────────────────────────────────────────
    // Scenario D: git repo with changed file + confirm=true → COMMITTED
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioD_withChanges_committed() throws Exception {
        Path gitRepo = initGitRepo(tempDir.resolve("repo-d"));

        // Modify the README that was created in initGitRepo
        Path readme = gitRepo.resolve("README.md");
        Files.writeString(readme, "# Modified by DevOS\nchange content\n");

        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                gitRepo.toString(), THREAD_ID, "devos: update README", true);

        DevOsGitCommitResponse resp = devOsService.gitCommit(req);

        assertEquals("COMMITTED", resp.status(),
                "Changed file must produce COMMITTED: " + resp.message());
        assertNotNull(resp.commitHash(),
                "commitHash must not be null after COMMITTED");
        assertEquals(40, resp.commitHash().length(),
                "commitHash must be a full SHA-1 (40 chars)");
        assertNotNull(resp.changedFiles(),
                "changedFiles must not be null");
        assertTrue(resp.changedFiles().stream().anyMatch(f -> f.contains("README")),
                "changedFiles must include README.md: " + resp.changedFiles());
        assertEquals("devos: update README", resp.message());
        assertNotNull(resp.repoPath());

        // Verify a second commit was actually created
        assertEquals(2, countCommits(gitRepo), "Commit count must be 2 after COMMITTED");
    }

    @Test
    void scenarioD_newFileAdded_committed() throws Exception {
        Path gitRepo = initGitRepo(tempDir.resolve("repo-d2"));

        // Add a new file that doesn't exist yet
        Path newFile = gitRepo.resolve("notes.txt");
        Files.writeString(newFile, "devos auto-generated notes\n");

        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                gitRepo.toString(), THREAD_ID, "devos: add notes.txt", true);

        DevOsGitCommitResponse resp = devOsService.gitCommit(req);

        assertEquals("COMMITTED", resp.status());
        assertNotNull(resp.commitHash());
        assertTrue(resp.changedFiles().stream().anyMatch(f -> f.contains("notes")),
                "changedFiles must include notes.txt: " + resp.changedFiles());

        assertEquals(2, countCommits(gitRepo));
    }

    // ────────────────────────────────────────────────────────────
    // Scenario E: commit message exceeds 200 chars → 400
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioE_messageTooLong_rejected() throws Exception {
        Path gitRepo = initGitRepo(tempDir.resolve("repo-e"));

        String longMsg = "x".repeat(201); // 201 chars — exceeds limit
        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                gitRepo.toString(), THREAD_ID, longMsg, true);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.gitCommit(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "Oversized message must return 400");
        assertTrue(ex.getMessage().toLowerCase().contains("200"),
                "Error must mention the 200-char limit: " + ex.getMessage());
    }

    @Test
    void scenarioE_messageExactly200_accepted() throws Exception {
        Path gitRepo = initGitRepo(tempDir.resolve("repo-e2"));
        Path readme = gitRepo.resolve("README.md");
        Files.writeString(readme, "modified\n");

        String exactly200 = "a".repeat(200);
        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                gitRepo.toString(), THREAD_ID, exactly200, true);

        // Must NOT throw — exactly 200 chars is within the limit
        DevOsGitCommitResponse resp = devOsService.gitCommit(req);

        assertEquals("COMMITTED", resp.status(), "Exactly-200-char message must be accepted");
    }

    // ────────────────────────────────────────────────────────────
    // Scenario F: repoPath does not exist / is a file → 400
    // ────────────────────────────────────────────────────────────

    @Test
    void scenarioF_repoPathNotExist_rejected() {
        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                "/no/such/path/does/not/exist/devos_test_12345",
                THREAD_ID, "test commit", true);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.gitCommit(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "Non-existent repoPath must return 400");
    }

    @Test
    void scenarioF_repoPathIsFile_rejected() throws IOException {
        Path aFile = tempDir.resolve("iam-a-file.txt");
        Files.writeString(aFile, "not a directory");

        DevOsGitCommitRequest req = new DevOsGitCommitRequest(
                aFile.toString(), THREAD_ID, "test commit", true);

        ApiException ex = assertThrows(ApiException.class,
                () -> devOsService.gitCommit(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(),
                "File path used as repoPath must return 400");
    }

    // ────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────

    /**
     * Create a fresh git repository in the given directory with a single
     * baseline commit. Configures local (non-global) user identity.
     *
     * Returns the absolute path of the initialised repo.
     */
    private Path initGitRepo(Path dir) throws Exception {
        Files.createDirectories(dir);
        run(dir, "git", "init");
        run(dir, "git", "config", "user.email", "devos@example.local");
        run(dir, "git", "config", "user.name",  "Slack Dev OS Test");
        Path readme = dir.resolve("README.md");
        Files.writeString(readme, "# Hello from DevOS\n");
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-m", "initial");
        return dir;
    }

    /**
     * Run a git (or other) command synchronously in the given directory.
     * Throws if exit code is non-zero.
     */
    private void run(Path cwd, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Command failed (exit=" + exit + "): "
                    + String.join(" ", args) + "\n" + out);
        }
    }

    /** Return the total number of commits in HEAD's history. */
    private int countCommits(Path gitRoot) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "rev-list", "--count", "HEAD");
        pb.directory(gitRoot.toFile());
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes()).strip();
        proc.waitFor();
        return Integer.parseInt(out);
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
        public boolean tryAcquireWorkspaceLock(String workspace, String workerId, long ttlSeconds) {
            return true;
        }

        @Override
        public void releaseWorkspaceLock(String workspace, String workerId) {
            // no-op
        }

        void clear() {
            queues.clear();
            locks.clear();
        }

        record LeaseState(String owner, LocalDateTime expireAt) {}
    }
}
