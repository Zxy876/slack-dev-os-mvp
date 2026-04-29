package com.asyncaiflow.worker.sdk.model;

import java.time.LocalDateTime;
import java.util.List;

public record WorkerSnapshot(
        String workerId,
        List<String> capabilities,
        String status,
        LocalDateTime lastHeartbeatAt
) {
}