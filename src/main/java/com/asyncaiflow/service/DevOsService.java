package com.asyncaiflow.service;

import java.time.LocalDateTime;

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
import com.asyncaiflow.web.dto.DevOsInterruptRequest;
import com.asyncaiflow.web.dto.DevOsInterruptResponse;
import com.asyncaiflow.web.dto.DevOsStartRequest;
import com.asyncaiflow.web.dto.DevOsStartResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
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
}
