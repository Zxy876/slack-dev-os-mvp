package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ActionResponse(
        Long id,
        Long workflowId,
        String type,
        String status,
        String workerId,
        Integer retryCount,
        Integer maxRetryCount,
        Integer backoffSeconds,
        Integer executionTimeoutSeconds,
        LocalDateTime leaseExpireAt,
        LocalDateTime nextRunAt,
        LocalDateTime claimTime,
        LocalDateTime firstRenewTime,
        LocalDateTime lastRenewTime,
        LocalDateTime submitTime,
        LocalDateTime reclaimTime,
        Integer leaseRenewSuccessCount,
        Integer leaseRenewFailureCount,
        LocalDateTime lastLeaseRenewAt,
        LocalDateTime executionStartedAt,
        Long lastExecutionDurationMs,
        String lastReclaimReason,
        String payload,
        String errorMessage,
        List<Long> upstreamActionIds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}