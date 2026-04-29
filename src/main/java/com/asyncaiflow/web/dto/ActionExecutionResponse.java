package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ActionExecutionResponse(
        Long actionId,
        Long workflowId,
        String type,
        String status,
        String workerId,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Object payload,
        Object result,
        String error,
        List<ActionLogEntryResponse> logs
) {
}