package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;
import java.util.List;

public record WorkerResponse(
        String workerId,
        List<String> capabilities,
        String status,
        LocalDateTime lastHeartbeatAt
) {
}