package com.asyncaiflow.web.dto;

public record WorkflowSummaryActionResponse(
        Long actionId,
        String actionType,
        String status,
        String workerId,
        Long durationSeconds,
        String shortResult,
        Integer matchCount,
        Integer sourceCount,
        Integer retrievalCount,
        Boolean noisyRetrieval
) {
}