package com.asyncaiflow.web.dto;

public record WorkflowContextQualityResponse(
        Integer retrievalCount,
        Integer sourceCount,
        Integer noisyActionCount,
        Boolean noiseDetected,
        String noiseSummary
) {
}
