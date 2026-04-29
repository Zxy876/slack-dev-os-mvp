package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;
import java.util.List;

public record WorkflowSummaryResponse(
        Long workflowId,
        String status,
        String issue,
        LocalDateTime createdAt,
        LocalDateTime finishedAt,
        Long durationSeconds,
        List<String> plan,
        List<WorkflowSummaryActionResponse> actions,
        WorkflowContextQualityResponse contextQuality,
        List<String> keyFindings,
        List<String> warnings,
        List<String> suggestions
) {
}