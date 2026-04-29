package com.asyncaiflow.worker.sdk.model;

public record ApiEnvelope<T>(boolean success, String message, T data) {
}