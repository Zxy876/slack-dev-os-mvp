package com.asyncaiflow.web.dto.design;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Current visible status of a design task")
public record TaskStatusResponse(
        @Schema(description = "Design task identifier", example = "task_f6d24f7a2fca4d1ba0bdfbc2e182d546")
        String taskId,
        @Schema(description = "Task status", example = "RUNNING")
        String status,
        @Schema(description = "Progress percentage from 0 to 100", example = "45")
        Integer progress,
        @Schema(description = "Designer-friendly stage label", example = "正在生成设计结果")
        String stageLabel,
        @Schema(description = "Last update timestamp")
        LocalDateTime updatedAt
) {
}