package com.asyncaiflow.worker.sdk.model;

import java.util.List;

public record WorkerRegistrationRequest(String workerId, List<String> capabilities) {
}