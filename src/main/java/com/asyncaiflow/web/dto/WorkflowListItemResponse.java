package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;

public record WorkflowListItemResponse(
        Long workflowId,
        String status,
        LocalDateTime createdAt,
        String issue
) {
}