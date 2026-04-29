package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;

public record WorkflowActionSummaryResponse(
        Long actionId,
        String type,
        String status,
        String workerId,
        LocalDateTime createdAt,
        LocalDateTime finishedAt
) {
}