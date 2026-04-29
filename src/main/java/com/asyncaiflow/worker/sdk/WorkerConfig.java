package com.asyncaiflow.worker.sdk;

import java.time.Duration;
import java.util.List;

public record WorkerConfig(
        String serverBaseUrl,
        String workerId,
        List<String> capabilities,
        Duration pollInterval,
        Duration heartbeatInterval,
        int maxActions
) {
}