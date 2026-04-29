package com.asyncaiflow.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

public record PlannerPlanRequest(
        @NotBlank(message = "must not be blank") String issue,
        @JsonProperty("repo_context") String repoContext,
        String file
) {
}
