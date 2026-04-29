package com.asyncaiflow.web.dto.design;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Final result payload for a completed design task")
public record TaskResultResponse(
        @Schema(description = "Design task identifier", example = "task_f6d24f7a2fca4d1ba0bdfbc2e182d546")
        String taskId,
        @Schema(description = "Task status", example = "SUCCEEDED")
        String status,
        @Schema(description = "3D preview resources")
        Preview3dResponse preview3d,
        @Schema(description = "2D fabric nesting layout from the DP worker", nullable = true)
        NestingLayoutResponse nestingLayout,
        @Schema(description = "DSL version generated for this task", example = "v1", nullable = true)
        String dslVersion,
        @Schema(description = "Task creation timestamp")
        LocalDateTime createdAt,
        @Schema(description = "Task completion timestamp", nullable = true)
        LocalDateTime finishedAt
) {
}