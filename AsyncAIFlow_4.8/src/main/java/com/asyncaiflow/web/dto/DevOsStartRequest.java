package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DevOs interrupt入口请求 — 对应 OS 中断：用户通过 Slack 发起的系统调用。
 */
public record DevOsStartRequest(
        @NotBlank(message = "must not be blank") String text,
        @NotBlank(message = "must not be blank") String slackThreadId
) {
}
