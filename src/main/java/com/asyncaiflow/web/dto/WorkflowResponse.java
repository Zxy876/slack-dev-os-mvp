package com.asyncaiflow.web.dto;

import java.time.LocalDateTime;

public record WorkflowResponse(
        Long id,
        String name,
        String description,
        String status,
        LocalDateTime createdAt
) {
}