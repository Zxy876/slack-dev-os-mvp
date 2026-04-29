package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;

public record WorkerHeartbeatRequest(
        @NotBlank(message = "must not be blank") String workerId
) {
}
