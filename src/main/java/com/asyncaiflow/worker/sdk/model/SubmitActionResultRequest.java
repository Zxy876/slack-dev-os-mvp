package com.asyncaiflow.worker.sdk.model;

public record SubmitActionResultRequest(
        String workerId,
        Long actionId,
        String status,
        String result,
        String errorMessage
) {
}