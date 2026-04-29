package com.asyncaiflow.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.asyncaiflow.design.DesignWorkflowConstants;
import com.asyncaiflow.domain.entity.ActionEntity;
import com.asyncaiflow.domain.entity.ActionLogEntity;
import com.asyncaiflow.domain.entity.DesignTask;
import com.asyncaiflow.domain.enums.ActionStatus;
import com.asyncaiflow.domain.enums.DesignTaskStatus;
import com.asyncaiflow.mapper.ActionLogMapper;
import com.asyncaiflow.mapper.ActionMapper;
import com.asyncaiflow.mapper.DesignTaskMapper;
import com.asyncaiflow.service.ActionService;
import com.asyncaiflow.service.DesignTaskService;
import com.asyncaiflow.service.WorkflowService;
import com.asyncaiflow.support.ApiException;
import com.asyncaiflow.support.JsonCodec;
import com.asyncaiflow.web.dto.ActionResponse;
import com.asyncaiflow.web.dto.CreateActionRequest;
import com.asyncaiflow.web.dto.CreateWorkflowRequest;
import com.asyncaiflow.web.dto.WorkflowResponse;
import com.asyncaiflow.web.dto.design.CreateDesignRequest;
import com.asyncaiflow.web.dto.design.CreateDesignTaskResponse;
import com.asyncaiflow.web.dto.design.NestingLayoutResponse;
import com.asyncaiflow.web.dto.design.PlacementResponse;
import com.asyncaiflow.web.dto.design.Preview3dResponse;
import com.asyncaiflow.web.dto.design.TaskResultResponse;
import com.asyncaiflow.web.dto.design.TaskStatusResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class DesignTaskServiceImpl implements DesignTaskService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DesignTaskServiceImpl.class);

    private final DesignTaskMapper designTaskMapper;
    private final WorkflowService workflowService;
    private final ActionService actionService;
    private final ActionMapper actionMapper;
    private final ActionLogMapper actionLogMapper;
    private final JsonCodec jsonCodec;
    private final ObjectMapper objectMapper;

    public DesignTaskServiceImpl(
            DesignTaskMapper designTaskMapper,
            WorkflowService workflowService,
            ActionService actionService,
            ActionMapper actionMapper,
            ActionLogMapper actionLogMapper,
            JsonCodec jsonCodec,
            ObjectMapper objectMapper
    ) {
        this.designTaskMapper = designTaskMapper;
        this.workflowService = workflowService;
        this.actionService = actionService;
        this.actionMapper = actionMapper;
        this.actionLogMapper = actionLogMapper;
        this.jsonCodec = jsonCodec;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public CreateDesignTaskResponse createDesignTask(CreateDesignRequest request) {
        validateCreateRequest(request);
        boolean hasRawScan = hasRawScan(request);

        LocalDateTime now = LocalDateTime.now();
        DesignTask designTask = new DesignTask();
        designTask.setId(generateTaskId());
        designTask.setStatus(DesignTaskStatus.PENDING.name());
        designTask.setInputType(request.inputType().trim().toUpperCase(Locale.ROOT));
        designTask.setPromptText(request.prompt());
        designTask.setDesignImageUrl(request.designImageUrl());
        designTask.setProgress(0);
        designTask.setStageLabel("设计任务已创建");
        designTask.setCreatedAt(now);
        designTask.setUpdatedAt(now);
        designTaskMapper.insert(designTask);

        WorkflowResponse workflow = workflowService.createWorkflow(new CreateWorkflowRequest(
            "design-task-" + designTask.getId(),
            buildWorkflowDescription(request)
        ));
        designTask.setWorkflowId(workflow.id());
        designTask.setUpdatedAt(LocalDateTime.now());
        designTaskMapper.updateById(designTask);

        // Step 1: NL → Design DSL  (Python GPT Worker)
        // Real LLM calls can exceed the default short lease in local development,
        // so give this step a longer execution window than the pure local workers.
        ActionResponse nlAction = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            DesignWorkflowConstants.NL_DESIGN_ACTION_TYPE,
            buildTranslatorPayload(designTask, request),
            null,
            0,
            1,
            120,
            null
        ));

        ActionResponse scanAction = null;
        if (hasRawScan) {
            scanAction = actionService.createAction(new CreateActionRequest(
                workflow.id(),
                DesignWorkflowConstants.SCAN_PROCESS_ACTION_TYPE,
                buildScanProcessPayload(designTask, request),
                null,
                0,
                1,
                120,
                null
            ));
        }

        // Step 2: Topology BFS validation  (Python BFS Worker)
        ActionResponse topologyAction = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            DesignWorkflowConstants.TOPOLOGY_VALIDATE_ACTION_TYPE,
            buildTopologyPayload(designTask),
            java.util.List.of(nlAction.id()),
            0,
            1,
            30,
            null
        ));

        // Step 3: 2-D nesting / fabric layout  (Python DP Worker)
        ActionResponse nestingAction = actionService.createAction(new CreateActionRequest(
            workflow.id(),
            DesignWorkflowConstants.DP_NESTING_ACTION_TYPE,
            buildNestingPayload(designTask, request),
            java.util.List.of(nlAction.id(), topologyAction.id()),
            0,
            1,
            30,
            null
        ));

        // Step 4: 3-D rendering stub
        List<Long> rendererDependsOn = new ArrayList<>();
        rendererDependsOn.add(nlAction.id());
        rendererDependsOn.add(nestingAction.id());
        if (scanAction != null) {
            rendererDependsOn.add(scanAction.id());
        }

        actionService.createAction(new CreateActionRequest(
            workflow.id(),
            DesignWorkflowConstants.RENDERER_ACTION_TYPE,
            buildRendererPayload(designTask, request, scanAction != null),
            rendererDependsOn,
            0,
            1,
            30,
            null
        ));

        LOGGER.info("Created design task: id={}, inputType={}, hasPrompt={}, hasImage={}, hasRawScan={}, optionsPresent={}",
                designTask.getId(),
                designTask.getInputType(),
                request.prompt() != null && !request.prompt().isBlank(),
                request.designImageUrl() != null && !request.designImageUrl().isBlank(),
                hasRawScan,
                request.options() != null && !request.options().isEmpty());
        LOGGER.info("Submitted internal workflow {} for design task {}", workflow.id(), designTask.getId());

        return new CreateDesignTaskResponse(designTask.getId(), designTask.getStatus(), designTask.getCreatedAt());
    }

    @Override
    public TaskStatusResponse getTaskStatus(String taskId) {
        DesignTask designTask = requireTask(taskId);
        designTask = synchronizeTaskRuntimeState(designTask);
        return new TaskStatusResponse(
                designTask.getId(),
                designTask.getStatus(),
                designTask.getProgress(),
                designTask.getStageLabel(),
                designTask.getUpdatedAt()
        );
    }

    @Override
    public TaskResultResponse getTaskResult(String taskId) {
        DesignTask designTask = requireTask(taskId);
        designTask = synchronizeTaskRuntimeState(designTask);
        if (!DesignTaskStatus.SUCCEEDED.name().equals(designTask.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_READY", "task is still running");
        }

        NestingLayoutResponse nestingLayout = null;
        if (designTask.getWorkflowId() != null) {
            nestingLayout = fetchNestingLayout(designTask.getWorkflowId());
        }

        return new TaskResultResponse(
                designTask.getId(),
                designTask.getStatus(),
                new Preview3dResponse(designTask.getResultModelUrl(), designTask.getResultThumbnailUrl()),
                nestingLayout,
                DesignWorkflowConstants.DSL_VERSION,
                designTask.getCreatedAt(),
                designTask.getFinishedAt()
        );
    }

    private DesignTask requireTask(String taskId) {
        DesignTask designTask = designTaskMapper.selectById(taskId);
        if (designTask == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "design task not found: " + taskId);
        }
        return designTask;
    }

    private void validateCreateRequest(CreateDesignRequest request) {
        boolean hasPrompt = request.prompt() != null && !request.prompt().isBlank();
        boolean hasImage = request.designImageUrl() != null && !request.designImageUrl().isBlank();
        boolean hasRawScan = hasRawScan(request);
        if (!hasPrompt && !hasImage && !hasRawScan) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "prompt, designImageUrl or rawScanUrl must be provided");
        }
    }

    private boolean hasRawScan(CreateDesignRequest request) {
        return request.rawScanUrl() != null && !request.rawScanUrl().isBlank();
    }

    private String generateTaskId() {
        return "task_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String buildWorkflowDescription(CreateDesignRequest request) {
        if (request.prompt() != null && !request.prompt().isBlank()) {
            return request.prompt().trim();
        }
        return "image-driven design task";
    }

    private String buildTranslatorPayload(DesignTask designTask, CreateDesignRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", designTask.getId());
        payload.put("inputType", designTask.getInputType());
        payload.put("prompt", designTask.getPromptText() != null ? designTask.getPromptText() : "");
        if (designTask.getDesignImageUrl() != null) {
            payload.put("designImageUrl", designTask.getDesignImageUrl());
        }
        if (hasRawScan(request)) {
            payload.put("rawScanUrl", request.rawScanUrl().trim());
        }
        // Pass raw options (style, etc.) so Python worker can enrich the design prompt
        if (request.options() != null && !request.options().isEmpty()) {
            payload.set("options", objectMapper.valueToTree(request.options()));
        }
        return jsonCodec.write(payload);
    }

    private String buildScanProcessPayload(DesignTask designTask, CreateDesignRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", designTask.getId());
        payload.put("rawModelPath", request.rawScanUrl().trim());

        ObjectNode scan = payload.putObject("scan");
        scan.put("rawModelPath", request.rawScanUrl().trim());
        scan.put("outputDir", "/tmp/asyncaiflow-scan-output");

        if (request.options() != null) {
            Object targetFaces = request.options().get("scanTargetFaces");
            if (targetFaces != null) {
                scan.set("targetFaces", objectMapper.valueToTree(targetFaces));
            }
            Object minDiameterPct = request.options().get("scanIsolatedPieceMinDiameterPct");
            if (minDiameterPct != null) {
                scan.set("isolatedPieceMinDiameterPct", objectMapper.valueToTree(minDiameterPct));
            }
        }

        return jsonCodec.write(payload);
    }

    private String buildTopologyPayload(DesignTask designTask) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", designTask.getId());
        // Inject the full DSL object from the upstream GPT worker result
        ObjectNode inject = payload.putObject("inject");
        inject.put("dsl", "$.upstreamByType." + DesignWorkflowConstants.NL_DESIGN_ACTION_TYPE + ".result.dsl");
        return jsonCodec.write(payload);
    }

    private String buildNestingPayload(DesignTask designTask, CreateDesignRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", designTask.getId());
        payload.put("fabricWidthMm", 1500);
        payload.put("gapMm", 10);

        if (request.options() != null) {
            Object fabricWidthMm = request.options().get("fabricWidthMm");
            if (fabricWidthMm != null) {
                payload.set("fabricWidthMm", objectMapper.valueToTree(fabricWidthMm));
            }
            Object gapMm = request.options().get("gapMm");
            if (gapMm != null) {
                payload.set("gapMm", objectMapper.valueToTree(gapMm));
            }
        }

        ObjectNode inject = payload.putObject("inject");
        inject.put("dsl", "$.upstreamByType." + DesignWorkflowConstants.NL_DESIGN_ACTION_TYPE + ".result.dsl");
        inject.put("topologyReport", "$.upstreamByType." + DesignWorkflowConstants.TOPOLOGY_VALIDATE_ACTION_TYPE + ".result");
        return jsonCodec.write(payload);
    }

    private String buildRendererPayload(DesignTask designTask, CreateDesignRequest request, boolean withScanInput) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", designTask.getId());
        payload.put("outputDir", "/tmp/asyncaiflow-assembly-output");
        // Inject nesting summary from the direct upstream (dp_nesting)
        ObjectNode inject = payload.putObject("inject");
        inject.put("consumedLengthMm", "$.upstreamByType." + DesignWorkflowConstants.DP_NESTING_ACTION_TYPE + ".result.consumedLengthMm");
        inject.put("utilization", "$.upstreamByType." + DesignWorkflowConstants.DP_NESTING_ACTION_TYPE + ".result.utilization");
        inject.put("dslVersion", "$.upstreamByType." + DesignWorkflowConstants.DP_NESTING_ACTION_TYPE + ".result.meta.dslVersion");
        ObjectNode dslInjectRule = inject.putObject("dsl");
        dslInjectRule.put("from", "$.upstreamByType." + DesignWorkflowConstants.DP_NESTING_ACTION_TYPE + ".result.dsl");
        dslInjectRule.putArray("fallbackFrom")
            .add("$.upstreamByType." + DesignWorkflowConstants.NL_DESIGN_ACTION_TYPE + ".result.dsl");
        dslInjectRule.put("required", true);
        if (withScanInput) {
            inject.put("baseModelUrl", "$.upstreamByType." + DesignWorkflowConstants.SCAN_PROCESS_ACTION_TYPE + ".result.modelUrl");
            inject.put("baseModelPath", "$.upstreamByType." + DesignWorkflowConstants.SCAN_PROCESS_ACTION_TYPE + ".result.glbPath");
        }

        if (hasRawScan(request)) {
            payload.put("rawScanUrl", request.rawScanUrl().trim());
        }
        return jsonCodec.write(payload);
    }

    private DesignTask synchronizeTaskRuntimeState(DesignTask designTask) {
        if (designTask.getWorkflowId() == null || designTask.getWorkflowId() <= 0) {
            return designTask;
        }

        List<ActionEntity> actions = actionMapper.selectList(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getWorkflowId, designTask.getWorkflowId())
                .orderByAsc(ActionEntity::getCreatedAt)
                .orderByAsc(ActionEntity::getId));
        if (actions.isEmpty()) {
            return designTask;
        }

        boolean allSucceeded = actions.stream()
                .allMatch(action -> ActionStatus.SUCCEEDED.name().equals(action.getStatus()));
        boolean anyFailed = actions.stream()
                .anyMatch(action -> ActionStatus.FAILED.name().equals(action.getStatus())
                        || ActionStatus.DEAD_LETTER.name().equals(action.getStatus()));

        LocalDateTime now = LocalDateTime.now();
        boolean changed = false;

        if (allSucceeded) {
            Map<String, String> renderResult = loadRendererResult(designTask.getWorkflowId());
            if (!DesignTaskStatus.SUCCEEDED.name().equals(designTask.getStatus())) {
                designTask.setStatus(DesignTaskStatus.SUCCEEDED.name());
                changed = true;
            }
            if (!"拼装模型已生成".equals(designTask.getStageLabel())) {
                designTask.setStageLabel("拼装模型已生成");
                changed = true;
            }
            if (!Integer.valueOf(100).equals(designTask.getProgress())) {
                designTask.setProgress(100);
                changed = true;
            }
            String modelUrl = renderResult.get("modelUrl");
            if (modelUrl != null && !modelUrl.isBlank() && !modelUrl.equals(designTask.getResultModelUrl())) {
                designTask.setResultModelUrl(modelUrl);
                changed = true;
            }
            String thumbnailUrl = renderResult.get("thumbnailUrl");
            if (thumbnailUrl != null && !thumbnailUrl.isBlank() && !thumbnailUrl.equals(designTask.getResultThumbnailUrl())) {
                designTask.setResultThumbnailUrl(thumbnailUrl);
                changed = true;
            }
            if (designTask.getFinishedAt() == null) {
                designTask.setFinishedAt(now);
                changed = true;
            }
        } else if (anyFailed) {
            if (!DesignTaskStatus.FAILED.name().equals(designTask.getStatus())) {
                designTask.setStatus(DesignTaskStatus.FAILED.name());
                changed = true;
            }
            if (!"设计流程执行失败".equals(designTask.getStageLabel())) {
                designTask.setStageLabel("设计流程执行失败");
                changed = true;
            }
            if (designTask.getFinishedAt() == null) {
                designTask.setFinishedAt(now);
                changed = true;
            }
        } else {
            StageProgress stageProgress = resolveStageProgress(actions);
            if (!DesignTaskStatus.RUNNING.name().equals(designTask.getStatus())) {
                designTask.setStatus(DesignTaskStatus.RUNNING.name());
                changed = true;
            }
            if (!stageProgress.stageLabel().equals(designTask.getStageLabel())) {
                designTask.setStageLabel(stageProgress.stageLabel());
                changed = true;
            }
            if (!Integer.valueOf(stageProgress.progress()).equals(designTask.getProgress())) {
                designTask.setProgress(stageProgress.progress());
                changed = true;
            }
            if (designTask.getFinishedAt() != null) {
                designTask.setFinishedAt(null);
                changed = true;
            }
        }

        if (changed) {
            designTask.setUpdatedAt(now);
            designTaskMapper.updateById(designTask);
        }
        return designTask;
    }

    private StageProgress resolveStageProgress(List<ActionEntity> actions) {
        if (isActionInFlight(actions, DesignWorkflowConstants.SCAN_PROCESS_ACTION_TYPE)) {
            return new StageProgress("正在清洗与轻量化旧衣网格...", 20);
        }
        if (isActionInFlight(actions, DesignWorkflowConstants.NL_DESIGN_ACTION_TYPE)) {
            return new StageProgress("正在解析设计意图...", 30);
        }
        if (isActionInFlight(actions, DesignWorkflowConstants.TOPOLOGY_VALIDATE_ACTION_TYPE)) {
            return new StageProgress("正在校验结构连通性...", 45);
        }
        if (isActionInFlight(actions, DesignWorkflowConstants.DP_NESTING_ACTION_TYPE)) {
            return new StageProgress("正在优化排料方案...", 60);
        }
        if (isActionInFlight(actions, DesignWorkflowConstants.RENDERER_ACTION_TYPE)) {
            return new StageProgress("正在拼装最终 3D 模型...", 80);
        }
        return new StageProgress("任务排队中...", 10);
    }

    private boolean isActionInFlight(List<ActionEntity> actions, String actionType) {
        return actions.stream()
                .anyMatch(action -> actionType.equals(action.getType())
                        && (ActionStatus.RUNNING.name().equals(action.getStatus())
                        || ActionStatus.QUEUED.name().equals(action.getStatus())
                        || ActionStatus.BLOCKED.name().equals(action.getStatus())
                        || ActionStatus.RETRY_WAIT.name().equals(action.getStatus())));
    }

    private Map<String, String> loadRendererResult(Long workflowId) {
        List<ActionEntity> rendererActions = actionMapper.selectList(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getWorkflowId, workflowId)
                .eq(ActionEntity::getType, DesignWorkflowConstants.RENDERER_ACTION_TYPE)
                .orderByDesc(ActionEntity::getCreatedAt)
                .orderByDesc(ActionEntity::getId));
        if (rendererActions.isEmpty()) {
            return Map.of();
        }

        Long actionId = rendererActions.get(0).getId();
        List<ActionLogEntity> logs = actionLogMapper.selectList(new LambdaQueryWrapper<ActionLogEntity>()
                .eq(ActionLogEntity::getActionId, actionId)
                .eq(ActionLogEntity::getStatus, ActionStatus.SUCCEEDED.name())
                .orderByDesc(ActionLogEntity::getCreatedAt)
                .orderByDesc(ActionLogEntity::getId));
        if (logs.isEmpty() || logs.get(0).getResult() == null || logs.get(0).getResult().isBlank()) {
            return Map.of();
        }

        try {
            JsonNode result = objectMapper.readTree(logs.get(0).getResult());
            String modelUrl = result.path("modelUrl").asText("");
            String thumbnailUrl = result.path("thumbnailUrl").asText("");
            return Map.of(
                    "modelUrl", modelUrl,
                    "thumbnailUrl", thumbnailUrl
            );
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Failed to parse assembly result for workflow {}: {}", workflowId, exception.getMessage());
            return Map.of();
        }
    }

    private record StageProgress(String stageLabel, int progress) {
    }

    private NestingLayoutResponse fetchNestingLayout(Long workflowId) {
        List<ActionEntity> actions = actionMapper.selectList(new LambdaQueryWrapper<ActionEntity>()
                .eq(ActionEntity::getWorkflowId, workflowId)
                .eq(ActionEntity::getType, DesignWorkflowConstants.DP_NESTING_ACTION_TYPE));
        if (actions.isEmpty()) {
            return null;
        }
        Long actionId = actions.get(0).getId();

        List<ActionLogEntity> logs = actionLogMapper.selectList(new LambdaQueryWrapper<ActionLogEntity>()
                .eq(ActionLogEntity::getActionId, actionId)
                .orderByDesc(ActionLogEntity::getCreatedAt)
                .orderByDesc(ActionLogEntity::getId));
        if (logs.isEmpty()) {
            return null;
        }

        ActionLogEntity successLog = logs.stream()
                .filter(log -> ActionStatus.SUCCEEDED.name().equals(log.getStatus()))
                .findFirst()
                .orElse(null);
        if (successLog == null || successLog.getResult() == null) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(successLog.getResult());
            double fabricWidthMm = root.path("fabricWidthMm").asDouble(0);
            double consumedLengthMm = root.path("consumedLengthMm").asDouble(0);
            double utilization = root.path("utilization").asDouble(0);

            List<PlacementResponse> placements = new ArrayList<>();
            JsonNode placementsNode = root.path("placements");
            if (placementsNode.isArray()) {
                for (JsonNode p : placementsNode) {
                    placements.add(new PlacementResponse(
                            p.path("componentId").asText(null),
                            p.path("xMm").asDouble(0),
                            p.path("yMm").asDouble(0),
                            p.path("widthMm").asDouble(0),
                            p.path("heightMm").asDouble(0),
                            p.path("rotated").asBoolean(false)
                    ));
                }
            }
            return new NestingLayoutResponse(fabricWidthMm, consumedLengthMm, utilization, placements);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse dp_nesting result for workflow {}: {}", workflowId, e.getMessage());
            return null;
        }
    }
}