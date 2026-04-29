package com.asyncaiflow.web.dto;

public record RunResponse(
        Long workflowId,
        int actionCount,
        String statusUrl
) {}
