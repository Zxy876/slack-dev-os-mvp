package com.asyncaiflow.web.dto.design;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response returned after a design task is created")
public record CreateDesignTaskResponse(
        @Schema(description = "Design task identifier", example = "task_f6d24f7a2fca4d1ba0bdfbc2e182d546")
        String taskId,
        @Schema(description = "Current task status", example = "PENDING")
        String status,
        @Schema(description = "Task creation timestamp")
        LocalDateTime createdAt
) {
}