package com.asyncaiflow.worker.sdk.model;

import java.time.LocalDateTime;

public record ActionAssignment(
        Long actionId,
        Long workflowId,
        String type,
        String payload,
        Integer retryCount,
        LocalDateTime leaseExpireAt
) {
}