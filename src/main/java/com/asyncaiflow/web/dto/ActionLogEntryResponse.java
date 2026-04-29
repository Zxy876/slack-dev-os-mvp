package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;

public record ActionLogEntryResponse(
        String workerId,
        String status,
        LocalDateTime createdAt,
        Object result
) {
}