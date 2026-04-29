package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;
import java.util.List;

public record WorkflowExecutionResponse(
        Long workflowId,
        String status,
        LocalDateTime createdAt,
        List<WorkflowActionSummaryResponse> actions
) {
}