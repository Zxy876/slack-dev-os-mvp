package com.asyncaiflow.worker.design;

import java.io.IOException;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.asyncaiflow.design.DesignWorkflowConstants;
import com.asyncaiflow.domain.entity.DesignTask;
import com.asyncaiflow.domain.enums.DesignTaskStatus;
import com.asyncaiflow.mapper.DesignTaskMapper;
import com.asyncaiflow.worker.sdk.WorkerActionHandler;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class RendererStubWorker implements WorkerActionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RendererStubWorker.class);

    private final DesignTaskMapper designTaskMapper;
    private final ObjectMapper objectMapper;

    public RendererStubWorker(DesignTaskMapper designTaskMapper, ObjectMapper objectMapper) {
        this.designTaskMapper = designTaskMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkerExecutionResult execute(ActionAssignment assignment) {
        if (!DesignWorkflowConstants.RENDERER_ACTION_TYPE.equals(assignment.type())) {
            return WorkerExecutionResult.failed(
                    "unsupported action type",
                    "RendererStubWorker only supports " + DesignWorkflowConstants.RENDERER_ACTION_TYPE
            );
        }

        RendererPayload payload = parsePayload(assignment.payload());
        try {
            DesignTask designTask = requireTask(payload.taskId());

            // When upstream Python workers handled translation, topology, and nesting,
            // the design task may still be PENDING. Publish one intermediate milestone
            // before the renderer jumps to 70 % so the frontend shows staged progress.
            if (DesignTaskStatus.PENDING.name().equals(designTask.getStatus())) {
                designTask.setStatus(DesignTaskStatus.RUNNING.name());
                designTask.setStageLabel("设计方案已验证并完成排料");
                designTask.setProgress(60);
                designTask.setUpdatedAt(LocalDateTime.now());
                designTaskMapper.updateById(designTask);
            }

            LocalDateTime runningAt = LocalDateTime.now();
            designTask.setStatus(DesignTaskStatus.RUNNING.name());
            designTask.setStageLabel("正在生成 3D 模型...");
            designTask.setProgress(70);
            designTask.setUpdatedAt(runningAt);
            designTaskMapper.updateById(designTask);

            Thread.sleep(3000L);

            LocalDateTime finishedAt = LocalDateTime.now();
            designTask.setStatus(DesignTaskStatus.SUCCEEDED.name());
            designTask.setStageLabel("正在生成 3D 模型...");
            designTask.setProgress(100);
            designTask.setResultModelUrl(DesignWorkflowConstants.MOCK_MODEL_URL);
            designTask.setResultThumbnailUrl(DesignWorkflowConstants.MOCK_THUMBNAIL_URL);
            designTask.setErrorCode(null);
            designTask.setErrorMessage(null);
            designTask.setUpdatedAt(finishedAt);
            designTask.setFinishedAt(finishedAt);
            designTaskMapper.updateById(designTask);

            LOGGER.info("Renderer stub finished for design task {}", payload.taskId());
            return WorkerExecutionResult.succeeded(objectMapper.writeValueAsString(
                    new RendererResult(
                            payload.taskId(),
                            DesignWorkflowConstants.MOCK_MODEL_URL,
                            DesignWorkflowConstants.MOCK_THUMBNAIL_URL,
                            DesignWorkflowConstants.DSL_VERSION)
            ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailed(payload.taskId(), "RENDERER_STUB_INTERRUPTED", exception.getMessage());
            LOGGER.warn("Renderer stub interrupted for design task {}", payload.taskId(), exception);
            return WorkerExecutionResult.failed("renderer stub interrupted", exception.getMessage());
        } catch (RuntimeException | IOException exception) {
            markFailed(payload.taskId(), "RENDERER_STUB_ERROR", exception.getMessage());
            LOGGER.warn("Renderer stub failed for design task {}", payload.taskId(), exception);
            return WorkerExecutionResult.failed("renderer stub failed", exception.getMessage());
        }
    }

    private RendererPayload parsePayload(String rawPayload) {
        try {
            return objectMapper.readValue(rawPayload, RendererPayload.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid renderer payload", exception);
        }
    }

    private DesignTask requireTask(String taskId) {
        DesignTask designTask = designTaskMapper.selectById(taskId);
        if (designTask == null) {
            throw new IllegalArgumentException("Design task not found: " + taskId);
        }
        return designTask;
    }

    private void markFailed(String taskId, String errorCode, String errorMessage) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        DesignTask designTask = designTaskMapper.selectById(taskId);
        if (designTask == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        designTask.setStatus(DesignTaskStatus.FAILED.name());
        designTask.setErrorCode(errorCode);
        designTask.setErrorMessage(errorMessage);
        designTask.setUpdatedAt(now);
        designTask.setFinishedAt(now);
        designTaskMapper.updateById(designTask);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RendererPayload(String taskId, String consumedLengthMm, String utilization, String dslVersion) {
    }

    public record RendererResult(String taskId, String modelUrl, String thumbnailUrl, String dslVersion) {
    }
}