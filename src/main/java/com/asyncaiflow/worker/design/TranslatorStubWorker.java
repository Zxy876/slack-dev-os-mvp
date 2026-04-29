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
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class TranslatorStubWorker implements WorkerActionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranslatorStubWorker.class);

    private final DesignTaskMapper designTaskMapper;
    private final ObjectMapper objectMapper;

    public TranslatorStubWorker(DesignTaskMapper designTaskMapper, ObjectMapper objectMapper) {
        this.designTaskMapper = designTaskMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkerExecutionResult execute(ActionAssignment assignment) {
        if (!DesignWorkflowConstants.TRANSLATOR_ACTION_TYPE.equals(assignment.type())) {
            return WorkerExecutionResult.failed(
                    "unsupported action type",
                    "TranslatorStubWorker only supports " + DesignWorkflowConstants.TRANSLATOR_ACTION_TYPE
            );
        }

        TranslatorPayload payload = parsePayload(assignment.payload());
        try {
            Thread.sleep(2000L);
            DesignTask designTask = requireTask(payload.taskId());
            LocalDateTime now = LocalDateTime.now();
            designTask.setStatus(DesignTaskStatus.RUNNING.name());
            designTask.setStageLabel("正在解析设计意图...");
            designTask.setProgress(30);
            designTask.setUpdatedAt(now);
            designTask.setErrorCode(null);
            designTask.setErrorMessage(null);
            designTaskMapper.updateById(designTask);

            LOGGER.info("Translator stub finished for design task {}", payload.taskId());
            return WorkerExecutionResult.succeeded(objectMapper.writeValueAsString(
                    new TranslatorResult(payload.taskId(), DesignWorkflowConstants.DSL_VERSION, "stub-translated")
            ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailed(payload.taskId(), "TRANSLATOR_STUB_INTERRUPTED", exception.getMessage());
            LOGGER.warn("Translator stub interrupted for design task {}", payload.taskId(), exception);
            return WorkerExecutionResult.failed("translator stub interrupted", exception.getMessage());
        } catch (RuntimeException | IOException exception) {
            markFailed(payload.taskId(), "TRANSLATOR_STUB_ERROR", exception.getMessage());
            LOGGER.warn("Translator stub failed for design task {}", payload.taskId(), exception);
            return WorkerExecutionResult.failed("translator stub failed", exception.getMessage());
        }
    }

    private TranslatorPayload parsePayload(String rawPayload) {
        try {
            return objectMapper.readValue(rawPayload, TranslatorPayload.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid translator payload", exception);
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

    public record TranslatorPayload(String taskId, String inputType, String prompt, String designImageUrl) {
    }

    public record TranslatorResult(String taskId, String dslVersion, String translationStatus) {
    }
}