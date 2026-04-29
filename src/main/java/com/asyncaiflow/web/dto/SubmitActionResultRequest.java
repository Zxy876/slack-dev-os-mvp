package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubmitActionResultRequest(
        @NotBlank(message = "must not be blank") String workerId,
        @NotNull(message = "must not be null") Long actionId,
        @NotBlank(message = "must not be blank") String status,
        String result,
        String errorMessage
) {
}