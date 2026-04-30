package com.asyncaiflow.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.asyncaiflow.domain.entity.ActionDependencyEntity;
import com.asyncaiflow.domain.entity.ActionEntity;
import com.asyncaiflow.domain.entity.ActionLogEntity;
import com.asyncaiflow.domain.entity.WorkerEntity;
import com.asyncaiflow.domain.entity.WorkflowEntity;
import com.asyncaiflow.domain.enums.ActionStatus;
import com.asyncaiflow.mapper.ActionDependencyMapper;
import com.asyncaiflow.mapper.ActionLogMapper;
import com.asyncaiflow.mapper.ActionMapper;
import com.asyncaiflow.service.queue.ActionQueueService;
import com.asyncaiflow.support.ApiException;
import com.asyncaiflow.support.RuntimeStatusView;
import com.asyncaiflow.web.dto.ActionAssignmentResponse;
import com.asyncaiflow.web.dto.ActionExecutionResponse;
import com.asyncaiflow.web.dto.ActionLogEntryResponse;
import com.asyncaiflow.web.dto.ActionResponse;
import com.asyncaiflow.web.dto.CreateActionRequest;
import com.asyncaiflow.web.dto.DevOsInterruptResponse;
import com.asyncaiflow.web.dto.SubmitActionResultRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

@Service
public class ActionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionService.class);

    private final ActionMapper actionMapper;
    private final ActionDependencyMapper actionDependencyMapper;
    private final ActionLogMapper actionLogMapper;
    private final ActionCapabilityResolver actionCapabilityResolver;
    private final WorkflowService workflowService;
    private final WorkerService workerService;
    private final ActionQueueService actionQueueService;
    private final ObjectMapper objectMapper;

    @Value("${asyncaiflow.action.default-max-retry-count:3}")
    private int defaultMaxRetryCount;

    @Value("${asyncaiflow.action.default-backoff-seconds:5}")
    private int defaultBackoffSeconds;

    @Value("${asyncaiflow.action.default-execution-timeout-seconds:300}")
    private int defaultExecutionTimeoutSeconds;

    @Value("${asyncaiflow.scheduler.max-parallel-actions-per-workflow:0}")
    private int maxParallelActionsPerWorkflow;

    public ActionService(
            ActionMapper actionMapper,
            ActionDependencyMapper actionDependencyMapper,
            ActionLogMapper actionLogMapper,
            ActionCapabilityResolver actionCapabilityResolver,
            WorkflowService workflowService,
            WorkerService workerService,
            ActionQueueService actionQueueService,
            ObjectMapper objectMapper
    ) {
        this.actionMapper = actionMapper;
        this.actionDependencyMapper = actionDependencyMapper;
        this.actionLogMapper = actionLogMapper;
        this.actionCapabilityResolver = actionCapabilityResolver;
        this.workflowService = workflowService;
        this.workerService = workerService;
        this.actionQueueService = actionQueueService;
        this.objectMapper = objectMapper;
    }

    public ActionExecutionResponse getActionExecution(Long actionId) {
        ActionEntity action = requireAction(actionId);
        List<ActionLogEntity> actionLogs = loadActionLogs(actionId);
        ActionLogEntity latestLog = actionLogs.isEmpty() ? null : actionLogs.get(actionLogs.size() - 1);
        String status = RuntimeStatusView.actionStatus(action.getStatus());
        return new ActionExecutionResponse(
                action.getId(),
                action.getWorkflowId(),
                action.getType(),
            status,
            action.getWorkerId(),
            resolveStartedAt(action),
            resolveFinishedAt(status, action),
                parseJsonContent(action.getPayload()),
            latestLog == null ? null : parseJsonContent(latestLog.getResult()),
            action.getErrorMessage(),
            actionLogs.stream().map(this::toActionLogEntry).toList()
        );
    }

    @Transactional
    public ActionResponse createAction(CreateActionRequest request) {
        WorkflowEntity workflow = workflowService.requireWorkflow(request.workflowId());
        List<Long> upstreamActionIds = normalizeUpstreamActionIds(request.upstreamActionIds());
        validateUpstreamActions(workflow.getId(), upstreamActionIds);

        LocalDateTime now = LocalDateTime.now();
        ActionEntity action = new ActionEntity();
        action.setWorkflowId(workflow.getId());
        action.setType(request.type().trim());
        action.setStatus(upstreamActionIds.isEmpty() ? ActionStatus.QUEUED.name() : ActionStatus.BLOCKED.name());
        action.setPayload(request.payload());
        action.setRetryCount(0);
        action.setMaxRetryCount(normalizeNonNegative(request.maxRetryCount(), defaultMaxRetryCount));
        action.setBackoffSeconds(normalizeNonNegative(request.backoffSeconds(), defaultBackoffSeconds));
        action.setExecutionTimeoutSeconds(normalizePositive(request.executionTimeoutSeconds(), defaultExecutionTimeoutSeconds));
        action.setLeaseExpireAt(null);
        action.setNextRunAt(null);
        action.setClaimTime(null);
        action.setFirstRenewTime(null);
        action.setLastRenewTime(null);
        action.setSubmitTime(null);
        action.setReclaimTime(null);
        action.setLeaseRenewSuccessCount(0);
        action.setLeaseRenewFailureCount(0);
        action.setLastLeaseRenewAt(null);
        action.setExecutionStartedAt(null);
        action.setLastExecutionDurationMs(null);
        action.setLastReclaimReason(null);
        action.setSlackThreadId(request.slackThreadId());
        action.setNotepadRef(null);
        action.setCreatedAt(now);
        action.setUpdatedAt(now);

        String requiredCapability = actionCapabilityResolver.resolveRequiredCapability(action.getType());
        if (requiredCapability.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Action type resolves to empty capability: " + action.getType());
        }

        actionMapper.insert(action);

        if (wouldCreateCycle(action.getId(), upstreamActionIds)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Adding these upstream dependencies would create a directed cycle in the DAG");
        }

        for (Long upstreamActionId : upstreamActionIds) {
            ActionDependencyEntity dependency = new ActionDependencyEntity();
            dependency.setWorkflowId(workflow.getId());
            dependency.setUpstreamActionId(upstreamActionId);
            dependency.setDownstreamActionId(action.getId());
            actionDependencyMapper.insert(dependency);
        }

        if (upstreamActionIds.isEmpty()) {
            enqueueAction(action, requiredCapability);
        } else if (allDependenciesSucceeded(action.getId())) {
            transitionState(action, ActionStatus.QUEUED);
            action.setUpdatedAt(LocalDateTime.now());
            actionMapper.updateById(action);
            enqueueAction(action, requiredCapability);
        }

        workflowService.refreshStatus(workflow.getId());
        return toResponse(actionMapper.selectById(action.getId()));
    }

    @Transactional
    public Optional<ActionAssignmentResponse> pollAction(String workerId) {
        WorkerEntity worker = workerService.touchHeartbeat(workerId);
        List<String> capabilities = workerService.readCapabilities(worker);
        if (capabilities.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Worker has no capabilities");
        }

        for (int attempt = 0; attempt < 20; attempt++) {
            Optional<Long> candidateActionId = actionQueueService.claimNextAction(capabilities, workerId);
            if (candidateActionId.isEmpty()) {
                return Optional.empty();
            }

            ActionEntity action = actionMapper.selectById(candidateActionId.get());
            if (action == null || !ActionStatus.QUEUED.name().equals(action.getStatus())) {
                actionQueueService.releaseLock(candidateActionId.get());
                continue;
            }

            String requiredCapability = actionCapabilityResolver.resolveRequiredCapability(action.getType());
            if (requiredCapability.isBlank() || !capabilities.contains(requiredCapability)) {
                actionQueueService.releaseLock(candidateActionId.get());
                continue;
            }

            // B-006 — Workspace single-writer mutex: prevent concurrent writes to the same repo/workspace.
            // If write_intent=true and workspace_key is set, attempt SETNX on the workspace lock.
            // On failure: release the per-action lock, re-enqueue, and try next candidate.
            int executionTimeoutSeconds = normalizePositive(action.getExecutionTimeoutSeconds(), defaultExecutionTimeoutSeconds);
            String wsKey = extractWorkspaceKey(action.getPayload());
            if (extractWriteIntent(action.getPayload()) && wsKey != null) {
                boolean acquired = actionQueueService.tryAcquireWorkspaceLock(
                        wsKey, action.getId().toString(), executionTimeoutSeconds);
                if (!acquired) {
                    actionQueueService.releaseLock(candidateActionId.get());
                    actionQueueService.enqueue(action, requiredCapability);
                    continue;
                }
            }

            String assignmentPayload = materializeAssignmentPayload(action);

            transitionState(action, ActionStatus.RUNNING);
            LocalDateTime now = LocalDateTime.now();
            action.setWorkerId(workerId);
            action.setLeaseExpireAt(now.plusSeconds(executionTimeoutSeconds));
            action.setNextRunAt(null);
            action.setClaimTime(now);
            action.setFirstRenewTime(null);
            action.setLastRenewTime(null);
            action.setSubmitTime(null);
            action.setReclaimTime(null);
            action.setExecutionStartedAt(now);
            action.setLastExecutionDurationMs(null);
            action.setLastReclaimReason(null);
            action.setUpdatedAt(now);
            actionMapper.updateById(action);
            actionQueueService.refreshActionLock(action.getId(), workerId, executionTimeoutSeconds);
            workflowService.refreshStatus(action.getWorkflowId());

            return Optional.of(new ActionAssignmentResponse(
                    action.getId(),
                    action.getWorkflowId(),
                    action.getType(),
                    assignmentPayload,
                    action.getRetryCount(),
                    action.getLeaseExpireAt(),
                    action.getSlackThreadId(),
                    action.getNotepadRef()
            ));
        }

        return Optional.empty();
    }

    @Transactional
    public ActionResponse submitResult(SubmitActionResultRequest request) {
        workerService.touchHeartbeat(request.workerId());
        ActionEntity action = requireAction(request.actionId());
        ActionStatus currentStatus = parseActionStatus(action.getStatus());
        if (currentStatus != ActionStatus.RUNNING) {
            if (isSafeDuplicateResult(action)) {
                return toResponse(action);
            }
            throw new ApiException(HttpStatus.CONFLICT, "Action is not in RUNNING status: " + action.getId());
        }

        if (action.getWorkerId() == null || !action.getWorkerId().equals(request.workerId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Action is not assigned to worker: " + request.workerId());
        }

        ActionStatus resultOutcome = parseResultOutcome(request.status());
        LocalDateTime now = LocalDateTime.now();

        // Ignore stale submission if lease already expired. Expired leases are reclaimed by scheduler loop.
        if (action.getLeaseExpireAt() != null && action.getLeaseExpireAt().isBefore(now)) {
            return toResponse(action);
        }

        if (resultOutcome == ActionStatus.SUCCEEDED) {
            transitionState(action, ActionStatus.SUCCEEDED);
            action.setErrorMessage(request.errorMessage());
            action.setLeaseExpireAt(null);
            action.setNextRunAt(null);
            action.setSubmitTime(now);
            action.setReclaimTime(null);
            completeExecutionAttempt(action, now);
            action.setLastReclaimReason(null);
            action.setNotepadRef(extractNotepadFromResult(request.result()));
            action.setUpdatedAt(now);
            actionMapper.updateById(action);

            recordActionLog(action.getId(), request.workerId(), request.result(), ActionStatus.SUCCEEDED.name(), now);
            actionQueueService.releaseLock(action.getId());
            releaseWorkspaceLockIfApplicable(action);
            triggerDownstreamActions(action);
            workflowService.refreshStatus(action.getWorkflowId());
            return toResponse(actionMapper.selectById(action.getId()));
        }

        String failureMessage = (request.errorMessage() == null || request.errorMessage().isBlank())
            ? "Worker execution failed"
            : request.errorMessage();

        action.setSubmitTime(now);
        action.setReclaimTime(null);
        action.setNotepadRef(extractNotepadFromResult(request.result()));

        applyFailureWithRetry(
                action,
                now,
                request.workerId(),
                request.result(),
                failureMessage,
                ActionStatus.FAILED.name(),
                ActionStatus.FAILED,
                null
        );

        workflowService.refreshStatus(action.getWorkflowId());
        return toResponse(actionMapper.selectById(action.getId()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public ActionResponse renewLease(Long actionId, String workerId) {
        workerService.touchHeartbeat(workerId);
        ActionEntity action = requireAction(actionId);
        LocalDateTime now = LocalDateTime.now();

        ActionStatus currentStatus = parseActionStatus(action.getStatus());
        if (currentStatus != ActionStatus.RUNNING) {
            maybeMarkRenewFailure(action, workerId, now);
            throw new ApiException(HttpStatus.CONFLICT, "Action is not in RUNNING status: " + action.getId());
        }
        if (action.getWorkerId() == null || !action.getWorkerId().equals(workerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Action is not assigned to worker: " + workerId);
        }

        if (action.getLeaseExpireAt() != null && action.getLeaseExpireAt().isBefore(now)) {
            markRenewFailure(action);
            action.setUpdatedAt(now);
            actionMapper.updateById(action);
            throw new ApiException(HttpStatus.CONFLICT, "Action lease already expired: " + action.getId());
        }

        int executionTimeoutSeconds = normalizePositive(action.getExecutionTimeoutSeconds(), defaultExecutionTimeoutSeconds);
        action.setLeaseExpireAt(now.plusSeconds(executionTimeoutSeconds));
        markRenewSuccess(action, now);
        action.setUpdatedAt(now);
        actionMapper.updateById(action);
        actionQueueService.refreshActionLock(action.getId(), workerId, executionTimeoutSeconds);

        return toResponse(actionMapper.selectById(action.getId()));
    }

    @Transactional
    public int reclaimExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        List<ActionEntity> expiredRunningActions = actionMapper.selectList(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getStatus, ActionStatus.RUNNING.name())
                .isNotNull(ActionEntity::getLeaseExpireAt)
                .le(ActionEntity::getLeaseExpireAt, now));

        int reclaimedCount = 0;
        Set<Long> impactedWorkflows = new HashSet<>();
        for (ActionEntity action : expiredRunningActions) {
            ActionEntity latest = actionMapper.selectById(action.getId());
            if (latest == null) {
                continue;
            }
            if (!ActionStatus.RUNNING.name().equals(latest.getStatus())) {
                continue;
            }
            if (latest.getLeaseExpireAt() == null || latest.getLeaseExpireAt().isAfter(now)) {
                continue;
            }

            applyFailureWithRetry(
                    latest,
                    now,
                    latest.getWorkerId(),
                    "lease timeout",
                    "Action lease expired before worker result submission",
                    "TIMEOUT",
                    ActionStatus.DEAD_LETTER,
                    "LEASE_EXPIRED"
            );

            impactedWorkflows.add(latest.getWorkflowId());
            reclaimedCount++;
        }

        for (Long workflowId : impactedWorkflows) {
            workflowService.refreshStatus(workflowId);
        }
        return reclaimedCount;
    }

    @Transactional
    public int enqueueDueRetries() {
        LocalDateTime now = LocalDateTime.now();
        List<ActionEntity> dueRetries = actionMapper.selectList(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getStatus, ActionStatus.RETRY_WAIT.name())
                .isNotNull(ActionEntity::getNextRunAt)
                .le(ActionEntity::getNextRunAt, now));

        int enqueuedCount = 0;
        Set<Long> impactedWorkflows = new HashSet<>();
        for (ActionEntity action : dueRetries) {
            ActionEntity latest = actionMapper.selectById(action.getId());
            if (latest == null) {
                continue;
            }
            if (!ActionStatus.RETRY_WAIT.name().equals(latest.getStatus())) {
                continue;
            }
            if (latest.getNextRunAt() == null || latest.getNextRunAt().isAfter(now)) {
                continue;
            }

            transitionState(latest, ActionStatus.QUEUED);
            latest.setWorkerId(null);
            latest.setLeaseExpireAt(null);
            latest.setNextRunAt(null);
            latest.setUpdatedAt(now);
            actionMapper.updateById(latest);
            enqueueAction(
                    latest,
                    actionCapabilityResolver.resolveRequiredCapability(latest.getType()));
            impactedWorkflows.add(latest.getWorkflowId());
            enqueuedCount++;
        }

        for (Long workflowId : impactedWorkflows) {
            workflowService.refreshStatus(workflowId);
        }
        return enqueuedCount;
    }

    /**
     * B-003 — 用户中断 syscall。
     *
     * 将 RUNNING / QUEUED / RETRY_WAIT / BLOCKED 状态的 Action 转为 FAILED，
     * 释放 worker lock，记录中断原因，阻止任务被再次调度或下游解锁。
     *
     * 不变量：
     *   - 终态（SUCCEEDED / FAILED / DEAD_LETTER）不可被中断
     *   - 被中断的 Action 不会再被 poll（pollAction 检查 DB status 为 FAILED 则跳过）
     *   - 被中断的 Action 不会再被 retry（enqueueDueRetries 检查 RETRY_WAIT 状态）
     *   - 被中断的 Action 不会解锁下游（下游只在 SUCCEEDED 时解锁）
     */
    @Transactional
    public DevOsInterruptResponse interruptAction(Long actionId, String reason) {
        ActionEntity action = requireAction(actionId);
        ActionStatus currentStatus = parseActionStatus(action.getStatus());

        if (currentStatus.isTerminal()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Action is already in terminal state and cannot be interrupted: " + actionId);
        }

        String interruptMessage = (reason == null || reason.isBlank())
                ? "USER_INTERRUPTED"
                : "USER_INTERRUPTED: " + reason;

        LocalDateTime now = LocalDateTime.now();
        transitionState(action, ActionStatus.FAILED);
        action.setErrorMessage(interruptMessage);
        action.setLastReclaimReason("USER_INTERRUPTED");
        action.setReclaimTime(now);
        action.setLeaseExpireAt(null);
        action.setNextRunAt(null);
        action.setUpdatedAt(now);
        actionMapper.updateById(action);
        actionQueueService.releaseLock(actionId);
        releaseWorkspaceLockIfApplicable(action);
        workflowService.refreshStatus(action.getWorkflowId());

        return new DevOsInterruptResponse(actionId, ActionStatus.FAILED.name(), true);
    }

    @Transactional
    public int dispatchAllRunnableActions() {
        List<ActionEntity> blockedActions = actionMapper.selectList(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getStatus, ActionStatus.BLOCKED.name())
                .orderByAsc(ActionEntity::getCreatedAt)
                .orderByAsc(ActionEntity::getId));
        if (blockedActions.isEmpty()) {
            return 0;
        }

        Set<Long> blockedActionIds = blockedActions.stream()
                .map(ActionEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return dispatchRunnableActions(blockedActionIds, LocalDateTime.now());
    }

    private ActionEntity requireAction(Long actionId) {
        ActionEntity action = actionMapper.selectById(actionId);
        if (action == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Action not found: " + actionId);
        }
        return action;
    }

    private List<ActionLogEntity> loadActionLogs(Long actionId) {
        return actionLogMapper.selectList(new LambdaQueryWrapper<ActionLogEntity>()
                .eq(ActionLogEntity::getActionId, actionId)
                .orderByAsc(ActionLogEntity::getCreatedAt)
                .orderByAsc(ActionLogEntity::getId));
    }

    private String materializeAssignmentPayload(ActionEntity action) {
        String rawPayload = action.getPayload();
        if (rawPayload == null || rawPayload.isBlank()) {
            return rawPayload;
        }

        JsonNode parsedPayload;
        try {
            parsedPayload = objectMapper.readTree(rawPayload);
        } catch (JsonProcessingException exception) {
            return rawPayload;
        }

        if (!(parsedPayload instanceof ObjectNode payloadObject)) {
            return rawPayload;
        }

        ObjectNode materializedPayload = payloadObject.deepCopy();
        JsonNode injectNode = materializedPayload.remove("inject");
        if (injectNode == null) {
            return rawPayload;
        }

        if (!injectNode.isObject()) {
            LOGGER.warn("action_payload_injection_invalid actionId={} type={} reason=inject_not_object",
                    action.getId(), action.getType());
            return writeJson(materializedPayload, rawPayload, action.getId(), action.getType());
        }

        ObjectNode injectionContext = buildInjectionContext(action.getId());
        applyInjectionRules(materializedPayload, (ObjectNode) injectNode, injectionContext, action);
        return writeJson(materializedPayload, rawPayload, action.getId(), action.getType());
    }

    private String writeJson(ObjectNode payload, String fallback, Long actionId, String actionType) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            LOGGER.warn("action_payload_injection_serialize_failed actionId={} type={} reason={}",
                    actionId,
                    actionType,
                    exception.getMessage());
            return fallback;
        }
    }

    private ObjectNode buildInjectionContext(Long actionId) {
        ObjectNode context = objectMapper.createObjectNode();
        ArrayNode upstreamArray = context.putArray("upstream");
        ObjectNode upstreamByType = context.putObject("upstreamByType");
        ObjectNode upstreamById = context.putObject("upstreamById");

        List<ActionDependencyEntity> dependencies = actionDependencyMapper.selectList(
                new LambdaQueryWrapper<ActionDependencyEntity>()
                        .eq(ActionDependencyEntity::getDownstreamActionId, actionId)
                        .orderByAsc(ActionDependencyEntity::getId));
        if (dependencies.isEmpty()) {
            return context;
        }

        List<Long> upstreamIds = dependencies.stream()
                .map(ActionDependencyEntity::getUpstreamActionId)
                .filter(id -> id != null && id > 0)
                .toList();
        if (upstreamIds.isEmpty()) {
            return context;
        }

        Map<Long, ActionEntity> upstreamByActionId = actionMapper.selectBatchIds(upstreamIds).stream()
                .collect(Collectors.toMap(ActionEntity::getId, upstream -> upstream));

        for (Long upstreamId : upstreamIds) {
            ActionEntity upstreamAction = upstreamByActionId.get(upstreamId);
            if (upstreamAction == null) {
                continue;
            }

            ObjectNode upstreamNode = objectMapper.createObjectNode();
            upstreamNode.put("actionId", upstreamAction.getId());
            upstreamNode.put("type", upstreamAction.getType());
            upstreamNode.put("status", upstreamAction.getStatus());
            upstreamNode.set("payload", parseJsonNodeLenient(upstreamAction.getPayload()));
            upstreamNode.set("result", loadLatestResultNode(upstreamAction.getId()));

            upstreamArray.add(upstreamNode);
            upstreamById.set(String.valueOf(upstreamAction.getId()), upstreamNode.deepCopy());
            String upstreamType = upstreamAction.getType();
            if (upstreamType != null && !upstreamType.isBlank() && !upstreamByType.has(upstreamType)) {
                upstreamByType.set(upstreamType, upstreamNode.deepCopy());
            }
        }

        return context;
    }

    private JsonNode loadLatestResultNode(Long actionId) {
        List<ActionLogEntity> actionLogs = actionLogMapper.selectList(new LambdaQueryWrapper<ActionLogEntity>()
                .eq(ActionLogEntity::getActionId, actionId)
                .orderByDesc(ActionLogEntity::getCreatedAt)
                .orderByDesc(ActionLogEntity::getId));
        if (actionLogs.isEmpty()) {
            return objectMapper.nullNode();
        }

        ActionLogEntity selected = actionLogs.stream()
                .filter(log -> ActionStatus.SUCCEEDED.name().equals(log.getStatus()))
                .findFirst()
                .orElse(actionLogs.get(0));
        return parseJsonNodeLenient(selected.getResult());
    }

    private JsonNode parseJsonNodeLenient(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (JsonProcessingException exception) {
            return TextNode.valueOf(rawJson);
        }
    }

    private void applyInjectionRules(
            ObjectNode payload,
            ObjectNode injectNode,
            ObjectNode context,
            ActionEntity action
    ) {
        Iterator<Map.Entry<String, JsonNode>> iterator = injectNode.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            String targetField = entry.getKey();
            if (targetField == null || targetField.isBlank()) {
                continue;
            }

            JsonNode ruleNode = entry.getValue();
            JsonNode defaultValue = null;
            JsonNode fallbackFrom = null;
            JsonNode resolvedValue;
            boolean required = false;
            boolean stringify = false;

            if (ruleNode == null || ruleNode.isNull()) {
                continue;
            }

            if (ruleNode.isTextual()) {
                resolvedValue = evaluateInjectionExpression(ruleNode.asText(""), context);
            } else if (ruleNode.isObject()) {
                JsonNode explicitValue = ruleNode.get("value");
                if (explicitValue != null) {
                    resolvedValue = explicitValue;
                } else {
                    resolvedValue = evaluateInjectionExpression(ruleNode.path("from").asText(""), context);
                }
                defaultValue = ruleNode.get("default");
                fallbackFrom = ruleNode.get("fallbackFrom");
                required = ruleNode.path("required").asBoolean(false);
                stringify = ruleNode.path("stringify").asBoolean(false);
            } else {
                resolvedValue = ruleNode;
            }

            if (isMissingOrNull(resolvedValue) && fallbackFrom != null) {
                resolvedValue = resolveFallbackExpression(fallbackFrom, context);
            }

            if (isMissingOrNull(resolvedValue)) {
                if (defaultValue != null) {
                    setInjectedValue(payload, targetField, defaultValue, stringify);
                } else if (required) {
                    LOGGER.warn("action_payload_injection_missing actionId={} type={} field={} rule={}",
                            action.getId(),
                            action.getType(),
                            targetField,
                            ruleNode.toString());
                }
                continue;
            }

            setInjectedValue(payload, targetField, resolvedValue, stringify);
        }
    }

    private JsonNode resolveFallbackExpression(JsonNode fallbackFrom, JsonNode context) {
        if (fallbackFrom == null || fallbackFrom.isNull()) {
            return objectMapper.missingNode();
        }

        if (fallbackFrom.isTextual()) {
            return evaluateInjectionExpression(fallbackFrom.asText(""), context);
        }

        if (!fallbackFrom.isArray()) {
            return objectMapper.missingNode();
        }

        for (JsonNode candidate : fallbackFrom) {
            if (candidate == null || !candidate.isTextual()) {
                continue;
            }

            JsonNode resolved = evaluateInjectionExpression(candidate.asText(""), context);
            if (!isMissingOrNull(resolved)) {
                return resolved;
            }
        }

        return objectMapper.missingNode();
    }

    private void setInjectedValue(ObjectNode payload, String targetPath, JsonNode value, boolean stringify) {
        List<String> segments = parseTargetPathSegments(targetPath);
        if (segments.isEmpty()) {
            return;
        }

        ObjectNode current = payload;
        for (int index = 0; index < segments.size() - 1; index++) {
            String segment = segments.get(index);
            JsonNode child = current.get(segment);
            ObjectNode objectChild;
            if (!(child instanceof ObjectNode)) {
                objectChild = objectMapper.createObjectNode();
                current.set(segment, objectChild);
            } else {
                objectChild = (ObjectNode) child;
            }
            current = objectChild;
        }

        String field = segments.get(segments.size() - 1);
        JsonNode storedValue = value == null ? objectMapper.nullNode() : value.deepCopy();
        if (stringify && !storedValue.isTextual()) {
            storedValue = TextNode.valueOf(storedValue.toString());
        }
        current.set(field, storedValue);
    }

    private List<String> parseTargetPathSegments(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) {
            return List.of();
        }
        return List.of(targetPath.split("\\."))
                .stream()
                .map(String::trim)
                .filter(segment -> !segment.isBlank())
                .toList();
    }

    private JsonNode evaluateInjectionExpression(String expression, JsonNode context) {
        if (expression == null || expression.isBlank()) {
            return objectMapper.missingNode();
        }

        String normalized = expression.trim();
        if (!normalized.startsWith("$")) {
            return TextNode.valueOf(normalized);
        }

        List<String> segments = parseExpressionSegments(normalized);
        JsonNode current = context;
        for (String segment : segments) {
            if (isMissingOrNull(current)) {
                return objectMapper.missingNode();
            }

            if (isInteger(segment)) {
                if (!current.isArray()) {
                    return objectMapper.missingNode();
                }
                int index = Integer.parseInt(segment);
                if (index < 0 || index >= current.size()) {
                    return objectMapper.missingNode();
                }
                current = current.get(index);
            } else {
                current = current.path(segment);
            }
        }

        return current == null ? objectMapper.missingNode() : current;
    }

    private List<String> parseExpressionSegments(String expression) {
        String body = expression.startsWith("$.") ? expression.substring(2) : expression.substring(1);
        if (body.isBlank()) {
            return List.of();
        }

        ArrayList<String> segments = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '.') {
                appendSegment(segments, token);
                continue;
            }

            if (current == '[') {
                appendSegment(segments, token);
                int closeIndex = body.indexOf(']', index + 1);
                if (closeIndex < 0) {
                    break;
                }
                String bracketValue = body.substring(index + 1, closeIndex).trim();
                if (!bracketValue.isBlank()) {
                    if (bracketValue.startsWith("\"") && bracketValue.endsWith("\"") && bracketValue.length() >= 2) {
                        bracketValue = bracketValue.substring(1, bracketValue.length() - 1);
                    }
                    segments.add(bracketValue);
                }
                index = closeIndex;
                continue;
            }

            token.append(current);
        }
        appendSegment(segments, token);
        return List.copyOf(segments);
    }

    private void appendSegment(List<String> segments, StringBuilder token) {
        if (token.length() == 0) {
            return;
        }
        String value = token.toString().trim();
        token.setLength(0);
        if (!value.isBlank()) {
            segments.add(value);
        }
    }

    private boolean isMissingOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private boolean isInteger(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private Object parseJsonContent(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawJson, Object.class);
        } catch (JsonProcessingException exception) {
            return rawJson;
        }
    }

    private LocalDateTime resolveStartedAt(ActionEntity action) {
        if (action.getExecutionStartedAt() != null) {
            return action.getExecutionStartedAt();
        }
        return action.getClaimTime();
    }

    private LocalDateTime resolveFinishedAt(String status, ActionEntity action) {
        if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
            return null;
        }
        if (action.getSubmitTime() != null) {
            return action.getSubmitTime();
        }
        return action.getReclaimTime();
    }

    private ActionLogEntryResponse toActionLogEntry(ActionLogEntity actionLog) {
        return new ActionLogEntryResponse(
                actionLog.getWorkerId(),
                actionLog.getStatus(),
                actionLog.getCreatedAt(),
                parseJsonContent(actionLog.getResult())
        );
    }

    private void validateUpstreamActions(Long workflowId, List<Long> upstreamActionIds) {
        if (upstreamActionIds.isEmpty()) {
            return;
        }

        List<ActionEntity> upstreamActions = actionMapper.selectBatchIds(upstreamActionIds);
        if (upstreamActions.size() != upstreamActionIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Some upstream actions do not exist");
        }

        boolean crossWorkflowDependency = upstreamActions.stream()
                .anyMatch(action -> !workflowId.equals(action.getWorkflowId()));
        if (crossWorkflowDependency) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upstream actions must belong to the same workflow");
        }
    }

    /**
     * Returns true if making {@code downstreamId} depend on each of {@code proposedUpstreamIds}
     * would introduce a directed cycle in the DAG.
     *
     * <p>Algorithm: BFS from {@code downstreamId} following existing downstream edges (i.e. edges
     * where {@code downstreamId} is an upstream). If any reachable node equals a proposed upstream,
     * the new edge would close a cycle.
     *
     * <p>Self-loops ({@code proposedUpstreamIds} contains {@code downstreamId}) are detected directly.
     *
     * <p>This method is public so that it can be called from tests seeding the dependency table
     * directly, simulating future {@code addDependency} API scenarios.
     */
    public boolean wouldCreateCycle(Long downstreamId, List<Long> proposedUpstreamIds) {
        if (proposedUpstreamIds.isEmpty()) {
            return false;
        }
        Set<Long> upstreamSet = new HashSet<>(proposedUpstreamIds);

        // Self-loop: the action lists itself as its own upstream
        if (upstreamSet.contains(downstreamId)) {
            return true;
        }

        // BFS from downstreamId following existing downstream edges.
        // If any reachable node is a proposed upstream, adding those edges creates a cycle.
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(downstreamId);

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }

            List<ActionDependencyEntity> outEdges = actionDependencyMapper.selectList(
                    new LambdaQueryWrapper<ActionDependencyEntity>()
                            .eq(ActionDependencyEntity::getUpstreamActionId, current));

            for (ActionDependencyEntity edge : outEdges) {
                Long child = edge.getDownstreamActionId();
                if (upstreamSet.contains(child)) {
                    return true;
                }
                if (!visited.contains(child)) {
                    queue.add(child);
                }
            }
        }
        return false;
    }

    private List<Long> normalizeUpstreamActionIds(List<Long> upstreamActionIds) {
        if (upstreamActionIds == null) {
            return List.of();
        }
        return upstreamActionIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private ActionStatus parseResultOutcome(String rawStatus) {
        try {
            ActionStatus status = ActionStatus.valueOf(rawStatus.trim().toUpperCase());
            if (status != ActionStatus.SUCCEEDED && status != ActionStatus.FAILED) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Result status must be SUCCEEDED or FAILED");
            }
            return status;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported result status: " + rawStatus);
        }
    }

    private ActionStatus parseActionStatus(String rawStatus) {
        try {
            return ActionStatus.valueOf(rawStatus);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unknown action status: " + rawStatus);
        }
    }

    private boolean isSafeDuplicateResult(ActionEntity action) {
        ActionStatus status = parseActionStatus(action.getStatus());
        return status.isTerminal() || status == ActionStatus.RETRY_WAIT || status == ActionStatus.QUEUED;
    }

    private void applyFailureWithRetry(
            ActionEntity action,
            LocalDateTime now,
            String workerId,
            String result,
            String failureMessage,
            String logStatus,
            ActionStatus terminalStatusWhenExhausted,
            String reclaimReason
    ) {
        int nextRetryCount = normalizeNonNegative(action.getRetryCount(), 0) + 1;
        int maxRetryCount = normalizeNonNegative(action.getMaxRetryCount(), defaultMaxRetryCount);

        action.setRetryCount(nextRetryCount);
        action.setErrorMessage(failureMessage);
        action.setLeaseExpireAt(null);
        if (reclaimReason == null || reclaimReason.isBlank()) {
            action.setLastReclaimReason(null);
            action.setReclaimTime(null);
        } else {
            action.setLastReclaimReason(reclaimReason);
            action.setReclaimTime(now);
            action.setSubmitTime(null);
        }
        completeExecutionAttempt(action, now);
        action.setWorkerId(null);
        action.setUpdatedAt(now);
        actionQueueService.releaseLock(action.getId());
        releaseWorkspaceLockIfApplicable(action);

        if (nextRetryCount <= maxRetryCount) {
            transitionState(action, ActionStatus.RETRY_WAIT);
            long delaySeconds = computeBackoffSeconds(action.getBackoffSeconds(), nextRetryCount);
            action.setNextRunAt(now.plusSeconds(delaySeconds));
        } else {
            transitionState(action, terminalStatusWhenExhausted);
            action.setNextRunAt(null);
        }

        actionMapper.updateById(action);
        recordActionLog(action.getId(), workerId, result, logStatus, now);
    }

    // ── B-006 Workspace Mutex helpers ─────────────────────────────────────────

    private boolean extractWriteIntent(String payload) {
        if (payload == null || payload.isBlank()) return false;
        try {
            return objectMapper.readTree(payload).path("write_intent").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private String extractWorkspaceKey(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            String key = objectMapper.readTree(payload).path("workspace_key").asText(null);
            return (key == null || key.isBlank()) ? null : key;
        } catch (Exception e) {
            return null;
        }
    }

    private void releaseWorkspaceLockIfApplicable(ActionEntity action) {
        String wsKey = extractWorkspaceKey(action.getPayload());
        if (wsKey != null) {
            actionQueueService.releaseWorkspaceLock(wsKey, action.getId().toString());
        }
    }

    private long computeBackoffSeconds(Integer configuredBackoffSeconds, int retryAttempt) {        long baseSeconds = Math.max(0, configuredBackoffSeconds == null ? defaultBackoffSeconds : configuredBackoffSeconds);
        if (baseSeconds == 0) {
            return 0;
        }

        int exponent = Math.max(0, retryAttempt - 1);
        long multiplier = 1L << Math.min(10, exponent);
        long delay = baseSeconds * multiplier;
        return Math.min(delay, 3600L);
    }

    private void recordActionLog(Long actionId, String workerId, String result, String status, LocalDateTime createdAt) {
        ActionLogEntity actionLog = new ActionLogEntity();
        actionLog.setActionId(actionId);
        actionLog.setWorkerId((workerId == null || workerId.isBlank()) ? "scheduler" : workerId);
        actionLog.setResult(result);
        actionLog.setStatus(status);
        actionLog.setCreatedAt(createdAt);
        actionLogMapper.insert(actionLog);
    }

    private void maybeMarkRenewFailure(ActionEntity action, String workerId, LocalDateTime now) {
        if (action.getWorkerId() != null && action.getWorkerId().equals(workerId)) {
            markRenewFailure(action);
            action.setUpdatedAt(now);
            actionMapper.updateById(action);
        }
    }

    private void markRenewSuccess(ActionEntity action, LocalDateTime now) {
        action.setLeaseRenewSuccessCount(normalizeNonNegative(action.getLeaseRenewSuccessCount(), 0) + 1);
        if (action.getFirstRenewTime() == null) {
            action.setFirstRenewTime(now);
        }
        action.setLastRenewTime(now);
        action.setLastLeaseRenewAt(now);
    }

    private void markRenewFailure(ActionEntity action) {
        action.setLeaseRenewFailureCount(normalizeNonNegative(action.getLeaseRenewFailureCount(), 0) + 1);
    }

    private void completeExecutionAttempt(ActionEntity action, LocalDateTime completedAt) {
        if (action.getExecutionStartedAt() == null) {
            action.setLastExecutionDurationMs(0L);
            return;
        }

        long durationMs = Math.max(0L, Duration.between(action.getExecutionStartedAt(), completedAt).toMillis());
        action.setLastExecutionDurationMs(durationMs);
        action.setExecutionStartedAt(null);
    }

    private void transitionState(ActionEntity action, ActionStatus target) {
        ActionStatus current = parseActionStatus(action.getStatus());
        if (current == target) {
            return;
        }
        if (!isValidTransition(current, target)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Invalid action state transition: " + current + " -> " + target + " for action " + action.getId());
        }
        action.setStatus(target.name());
    }

    private boolean isValidTransition(ActionStatus from, ActionStatus to) {
        return switch (from) {
            case BLOCKED -> to == ActionStatus.QUEUED || to == ActionStatus.FAILED;
            case QUEUED -> to == ActionStatus.RUNNING || to == ActionStatus.FAILED;
            case RUNNING -> to == ActionStatus.SUCCEEDED || to == ActionStatus.FAILED || to == ActionStatus.RETRY_WAIT || to == ActionStatus.DEAD_LETTER;
            case RETRY_WAIT -> to == ActionStatus.QUEUED || to == ActionStatus.DEAD_LETTER || to == ActionStatus.FAILED;
            case SUCCEEDED, FAILED, DEAD_LETTER -> false;
        };
    }

    private int normalizeNonNegative(Integer value, int fallback) {
        if (value == null) {
            return Math.max(0, fallback);
        }
        return Math.max(0, value);
    }

    private int normalizePositive(Integer value, int fallback) {
        int raw = value == null ? fallback : value;
        return Math.max(1, raw);
    }

    private void triggerDownstreamActions(ActionEntity completedAction) {
        List<ActionDependencyEntity> downstreamDependencies = actionDependencyMapper.selectList(
                new LambdaQueryWrapper<ActionDependencyEntity>()
                        .eq(ActionDependencyEntity::getUpstreamActionId, completedAction.getId())
        );

        Set<Long> downstreamActionIds = downstreamDependencies.stream()
                .map(ActionDependencyEntity::getDownstreamActionId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        dispatchRunnableActions(downstreamActionIds, LocalDateTime.now());
    }

    private int dispatchRunnableActions(Set<Long> candidateActionIds, LocalDateTime now) {
        if (candidateActionIds.isEmpty()) {
            return 0;
        }

        Set<Long> normalizedCandidateActionIds = candidateActionIds.stream()
                .filter(actionId -> actionId != null && actionId > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedCandidateActionIds.isEmpty()) {
            return 0;
        }

        Map<Long, ActionEntity> candidateActionsById = actionMapper.selectBatchIds(normalizedCandidateActionIds)
                .stream()
                .collect(Collectors.toMap(ActionEntity::getId, action -> action));

        Set<Long> blockedCandidateActionIds = normalizedCandidateActionIds.stream()
                .filter(actionId -> {
                    ActionEntity action = candidateActionsById.get(actionId);
                    return action != null && ActionStatus.BLOCKED.name().equals(action.getStatus());
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> dependencyReadyActionIds = findDependencySatisfiedActionIds(blockedCandidateActionIds);

        int dispatchedCount = 0;
        Map<Long, Integer> remainingSlotsByWorkflow = new HashMap<>();
        Set<Long> impactedWorkflows = new LinkedHashSet<>();
        for (Long downstreamActionId : normalizedCandidateActionIds) {
            ActionEntity downstreamAction = candidateActionsById.get(downstreamActionId);
            if (downstreamAction == null || !ActionStatus.BLOCKED.name().equals(downstreamAction.getStatus())) {
                continue;
            }

            if (!dependencyReadyActionIds.contains(downstreamActionId)) {
                continue;
            }

            Long workflowId = downstreamAction.getWorkflowId();
            if (!hasRemainingDispatchSlot(workflowId, remainingSlotsByWorkflow)) {
                continue;
            }

            int updatedRows = actionMapper.update(null, new LambdaUpdateWrapper<ActionEntity>()
                    .set(ActionEntity::getStatus, ActionStatus.QUEUED.name())
                    .set(ActionEntity::getUpdatedAt, now)
                    .eq(ActionEntity::getId, downstreamActionId)
                    .eq(ActionEntity::getStatus, ActionStatus.BLOCKED.name()));
            if (updatedRows == 0) {
                continue;
            }

                enqueueAction(
                    downstreamAction,
                    actionCapabilityResolver.resolveRequiredCapability(downstreamAction.getType()));
            consumeDispatchSlot(workflowId, remainingSlotsByWorkflow);
            impactedWorkflows.add(workflowId);
            dispatchedCount++;
        }

        for (Long workflowId : impactedWorkflows) {
            workflowService.refreshStatus(workflowId);
        }

        return dispatchedCount;
    }

    private boolean hasRemainingDispatchSlot(Long workflowId, Map<Long, Integer> remainingSlotsByWorkflow) {
        if (workflowId == null || maxParallelActionsPerWorkflow <= 0) {
            return true;
        }

        Integer remainingSlots = remainingSlotsByWorkflow.get(workflowId);
        if (remainingSlots == null) {
            remainingSlots = computeRemainingDispatchSlots(workflowId);
            remainingSlotsByWorkflow.put(workflowId, remainingSlots);
        }
        return remainingSlots > 0;
    }

    private void consumeDispatchSlot(Long workflowId, Map<Long, Integer> remainingSlotsByWorkflow) {
        if (workflowId == null || maxParallelActionsPerWorkflow <= 0) {
            return;
        }

        Integer remainingSlots = remainingSlotsByWorkflow.get(workflowId);
        if (remainingSlots == null) {
            return;
        }
        remainingSlotsByWorkflow.put(workflowId, Math.max(0, remainingSlots - 1));
    }

    private int computeRemainingDispatchSlots(Long workflowId) {
        if (maxParallelActionsPerWorkflow <= 0) {
            return Integer.MAX_VALUE;
        }

        long inflightCount = actionMapper.selectCount(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getWorkflowId, workflowId)
                .in(ActionEntity::getStatus, ActionStatus.QUEUED.name(), ActionStatus.RUNNING.name()));
        if (inflightCount >= maxParallelActionsPerWorkflow) {
            return 0;
        }
        return maxParallelActionsPerWorkflow - (int) inflightCount;
    }

    private Set<Long> findDependencySatisfiedActionIds(Set<Long> downstreamActionIds) {
        if (downstreamActionIds.isEmpty()) {
            return Set.of();
        }

        List<ActionDependencyEntity> dependencies = actionDependencyMapper.selectList(
                new LambdaQueryWrapper<ActionDependencyEntity>()
                        .in(ActionDependencyEntity::getDownstreamActionId, downstreamActionIds)
        );
        if (dependencies.isEmpty()) {
            return new LinkedHashSet<>(downstreamActionIds);
        }

        Map<Long, Set<Long>> upstreamActionIdsByDownstream = new HashMap<>();
        Set<Long> upstreamActionIds = new LinkedHashSet<>();
        for (ActionDependencyEntity dependency : dependencies) {
            Long downstreamActionId = dependency.getDownstreamActionId();
            Long upstreamActionId = dependency.getUpstreamActionId();
            if (downstreamActionId == null || upstreamActionId == null || upstreamActionId <= 0) {
                continue;
            }
            if (!downstreamActionIds.contains(downstreamActionId)) {
                continue;
            }

            upstreamActionIdsByDownstream
                    .computeIfAbsent(downstreamActionId, ignored -> new LinkedHashSet<>())
                    .add(upstreamActionId);
            upstreamActionIds.add(upstreamActionId);
        }

        if (upstreamActionIds.isEmpty()) {
            return new LinkedHashSet<>(downstreamActionIds);
        }

        Map<Long, String> upstreamStatusByActionId = actionMapper.selectBatchIds(upstreamActionIds)
                .stream()
                .collect(Collectors.toMap(ActionEntity::getId, ActionEntity::getStatus));

        Set<Long> satisfiedActionIds = new LinkedHashSet<>();
        for (Long downstreamActionId : downstreamActionIds) {
            Set<Long> requiredUpstreamActionIds = upstreamActionIdsByDownstream.get(downstreamActionId);
            if (requiredUpstreamActionIds == null || requiredUpstreamActionIds.isEmpty()) {
                satisfiedActionIds.add(downstreamActionId);
                continue;
            }

            boolean allSucceeded = true;
            for (Long upstreamActionId : requiredUpstreamActionIds) {
                if (!ActionStatus.SUCCEEDED.name().equals(upstreamStatusByActionId.get(upstreamActionId))) {
                    allSucceeded = false;
                    break;
                }
            }

            if (allSucceeded) {
                satisfiedActionIds.add(downstreamActionId);
            }
        }

        return satisfiedActionIds;
    }

    private boolean allDependenciesSucceeded(Long downstreamActionId) {
        if (downstreamActionId == null || downstreamActionId <= 0) {
            return false;
        }
        return findDependencySatisfiedActionIds(Set.of(downstreamActionId)).contains(downstreamActionId);
    }

    private void enqueueAction(ActionEntity action, String capability) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    actionQueueService.enqueue(action, capability);
                }
            });
            return;
        }

        actionQueueService.enqueue(action, capability);
    }

    private List<Long> loadUpstreamActionIds(Long actionId) {
        return actionDependencyMapper.selectList(new LambdaQueryWrapper<ActionDependencyEntity>()
                        .eq(ActionDependencyEntity::getDownstreamActionId, actionId))
                .stream()
                .map(ActionDependencyEntity::getUpstreamActionId)
                .toList();
    }

    private ActionResponse toResponse(ActionEntity action) {
        return new ActionResponse(
                action.getId(),
                action.getWorkflowId(),
                action.getType(),
                action.getStatus(),
                action.getWorkerId(),
                action.getRetryCount(),
                action.getMaxRetryCount(),
                action.getBackoffSeconds(),
                action.getExecutionTimeoutSeconds(),
                action.getLeaseExpireAt(),
                action.getNextRunAt(),
                action.getClaimTime(),
                action.getFirstRenewTime(),
                action.getLastRenewTime(),
                action.getSubmitTime(),
                action.getReclaimTime(),
                action.getLeaseRenewSuccessCount(),
                action.getLeaseRenewFailureCount(),
                action.getLastLeaseRenewAt(),
                action.getExecutionStartedAt(),
                action.getLastExecutionDurationMs(),
                action.getLastReclaimReason(),
                action.getPayload(),
                action.getErrorMessage(),
                loadUpstreamActionIds(action.getId()),
                action.getCreatedAt(),
                action.getUpdatedAt()
        );
    }

    /**
     * Extract the "notepad" field from a result JSON string produced by the worker.
     * If the result is not valid JSON or has no "notepad" field, returns null.
     * The worker can embed: {"response": "...", "notepad": "summary of what was done"}
     */
    private String extractNotepadFromResult(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(resultJson);
            JsonNode notepad = node.get("notepad");
            if (notepad != null && notepad.isTextual()) {
                return notepad.asText();
            }
        } catch (JsonProcessingException ignored) {
            // result is plain text — not a JSON object
        }
        return null;
    }
}