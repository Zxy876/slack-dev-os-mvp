package com.asyncaiflow.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.asyncaiflow.domain.entity.ActionEntity;
import com.asyncaiflow.domain.entity.WorkflowEntity;
import com.asyncaiflow.domain.enums.ActionStatus;
import com.asyncaiflow.domain.enums.WorkflowStatus;
import com.asyncaiflow.mapper.ActionMapper;
import com.asyncaiflow.mapper.WorkflowMapper;
import com.asyncaiflow.service.queue.ActionQueueService;
import com.asyncaiflow.support.ApiException;
import com.asyncaiflow.web.dto.DevOsApplyPatchRequest;
import com.asyncaiflow.web.dto.DevOsApplyPatchResponse;
import com.asyncaiflow.web.dto.DevOsGitCommitRequest;
import com.asyncaiflow.web.dto.DevOsGitCommitResponse;
import com.asyncaiflow.web.dto.DevOsInterruptRequest;
import com.asyncaiflow.web.dto.DevOsInterruptResponse;
import com.asyncaiflow.web.dto.DevOsProposeFixRequest;
import com.asyncaiflow.web.dto.DevOsProposeFixResponse;
import com.asyncaiflow.web.dto.DevOsRunTestRequest;
import com.asyncaiflow.web.dto.DevOsRunTestResponse;
import com.asyncaiflow.web.dto.DevOsStartRequest;
import com.asyncaiflow.web.dto.DevOsStartResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * DevOsService — Slack Dev OS 中断处理层（Syscall Gateway）
 *
 * 职责：
 *   1. 接收 Slack 用户指令（Interrupt）
 *   2. 初始化 PCB（Action Entity）
 *   3. 将任务推入 Redis 能力队列（devos_chat）
 *
 * OS 类比：
 *   - Workflow  = 进程组 (Process Group)
 *   - Action    = 进程控制块 (PCB)
 *   - Queue     = 就绪队列 (Ready Queue)
 */
@Service
public class DevOsService {

    /** 固定 action type，对应 devos_chat Worker 的 capability */
    public static final String DEVOS_CHAT_ACTION_TYPE = "devos_chat";

    private final WorkflowMapper workflowMapper;
    private final ActionMapper actionMapper;
    private final ActionQueueService actionQueueService;
    private final ActionService actionService;
    private final ObjectMapper objectMapper;

    public DevOsService(
            WorkflowMapper workflowMapper,
            ActionMapper actionMapper,
            ActionQueueService actionQueueService,
            ActionService actionService,
            ObjectMapper objectMapper) {
        this.workflowMapper = workflowMapper;
        this.actionMapper = actionMapper;
        this.actionQueueService = actionQueueService;
        this.actionService = actionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理 /devos start 系统调用：
     *   1. 创建 Workflow（进程组）
     *   2. 创建 Action (PCB)，状态为 QUEUED
     *   3. 推入 Redis devos_chat 队列
     */
    @Transactional
    public DevOsStartResponse start(DevOsStartRequest request) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 创建 Workflow（进程组）
        WorkflowEntity workflow = new WorkflowEntity();
        String workflowName = "devos:" + truncate(request.text(), 80);
        workflow.setName(workflowName);
        workflow.setDescription("Slack Dev OS session — " + request.slackThreadId());
        workflow.setStatus(WorkflowStatus.CREATED.name());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowMapper.insert(workflow);

        // 2. 构造 payload JSON: {user_text, slack_thread_id[, repo_path, file_path, write_intent, workspace_key, mode, replace_from, replace_to]}
        String payload = buildPayload(request.text(), request.slackThreadId(),
                request.repoPath(), request.filePath(),
                request.writeIntent(), request.workspaceKey(),
                request.mode(), request.replaceFrom(), request.replaceTo());

        // 3. 创建 Action (PCB)
        // Context Restore：若调用方提供 prevActionId，继承其 notepad_ref（L2 寄存器恢复）
        // B-007: 必须与当前 slackThreadId 属于同一 thread，否则抛出 403
        String inheritedNotepadRef = resolveNotepadRef(request.prevActionId(), request.slackThreadId());

        ActionEntity action = new ActionEntity();
        action.setWorkflowId(workflow.getId());
        action.setType(DEVOS_CHAT_ACTION_TYPE);
        action.setStatus(ActionStatus.QUEUED.name());
        action.setPayload(payload);
        action.setSlackThreadId(request.slackThreadId());
        action.setNotepadRef(inheritedNotepadRef);
        action.setRetryCount(0);
        action.setMaxRetryCount(2);           // 失败最多重试 2 次
        action.setBackoffSeconds(5);
        action.setExecutionTimeoutSeconds(120);
        action.setLeaseRenewSuccessCount(0);
        action.setLeaseRenewFailureCount(0);
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        actionMapper.insert(action);

        // 4. 推入 Redis 能力队列（devos_chat）
        actionQueueService.enqueue(action, DEVOS_CHAT_ACTION_TYPE);

        return new DevOsStartResponse(
                action.getId(),
                workflow.getId(),
                action.getStatus(),
                request.slackThreadId()
        );
    }

    /**
     * B-003 — 用户中断 syscall。
     *
     * 将指定 Action 强制转为 FAILED。
     * RUNNING / QUEUED / RETRY_WAIT / BLOCKED 状态均可被中断。
     * 终态 Action （SUCCEEDED / FAILED / DEAD_LETTER）不可被中断，返回 409 CONFLICT。
     *
     * B-007: 请求方的 slackThreadId 必须与目标 Action 的 slackThreadId 一致。
     * 跨 thread 操作返回 403 FORBIDDEN，目标 Action 状态不变。
     */
    public DevOsInterruptResponse interrupt(DevOsInterruptRequest request) {
        // B-007: Ownership check — 目标 Action 必须属于请求方的 slackThread
        ActionEntity target = actionMapper.selectById(request.actionId());
        if (target == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Action not found: " + request.actionId());
        }
        String targetThreadId = target.getSlackThreadId();
        if (targetThreadId == null || !request.slackThreadId().equals(targetThreadId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Cross-thread interrupt denied: action " + request.actionId()
                    + " belongs to a different slackThread");
        }
        return actionService.interruptAction(request.actionId(), request.reason());
    }

    private String buildPayload(String userText, String slackThreadId,
                                String repoPath, String filePath,
                                Boolean writeIntent, String workspaceKey,
                                String mode, String replaceFrom, String replaceTo) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("user_text", userText);
        node.put("slack_thread_id", slackThreadId);
        if (repoPath != null && !repoPath.isBlank()) {
            node.put("repo_path", repoPath);
        }
        if (filePath != null && !filePath.isBlank()) {
            node.put("file_path", filePath);
        }
        if (Boolean.TRUE.equals(writeIntent)) {
            node.put("write_intent", true);
        }
        if (workspaceKey != null && !workspaceKey.isBlank()) {
            node.put("workspace_key", workspaceKey);
        }
        // B-017 patch preview fields
        if (mode != null && !mode.isBlank()) {
            node.put("mode", mode);
        }
        if (replaceFrom != null && !replaceFrom.isBlank()) {
            node.put("replace_from", replaceFrom);
        }
        if (replaceTo != null && !replaceTo.isBlank()) {
            node.put("replace_to", replaceTo);
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Fallback: plain text (should never happen)
            return "{\"user_text\":\"" + userText.replace("\"", "'") + "\",\"slack_thread_id\":\"" + slackThreadId + "\"}";
        }
    }

    /**
     * Context Restore 辅助：查询上一个 Action 的 notepad_ref。
     * 仅在 prevActionId 非 null 时查询。
     *
     * B-007 Ownership Check：若 prevAction 属于不同的 slackThread，招强拒绝（403 FORBIDDEN）。
     * 防止跨 thread notepad 泄露；找不到 prevAction 则 fallback null（创建路径健壮）。
     */
    private String resolveNotepadRef(Long prevActionId, String currentSlackThreadId) {
        if (prevActionId == null) {
            return null;
        }
        ActionEntity prev = actionMapper.selectById(prevActionId);
        if (prev == null) {
            return null;
        }
        // B-007: Ownership guard — prevAction 必须属于当前 slackThread
        if (!currentSlackThreadId.equals(prev.getSlackThreadId())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Cross-thread context restore denied: prevActionId " + prevActionId
                    + " belongs to a different slackThread");
        }
        return prev.getNotepadRef();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B-018 Human Confirm Apply Patch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply a previously generated patch preview to the real file.
     *
     * Safety invariants:
     *  1. confirm must be true
     *  2. previewAction must exist and belong to the same slackThreadId (B-007)
     *  3. previewAction must be SUCCEEDED
     *  4. patchPreview metadata must be present in action log result (or payload fallback)
     *  5. filePath must be relative, no "..", resolved within repoPath
     *  6. replaceFrom must exist in current file
     *  7. If originalSha256 present: current file hash must match (stale-patch guard)
     *  8. Only first occurrence is replaced (idempotent for deterministic patches)
     *  9. No git commit, no git push, no shell execution
     */
    public DevOsApplyPatchResponse applyPatch(DevOsApplyPatchRequest request) {
        // 1. confirm guard
        if (!Boolean.TRUE.equals(request.confirm())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "confirm must be true to apply patch; set confirm=true to proceed");
        }

        // 2. load preview action
        ActionEntity preview = actionMapper.selectById(request.previewActionId());
        if (preview == null) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "Preview action not found: " + request.previewActionId());
        }

        // 3. B-007 ownership check
        if (preview.getSlackThreadId() == null
                || !request.slackThreadId().equals(preview.getSlackThreadId())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Cross-thread apply denied: action " + request.previewActionId()
                    + " belongs to a different slackThread");
        }

        // 4. status must be SUCCEEDED
        if (!ActionStatus.SUCCEEDED.name().equals(preview.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Preview action is not SUCCEEDED (status=" + preview.getStatus()
                    + "); only SUCCEEDED patch previews can be applied");
        }

        // 5. extract patchPreview metadata — action log result first, payload as fallback
        JsonNode patchMeta = extractPatchMeta(request.previewActionId(), preview.getPayload());
        if (patchMeta == null || patchMeta.isNull() || patchMeta.isMissingNode()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No patchPreview metadata found in action result or payload for action "
                    + request.previewActionId()
                    + "; was this action created with mode=patch_preview?");
        }

        String repoPath    = patchMeta.path("repoPath").asText(null);
        String filePath    = patchMeta.path("filePath").asText(null);
        String replaceFrom = patchMeta.path("replaceFrom").asText(null);
        String replaceTo   = patchMeta.path("replaceTo").asText("");
        String storedHash  = patchMeta.path("originalSha256").asText(null);

        if (repoPath == null || filePath == null || replaceFrom == null
                || repoPath.isBlank() || filePath.isBlank() || replaceFrom.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Incomplete patchPreview metadata: repoPath, filePath, replaceFrom are required");
        }

        // 6. path safety validation
        validateApplyPaths(repoPath, filePath);

        // Resolve real file path
        Path realRepo;
        Path candidateFile;
        try {
            realRepo      = Path.of(repoPath).toRealPath();
            candidateFile = realRepo.resolve(filePath).normalize();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Cannot resolve repoPath: " + repoPath + " — " + e.getMessage());
        }
        if (!candidateFile.startsWith(realRepo)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Resolved file path escapes repo boundary");
        }
        if (!Files.isRegularFile(candidateFile)) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "Target file not found: " + filePath);
        }

        // 7. read current file
        byte[] currentBytes;
        String currentContent;
        try {
            currentBytes  = Files.readAllBytes(candidateFile);
            currentContent = new String(currentBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Cannot read target file: " + filePath + " — " + e.getMessage());
        }

        // 8. stale-patch guard: compare file hash against preview-time hash
        if (storedHash != null && !storedHash.isBlank()) {
            String currentHash = sha256Hex(currentBytes);
            if (!storedHash.equals(currentHash)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "File has changed since patch preview was generated (hash mismatch); "
                        + "re-run patch_preview before applying");
            }
        }

        // 9. replaceFrom must exist in current file
        if (!currentContent.contains(replaceFrom)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "replaceFrom text not found in current file; "
                    + "patch cannot be applied (file may have changed)");
        }

        // 10. apply — replace only the first occurrence
        int idx = currentContent.indexOf(replaceFrom);
        String newContent = currentContent.substring(0, idx)
                + replaceTo
                + currentContent.substring(idx + replaceFrom.length());

        try {
            Files.writeString(candidateFile, newContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Cannot write target file: " + filePath + " — " + e.getMessage());
        }

        return new DevOsApplyPatchResponse(
                request.previewActionId(),
                "APPLIED",
                filePath,
                true,
                "Patch applied to " + filePath + "; no git commit was made"
        );
    }

    /**
     * Extract patchPreview JSON from the action log result, with fallback to payload.
     * Returns null if metadata cannot be found.
     */
    private JsonNode extractPatchMeta(Long actionId, String payloadJson) {
        // Primary: action log result (set by worker in B-017 / B-018 metadata fix)
        JsonNode resultNode = actionService.getLatestSucceededResult(actionId);
        if (resultNode != null && !resultNode.isNull() && !resultNode.isMissingNode()) {
            JsonNode meta = resultNode.path("patchPreview");
            if (!meta.isMissingNode() && !meta.isNull() && meta.isObject()) {
                return meta;
            }
        }

        // Fallback: reconstruct from payload (for legacy actions without patchPreview in result)
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            String repoPath    = payload.path("repo_path").asText(null);
            String filePath    = payload.path("file_path").asText(null);
            String replaceFrom = payload.path("replace_from").asText(null);
            String replaceTo   = payload.path("replace_to").asText("");
            if (repoPath == null || filePath == null || replaceFrom == null) {
                return null;
            }
            ObjectNode meta = objectMapper.createObjectNode();
            meta.put("mode", "replace");
            meta.put("repoPath", repoPath);
            meta.put("filePath", filePath);
            meta.put("replaceFrom", replaceFrom);
            meta.put("replaceTo", replaceTo);
            // No originalSha256 in payload fallback — hash check is skipped
            return meta;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Validate that filePath is relative, contains no "..", and resolves inside repoPath.
     * Throws ApiException(400/403) on violation.
     */
    private static void validateApplyPaths(String repoPath, String filePath) {
        if (filePath.startsWith("/") || filePath.startsWith("\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "filePath must be relative, not absolute");
        }
        Path norm = Path.of(filePath).normalize();
        for (Path part : norm) {
            if ("..".equals(part.toString())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "filePath must not contain '..'");
            }
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B-019 Test Command Runner
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Allowlist of safe test commands.
     *
     * Key   = exact command string (must match request.command() exactly)
     * Value = ProcessBuilder args (no shell expansion, no arbitrary injection)
     *
     * DO NOT add "sh -c", "bash -c", or any command that accepts arbitrary args.
     */
    private static final Map<String, List<String>> TEST_COMMAND_ALLOWLIST;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("mvn test -Dspring.profiles.active=local",
                List.of("mvn", "test", "-Dspring.profiles.active=local"));
        m.put("python -m pytest",
                List.of("python3", "-m", "pytest"));
        m.put("bash scripts/secret_scan.sh",
                List.of("bash", "scripts/secret_scan.sh"));
        m.put("bash scripts/run_patch_preview_e2e.sh",
                List.of("bash", "scripts/run_patch_preview_e2e.sh"));
        m.put("bash scripts/run_apply_patch_e2e.sh",
                List.of("bash", "scripts/run_apply_patch_e2e.sh"));
        TEST_COMMAND_ALLOWLIST = Map.copyOf(m);
    }

    /** Maximum excerpt length for stdout/stderr captured output. */
    private static final int MAX_OUTPUT_EXCERPT_CHARS = 8_000;

    /** Default timeout for test commands (seconds). */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** Maximum allowed timeout (seconds). */
    private static final int MAX_TIMEOUT_SECONDS = 180;

    /**
     * B-019 — Run an allowlisted test command in the specified repo directory.
     *
     * Safety invariants:
     *  1. slackThreadId required (audit trail)
     *  2. repoPath must exist and be a directory
     *  3. repoPath canonical path must not be root "/"
     *  4. command must be in TEST_COMMAND_ALLOWLIST (exact match)
     *  5. ProcessBuilder uses explicit args array — no shell expansion
     *  6. cwd = repoPath (no escaping)
     *  7. timeout clamped to [1, 180] seconds
     *  8. test failure (nonzero exit) → FAILED status but HTTP 200; never throws 500
     *  9. No auto-fix, no commit, no push
     */
    public DevOsRunTestResponse runTest(DevOsRunTestRequest request) {
        // 1. Validate repoPath
        Path repoDir;
        try {
            repoDir = Path.of(request.repoPath()).toRealPath();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "repoPath does not exist or cannot be resolved: " + request.repoPath());
        }
        if (!Files.isDirectory(repoDir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "repoPath is not a directory: " + request.repoPath());
        }
        // Guard against running in root
        if (repoDir.getNameCount() == 0 || "/".equals(repoDir.toString())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "repoPath must not be the filesystem root");
        }

        // 2. Validate command against allowlist
        String cmd = request.command().strip();
        List<String> args = TEST_COMMAND_ALLOWLIST.get(cmd);
        if (args == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Command not in allowlist: [" + cmd + "]. Allowed commands: "
                    + String.join(", ", TEST_COMMAND_ALLOWLIST.keySet()));
        }

        // 3. Clamp timeout
        int timeoutSec = request.timeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, Math.min(request.timeoutSeconds(), MAX_TIMEOUT_SECONDS));

        // 4. Run command via ProcessBuilder
        long startMs = System.currentTimeMillis();
        int exitCode;
        String stdoutExcerpt;
        String stderrExcerpt;

        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(repoDir.toFile());
            pb.redirectErrorStream(false);

            Process proc = pb.start();

            // Read stdout and stderr concurrently to avoid blocking on full pipe buffer
            String[] stdoutHolder = {""};
            String[] stderrHolder = {""};

            Thread stdoutReader = new Thread(() -> {
                try (InputStream is = proc.getInputStream()) {
                    stdoutHolder[0] = truncate(new String(is.readAllBytes(), StandardCharsets.UTF_8),
                            MAX_OUTPUT_EXCERPT_CHARS);
                } catch (IOException ignored) {}
            });
            Thread stderrReader = new Thread(() -> {
                try (InputStream is = proc.getErrorStream()) {
                    stderrHolder[0] = truncate(new String(is.readAllBytes(), StandardCharsets.UTF_8),
                            MAX_OUTPUT_EXCERPT_CHARS);
                } catch (IOException ignored) {}
            });

            stdoutReader.start();
            stderrReader.start();

            boolean finished = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                stdoutReader.interrupt();
                stderrReader.interrupt();
                long durationMs = System.currentTimeMillis() - startMs;
                return new DevOsRunTestResponse(
                        "FAILED", -1, durationMs,
                        stdoutHolder[0],
                        "Command timed out after " + timeoutSec + "s",
                        cmd, repoDir.toString()
                );
            }

            stdoutReader.join(5_000);
            stderrReader.join(5_000);

            exitCode = proc.exitValue();
            stdoutExcerpt = stdoutHolder[0];
            stderrExcerpt = stderrHolder[0];

        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to start test command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Test command execution was interrupted");
        }

        long durationMs = System.currentTimeMillis() - startMs;
        String status = exitCode == 0 ? "PASSED" : "FAILED";

        return new DevOsRunTestResponse(
                status, exitCode, durationMs,
                stdoutExcerpt, stderrExcerpt,
                cmd, repoDir.toString()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B-020 Propose Fix — observe failure → queue fix_preview action
    // ─────────────────────────────────────────────────────────────────────────

    /** Maximum chars preserved for failure context fields in the payload. */
    private static final int MAX_FAILURE_CONTEXT_CHARS = 8_000;

    /** Maximum chars preserved for the human hint field. */
    private static final int MAX_HINT_CHARS = 2_000;

    /**
     * B-020 — Create a fix_preview Action from test failure evidence.
     *
     * Safety invariants:
     *  1. No filesystem mutation (neither here nor in the queued action itself)
     *  2. No automatic apply/commit/push
     *  3. No shell execution
     *  4. stdout/stderr/hint are truncated before embedding in payload
     *  5. Worker will validate repoPath/filePath safety independently
     *  6. Returns actionId immediately; caller polls for the fix plan result
     */
    @Transactional
    public DevOsProposeFixResponse proposeFix(DevOsProposeFixRequest request) {
        LocalDateTime now = LocalDateTime.now();

        // 1. Create Workflow
        WorkflowEntity workflow = new WorkflowEntity();
        String workflowName = "devos:fix:" + truncate(request.filePath(), 60);
        workflow.setName(workflowName);
        workflow.setDescription("Fix proposal for " + request.filePath()
                + " — thread " + request.slackThreadId());
        workflow.setStatus(WorkflowStatus.CREATED.name());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowMapper.insert(workflow);

        // 2. Build payload with failure context (all string fields truncated)
        String payload = buildFixPreviewPayload(request);

        // 3. Create Action
        ActionEntity action = new ActionEntity();
        action.setWorkflowId(workflow.getId());
        action.setType(DEVOS_CHAT_ACTION_TYPE);
        action.setStatus(ActionStatus.QUEUED.name());
        action.setPayload(payload);
        action.setSlackThreadId(request.slackThreadId());
        action.setNotepadRef(null);   // no context restore for fix proposals
        action.setRetryCount(0);
        action.setMaxRetryCount(2);
        action.setBackoffSeconds(5);
        action.setExecutionTimeoutSeconds(120);
        action.setLeaseRenewSuccessCount(0);
        action.setLeaseRenewFailureCount(0);
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        actionMapper.insert(action);

        // 4. Enqueue
        actionQueueService.enqueue(action, DEVOS_CHAT_ACTION_TYPE);

        return new DevOsProposeFixResponse(
                action.getId(),
                workflow.getId(),
                action.getStatus(),
                request.slackThreadId(),
                "fix proposal queued — action " + action.getId()
        );
    }

    private String buildFixPreviewPayload(DevOsProposeFixRequest request) {
        ObjectNode node = objectMapper.createObjectNode();
        // user_text is used by the worker for logging; keep it human-readable
        node.put("user_text",
                "Fix suggestion for " + request.filePath()
                + " (test " + (request.testStatus() != null ? request.testStatus() : "FAILED")
                + " exitCode=" + (request.exitCode() != null ? request.exitCode() : -1) + ")");
        node.put("slack_thread_id", request.slackThreadId());
        node.put("mode", "fix_preview");
        node.put("repo_path", request.repoPath());
        node.put("file_path", request.filePath());

        // Nested failure_context object (all fields truncated for safety)
        ObjectNode failureCtx = objectMapper.createObjectNode();
        failureCtx.put("test_status",
                request.testStatus() != null ? request.testStatus() : "FAILED");
        failureCtx.put("exit_code",
                request.exitCode() != null ? request.exitCode() : -1);
        failureCtx.put("stdout_excerpt",
                truncate(request.stdoutExcerpt(), MAX_FAILURE_CONTEXT_CHARS));
        failureCtx.put("stderr_excerpt",
                truncate(request.stderrExcerpt(), MAX_FAILURE_CONTEXT_CHARS));
        failureCtx.put("hint",
                truncate(request.hint(), MAX_HINT_CHARS));
        node.set("failure_context", failureCtx);

        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Should never happen with well-formed ObjectNode
            return "{\"user_text\":\"fix_preview\",\"mode\":\"fix_preview\"}";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B-021 Human Git Commit Snapshot
    // ─────────────────────────────────────────────────────────────────────────

    /** Maximum commit message length (chars). */
    private static final int MAX_COMMIT_MSG_CHARS = 200;

    /** Maximum diff excerpt length (chars). */
    private static final int MAX_DIFF_EXCERPT_CHARS = 2_000;

    /** Timeout for git subprocess calls (seconds). */
    private static final int GIT_TIMEOUT_SECONDS = 30;

    /**
     * B-021 — Create a local git commit in the specified repo.
     *
     * Safety invariants:
     *  1. confirm must be true
     *  2. repoPath must exist, be a directory, and be inside a git repo
     *  3. Commit message length must be ≤ 200 chars
     *  4. ProcessBuilder with explicit args — no shell expansion, no sh -c
     *  5. cwd = git top-level of repoPath (resolved by git rev-parse --show-toplevel)
     *  6. If working tree is clean → return NO_CHANGES, HTTP 200
     *  7. git add -A, then git commit -m <message>
     *  8. Return commit hash, changed files, diff excerpt
     *  9. NEVER git push, NEVER modify remote, NEVER write global git config
     */
    public DevOsGitCommitResponse gitCommit(DevOsGitCommitRequest request) {
        // 1. confirm guard
        if (!Boolean.TRUE.equals(request.confirm())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "confirm must be true to create a commit; set confirm=true to proceed");
        }

        // 2. Validate commit message length
        if (request.message().length() > MAX_COMMIT_MSG_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "commit message must be " + MAX_COMMIT_MSG_CHARS + " chars or fewer "
                    + "(got " + request.message().length() + ")");
        }

        // 3. Resolve and validate repoPath
        Path repoDir;
        try {
            repoDir = Path.of(request.repoPath()).toRealPath();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "repoPath does not exist or cannot be resolved: " + request.repoPath());
        }
        if (!Files.isDirectory(repoDir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "repoPath is not a directory: " + request.repoPath());
        }
        if (repoDir.getNameCount() == 0 || "/".equals(repoDir.toString())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "repoPath must not be the filesystem root");
        }

        // 4. Verify it is a git repository and resolve top-level
        String topLevel = runGitCommand(repoDir,
                List.of("git", "rev-parse", "--show-toplevel"));
        if (topLevel == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "repoPath is not inside a git repository: " + repoDir);
        }
        Path gitRoot;
        try {
            gitRoot = Path.of(topLevel.strip()).toRealPath();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Cannot resolve git top-level: " + topLevel);
        }

        // 5. Check for uncommitted changes (git status --porcelain)
        String porcelain = runGitCommand(gitRoot, List.of("git", "status", "--porcelain"));
        if (porcelain == null || porcelain.isBlank()) {
            return new DevOsGitCommitResponse(
                    "NO_CHANGES", null, List.of(),
                    request.message(),
                    "Working tree is clean — nothing to commit.",
                    gitRoot.toString()
            );
        }

        // 6. Collect changed file names from porcelain output
        List<String> changedFiles = new ArrayList<>();
        for (String line : porcelain.split("\n")) {
            // Porcelain format: XY<space>filename (positions 0-1=status, 2=space, 3+=name)
            // Do NOT trim the line before taking substring — it would shift the positions
            if (line.length() >= 4) {
                String name = line.substring(3).trim();
                // For renames "old -> new", take the new name
                if (name.contains(" -> ")) {
                    name = name.substring(name.lastIndexOf(" -> ") + 4).trim();
                }
                if (!name.isEmpty()) {
                    changedFiles.add(name);
                }
            }
        }

        // 7. Collect diff excerpt (before git add, so diff reflects working tree)
        String diffStat = runGitCommand(gitRoot, List.of("git", "diff", "--stat"));
        String diffExcerpt = truncate(diffStat != null ? diffStat : "", MAX_DIFF_EXCERPT_CHARS);

        // 8. Stage all changes
        String addOut = runGitCommand(gitRoot, List.of("git", "add", "-A"));
        // addOut may be null on error; proceed — commit will fail if add did not work

        // 9. Create commit (no push)
        String commitOut = runGitCommand(gitRoot,
                List.of("git", "commit", "-m", request.message()));
        if (commitOut == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "git commit failed — check that user.name and user.email are configured "
                    + "in the repository (git config user.email / user.name inside repoPath)");
        }

        // 10. Retrieve new commit hash
        String head = runGitCommand(gitRoot, List.of("git", "rev-parse", "HEAD"));
        String commitHash = head != null ? head.strip() : null;

        return new DevOsGitCommitResponse(
                "COMMITTED",
                commitHash,
                changedFiles,
                request.message(),
                diffExcerpt,
                gitRoot.toString()
        );
    }

    /**
     * Execute a git command via ProcessBuilder in the given directory.
     *
     * Returns stdout content (trimmed) on success, null on nonzero exit or error.
     * Never uses shell expansion. cwd is restricted to the supplied directory.
     */
    private String runGitCommand(Path cwd, List<String> args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            String[] stdoutHolder = {null};
            Thread reader = new Thread(() -> {
                try (InputStream is = proc.getInputStream()) {
                    stdoutHolder[0] = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {}
            });
            reader.start();

            boolean finished = proc.waitFor(GIT_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            reader.join(3_000);

            int exit = proc.exitValue();
            if (exit != 0) {
                return null;
            }
            return stdoutHolder[0] != null ? stdoutHolder[0] : "";
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
