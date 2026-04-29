package com.asyncaiflow.worker.sdk;

public record WorkerExecutionResult(
        String status,
        String result,
        String errorMessage
) {

    public static WorkerExecutionResult succeeded(String result) {
        return new WorkerExecutionResult("SUCCEEDED", result, null);
    }

    public static WorkerExecutionResult failed(String result, String errorMessage) {
        return new WorkerExecutionResult("FAILED", result, errorMessage);
    }
}