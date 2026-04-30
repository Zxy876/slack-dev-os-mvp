package com.asyncaiflow.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateActionRequest(
        @NotNull(message = "must not be null") Long workflowId,
        @NotBlank(message = "must not be blank") String type,
        String payload,
        List<Long> upstreamActionIds,
        @PositiveOrZero(message = "must be greater than or equal to 0") Integer maxRetryCount,
        @PositiveOrZero(message = "must be greater than or equal to 0") Integer backoffSeconds,
        @Positive(message = "must be greater than 0") Integer executionTimeoutSeconds,
        String slackThreadId
) {
}