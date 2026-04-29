package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWorkflowRequest(
        @NotBlank(message = "must not be blank") String name,
        String description
) {
}