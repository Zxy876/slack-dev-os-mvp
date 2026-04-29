package com.asyncaiflow.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.asyncaiflow.domain.entity.ActionEntity;
import com.asyncaiflow.domain.entity.ActionLogEntity;
import com.asyncaiflow.domain.entity.WorkflowEntity;
import com.asyncaiflow.domain.enums.ActionStatus;
import com.asyncaiflow.domain.enums.WorkflowStatus;
import com.asyncaiflow.mapper.ActionLogMapper;
import com.asyncaiflow.mapper.ActionMapper;
import com.asyncaiflow.mapper.WorkflowMapper;
import com.asyncaiflow.support.ApiException;
import com.asyncaiflow.support.RuntimeStatusView;
import com.asyncaiflow.web.dto.CreateWorkflowRequest;
import com.asyncaiflow.web.dto.WorkflowActionSummaryResponse;
import com.asyncaiflow.web.dto.WorkflowContextQualityResponse;
import com.asyncaiflow.web.dto.WorkflowExecutionResponse;
import com.asyncaiflow.web.dto.WorkflowListItemResponse;
import com.asyncaiflow.web.dto.WorkflowResponse;
import com.asyncaiflow.web.dto.WorkflowSummaryActionResponse;
import com.asyncaiflow.web.dto.WorkflowSummaryResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final ActionMapper actionMapper;
    private final ActionLogMapper actionLogMapper;
    private final ObjectMapper objectMapper;

    public WorkflowService(
            WorkflowMapper workflowMapper,
            ActionMapper actionMapper,
            ActionLogMapper actionLogMapper,
            ObjectMapper objectMapper
    ) {
        this.workflowMapper = workflowMapper;
        this.actionMapper = actionMapper;
        this.actionLogMapper = actionLogMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowResponse createWorkflow(CreateWorkflowRequest request) {
        LocalDateTime now = LocalDateTime.now();
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName(request.name().trim());
        workflow.setDescription(request.description());
        workflow.setStatus(WorkflowStatus.CREATED.name());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowMapper.insert(workflow);
        return toResponse(workflow);
    }

    public WorkflowEntity requireWorkflow(Long workflowId) {
        WorkflowEntity workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Workflow not found: " + workflowId);
        }
        return workflow;
    }

    public WorkflowExecutionResponse getWorkflowExecution(Long workflowId) {
        WorkflowEntity workflow = requireWorkflow(workflowId);
        List<ActionEntity> actions = listWorkflowActionEntities(workflowId);
        List<WorkflowActionSummaryResponse> actionSummaries = actions.stream()
                .map(this::toActionSummary)
                .toList();

        return new WorkflowExecutionResponse(
                workflow.getId(),
                RuntimeStatusView.workflowStatus(actions),
                workflow.getCreatedAt(),
                actionSummaries
        );
    }

    public List<WorkflowListItemResponse> getRecentWorkflows(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 50));
        return workflowMapper.selectList(new LambdaQueryWrapper<WorkflowEntity>()
                        .orderByDesc(WorkflowEntity::getCreatedAt)
                        .orderByDesc(WorkflowEntity::getId)
                        .last("LIMIT " + effectiveLimit))
                .stream()
                .map(this::toWorkflowListItem)
                .toList();
    }

    public List<WorkflowActionSummaryResponse> getWorkflowActions(Long workflowId) {
        requireWorkflow(workflowId);
        return listWorkflowActionEntities(workflowId).stream()
                .map(this::toActionSummary)
                .toList();
    }

    public WorkflowSummaryResponse getWorkflowSummary(Long workflowId) {
        WorkflowEntity workflow = requireWorkflow(workflowId);
        List<ActionEntity> actions = listWorkflowActionEntities(workflowId);
        String status = RuntimeStatusView.workflowStatus(actions);
        LocalDateTime finishedAt = resolveWorkflowFinishedAt(actions, status);

        List<WorkflowSummaryActionResponse> actionSummaries = new ArrayList<>();
        List<String> keyFindings = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        Set<String> suggestions = new LinkedHashSet<>();
        int totalRetrievalCount = 0;
        int totalSourceCount = 0;
        int noisyActionCount = 0;
        boolean hasRetrievalCount = false;
        boolean hasSourceCount = false;

        for (ActionEntity action : actions) {
            String actionStatus = RuntimeStatusView.actionStatus(action.getStatus());
            LocalDateTime startedAt = resolveStartedAt(action);
            LocalDateTime actionFinishedAt = terminalFinishedAt(actionStatus, action);
            Long actionDurationSeconds = computeDurationSeconds(startedAt, actionFinishedAt);
            JsonNode resultNode = latestActionResultNode(action.getId());

            Integer matchCount = readIntegerField(resultNode, "matchCount");
            Integer sourceCount = readIntegerField(resultNode, "sourceCount");
            Integer retrievalCount = readIntegerField(resultNode, "retrievalCount");
            boolean noisyRetrieval = hasDependencyNoise(resultNode);

            if (retrievalCount != null) {
                totalRetrievalCount += retrievalCount;
                hasRetrievalCount = true;
            }
            if (sourceCount != null) {
                totalSourceCount += sourceCount;
                hasSourceCount = true;
            }

            if (noisyRetrieval) {
                noisyActionCount++;
                warnings.add("semantic search pulled dependency directories (venv/site-packages/node_modules)");
                suggestions.add("exclude .venv/venv/node_modules from repository retrieval scope");
            }

            if ("build_context_pack".equals(action.getType()) && resultNode != null && resultNode.path("truncated").asBoolean(false)) {
                warnings.add("context pack contains truncated sources");
                suggestions.add("increase maxCharsPerFile or narrow scope paths before context build");
            }

            if ("FAILED".equals(actionStatus)) {
                warnings.add("action failed: " + action.getType());
                suggestions.add("inspect failed action details and rerun the workflow after fixing upstream issues");
            }

            if (action.getErrorMessage() != null && !action.getErrorMessage().isBlank()) {
                warnings.add(action.getType() + " error: " + toSingleLine(action.getErrorMessage(), 160));
            }

            String shortResult = buildShortResult(
                    action.getType(),
                    resultNode,
                    action.getErrorMessage(),
                    matchCount,
                    sourceCount,
                    retrievalCount
            );

            keyFindings.addAll(extractKeyFindings(action.getType(), resultNode, shortResult));

            actionSummaries.add(new WorkflowSummaryActionResponse(
                    action.getId(),
                    action.getType(),
                    actionStatus,
                    action.getWorkerId(),
                    actionDurationSeconds,
                    shortResult,
                    matchCount,
                    sourceCount,
                    retrievalCount,
                    noisyRetrieval
            ));
        }

        if (keyFindings.isEmpty()) {
            actionSummaries.stream()
                    .map(WorkflowSummaryActionResponse::shortResult)
                    .filter(value -> value != null && !value.isBlank())
                    .limit(3)
                    .forEach(keyFindings::add);
        }

        WorkflowContextQualityResponse contextQuality = new WorkflowContextQualityResponse(
            hasRetrievalCount ? totalRetrievalCount : null,
            hasSourceCount ? totalSourceCount : null,
            noisyActionCount,
            noisyActionCount > 0,
            noisyActionCount > 0
                ? "dependency directories detected (venv/site-packages/node_modules)"
                : "none detected"
        );

        return new WorkflowSummaryResponse(
                workflow.getId(),
                status,
                resolveIssue(workflow),
                workflow.getCreatedAt(),
                finishedAt,
                computeDurationSeconds(workflow.getCreatedAt(), finishedAt),
                actions.stream().map(ActionEntity::getType).toList(),
                actionSummaries,
            contextQuality,
                deduplicatePreservingOrder(keyFindings),
                List.copyOf(warnings),
                List.copyOf(suggestions)
        );
    }

    @Transactional
    public void refreshStatus(Long workflowId) {
        WorkflowEntity workflow = requireWorkflow(workflowId);
        List<ActionEntity> actions = actionMapper.selectList(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getWorkflowId, workflowId));

        WorkflowStatus nextStatus = deriveStatus(actions);
        if (!nextStatus.name().equals(workflow.getStatus())) {
            workflow.setStatus(nextStatus.name());
            workflow.setUpdatedAt(LocalDateTime.now());
            workflowMapper.updateById(workflow);
        }
    }

    public WorkflowResponse toResponse(WorkflowEntity workflow) {
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getStatus(),
                workflow.getCreatedAt()
        );
    }

    private List<ActionEntity> listWorkflowActionEntities(Long workflowId) {
        return actionMapper.selectList(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getWorkflowId, workflowId)
                .orderByAsc(ActionEntity::getCreatedAt)
                .orderByAsc(ActionEntity::getId));
    }

    private WorkflowActionSummaryResponse toActionSummary(ActionEntity action) {
        String status = RuntimeStatusView.actionStatus(action.getStatus());
        return new WorkflowActionSummaryResponse(
                action.getId(),
                action.getType(),
                status,
                action.getWorkerId(),
                action.getCreatedAt(),
                terminalFinishedAt(status, action)
        );
    }

    private WorkflowListItemResponse toWorkflowListItem(WorkflowEntity workflow) {
        List<ActionEntity> actions = listWorkflowActionEntities(workflow.getId());
        return new WorkflowListItemResponse(
                workflow.getId(),
                RuntimeStatusView.workflowStatus(actions),
                workflow.getCreatedAt(),
                resolveIssue(workflow)
        );
    }

    private LocalDateTime terminalFinishedAt(String status, ActionEntity action) {
        if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
            return null;
        }
        if (action.getSubmitTime() != null) {
            return action.getSubmitTime();
        }
        return action.getReclaimTime();
    }

    private String resolveIssue(WorkflowEntity workflow) {
        if (workflow.getDescription() != null && !workflow.getDescription().isBlank()) {
            return workflow.getDescription().trim();
        }
        return workflow.getName();
    }

    private JsonNode latestActionResultNode(Long actionId) {
        ActionLogEntity latestLog = actionLogMapper.selectOne(new LambdaQueryWrapper<ActionLogEntity>()
                .eq(ActionLogEntity::getActionId, actionId)
                .orderByDesc(ActionLogEntity::getCreatedAt)
                .orderByDesc(ActionLogEntity::getId)
                .last("LIMIT 1"));
        if (latestLog == null || latestLog.getResult() == null || latestLog.getResult().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(latestLog.getResult());
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private LocalDateTime resolveStartedAt(ActionEntity action) {
        if (action.getExecutionStartedAt() != null) {
            return action.getExecutionStartedAt();
        }
        return action.getClaimTime();
    }

    private LocalDateTime resolveWorkflowFinishedAt(List<ActionEntity> actions, String workflowStatus) {
        if (!"COMPLETED".equals(workflowStatus) && !"FAILED".equals(workflowStatus)) {
            return null;
        }
        return actions.stream()
                .map(action -> terminalFinishedAt(RuntimeStatusView.actionStatus(action.getStatus()), action))
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private Long computeDurationSeconds(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null) {
            return null;
        }
        LocalDateTime effectiveFinishedAt = finishedAt != null ? finishedAt : LocalDateTime.now();
        if (effectiveFinishedAt.isBefore(startedAt)) {
            return 0L;
        }
        return Duration.between(startedAt, effectiveFinishedAt).getSeconds();
    }

    private Integer readIntegerField(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        if (!value.isNumber()) {
            return null;
        }
        return value.intValue();
    }

    private String readStringField(JsonNode node, String fieldName) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(fieldName);
        if (!value.isTextual()) {
            return "";
        }
        return value.asText();
    }

    private boolean hasDependencyNoise(JsonNode resultNode) {
        if (resultNode == null || !resultNode.isObject()) {
            return false;
        }
        return scanNoise(resultNode.path("matches"), "path")
                || scanNoise(resultNode.path("sources"), "file")
                || scanNoise(resultNode.path("retrieval"), "path");
    }

    private boolean scanNoise(JsonNode node, String pathField) {
        if (!node.isArray()) {
            return false;
        }
        for (JsonNode entry : node) {
            if (!entry.isObject()) {
                continue;
            }
            String path = entry.path(pathField).asText("").toLowerCase(Locale.ROOT);
            if (path.contains("/venv/")
                    || path.contains("/.venv/")
                    || path.contains("site-packages/")
                    || path.contains("/node_modules/")) {
                return true;
            }
        }
        return false;
    }

    private String buildShortResult(
            String actionType,
            JsonNode resultNode,
            String errorMessage,
            Integer matchCount,
            Integer sourceCount,
            Integer retrievalCount
    ) {
        if ("search_semantic".equals(actionType) && matchCount != null) {
            return "matches: " + matchCount;
        }
        if ("build_context_pack".equals(actionType)) {
            if (sourceCount != null && retrievalCount != null) {
                return "sources: " + sourceCount + ", retrievalCount: " + retrievalCount;
            }
            if (sourceCount != null) {
                return "sources: " + sourceCount;
            }
        }

        String summary = readStringField(resultNode, "summary");
        if (!summary.isBlank()) {
            return toSingleLine(summary, 200);
        }

        String content = readStringField(resultNode, "content");
        if (!content.isBlank()) {
            return toSingleLine(content, 200);
        }

        if (errorMessage != null && !errorMessage.isBlank()) {
            return toSingleLine(errorMessage, 200);
        }

        return "";
    }

    private List<String> extractKeyFindings(String actionType, JsonNode resultNode, String shortResult) {
        if (!"generate_explanation".equals(actionType)
                && !"design_solution".equals(actionType)
                && !"review_code".equals(actionType)) {
            return List.of();
        }

        String summary = readStringField(resultNode, "summary");
        if (summary.isBlank()) {
            summary = readStringField(resultNode, "content");
        }

        if (summary.isBlank()) {
            if (shortResult == null || shortResult.isBlank()) {
                return List.of();
            }
            return List.of(shortResult);
        }

        List<String> findings = new ArrayList<>();
        String normalizedSummary = preprocessFindingSummary(summary);
        outer:
        for (String rawLine : normalizedSummary.split("\\R")) {
            String line = normalizeFindingLine(rawLine);
            if (line.isBlank()) {
                continue;
            }

            for (String candidate : splitFindingCandidates(line)) {
                String normalizedCandidate = normalizeFindingLine(candidate);
                if (normalizedCandidate.isBlank()) {
                    continue;
                }
                findings.add(toSingleLine(normalizedCandidate, 120));
                if (findings.size() >= 3) {
                    break outer;
                }
            }
        }

        if (findings.isEmpty()) {
            findings.add(toSingleLine(normalizeFindingLine(summary), 120));
        }

        return findings;
    }

    private String preprocessFindingSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }

        return summary
            .replaceAll("(?i)(Summary|Findings|Risks|Open Questions|Interaction Flow|Key Components)\\s*[:：]", "\n$1: ")
            .replaceAll("(结论|发现|代码位置|建议修复|风险)\\s*[:：]", "\n$1: ")
            .replace(" | ", "\n");
    }

    private List<String> splitFindingCandidates(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }

        List<String> candidates = new ArrayList<>();
        String[] sentenceParts = line.split("(?<=[。！？!?；;.])\\s+");
        for (String sentencePart : sentenceParts) {
            String candidate = sentencePart == null ? "" : sentencePart.trim();
            if (candidate.isBlank()) {
                continue;
            }

            if (candidate.length() > 140) {
                String[] commaParts = candidate.split("(?<=[，,])\\s+");
                for (String commaPart : commaParts) {
                    String trimmed = commaPart == null ? "" : commaPart.trim();
                    if (!trimmed.isBlank()) {
                        candidates.add(trimmed);
                    }
                }
                continue;
            }

            candidates.add(candidate);
        }

        if (candidates.isEmpty()) {
            return List.of(line);
        }
        return candidates;
    }

    private String normalizeFindingLine(String rawLine) {
        if (rawLine == null) {
            return "";
        }

        String line = rawLine.trim();
        if (line.isBlank()) {
            return "";
        }

        if (line.startsWith("#") || line.startsWith("|") || line.startsWith("```") || line.startsWith("---")) {
            return "";
        }

        if (line.startsWith("- ") || line.startsWith("* ")) {
            line = line.substring(2).trim();
        }
        if (line.startsWith("•")) {
            line = line.substring(1).trim();
        }

        line = line.replaceAll("^\\d+[\\.)]\\s*", "");
        line = line.replaceAll("^\\[(.*?)\\]", "");
        line = line.replace("**", "").replace("`", "").trim();
        line = line.replaceAll("^(结论|发现|代码位置|建议修复|风险)\\s*[:：]?\\s*", "").trim();

        if (line.isBlank()) {
            return "";
        }

        String lowerLine = line.toLowerCase(Locale.ROOT);
        if (lowerLine.equals("summary")
                || lowerLine.equals("findings")
                || lowerLine.equals("risks")
                || lowerLine.equals("open questions")) {
            return "";
        }

        return line;
    }

    private String toSingleLine(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 3) + "...";
    }

    private List<String> deduplicatePreservingOrder(List<String> values) {
        List<String> deduplicated = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (seen.add(value)) {
                deduplicated.add(value);
            }
        }
        return deduplicated;
    }

    private WorkflowStatus deriveStatus(List<ActionEntity> actions) {
        if (actions.isEmpty()) {
            return WorkflowStatus.CREATED;
        }
        boolean allSucceeded = actions.stream()
                .allMatch(action -> ActionStatus.SUCCEEDED.name().equals(action.getStatus()));
        if (allSucceeded) {
            return WorkflowStatus.COMPLETED;
        }
        boolean anyFailed = actions.stream()
                .anyMatch(action -> ActionStatus.FAILED.name().equals(action.getStatus())
                        || ActionStatus.DEAD_LETTER.name().equals(action.getStatus()));
        if (anyFailed) {
            return WorkflowStatus.FAILED;
        }
        return WorkflowStatus.RUNNING;
    }
}