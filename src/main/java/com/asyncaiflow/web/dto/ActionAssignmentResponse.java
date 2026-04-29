package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;

public record ActionAssignmentResponse(
        Long actionId,
        Long workflowId,
        String type,
        String payload,
        Integer retryCount,
        LocalDateTime leaseExpireAt,
        String slackThreadId,
        String notepadRef
) {
}