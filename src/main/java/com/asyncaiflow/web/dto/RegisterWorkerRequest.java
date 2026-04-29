package com.asyncaiflow.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record RegisterWorkerRequest(
        @NotBlank(message = "must not be blank") String workerId,
        @NotEmpty(message = "must not be empty") List<@NotBlank(message = "must not be blank") String> capabilities
) {
}