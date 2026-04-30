package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DevOs 用户中断请求 — 对应 OS 中断：用户通过 Slack 主动取消正在运行的 Action。
 *
 * slackThreadId：必填，用于 B-007 ownership guard。
 *   只有与目标 Action 属于同一 slackThreadId 的请求才允许执行中断。
 *
 * <pre>
 * POST /devos/interrupt
 * {
 *   "actionId": 123,
 *   "slackThreadId": "C08XXXXXX/1234567890.123456",
 *   "reason": "User asked to stop this task"
 * }
 * </pre>
 */
public record DevOsInterruptRequest(
        @NotNull(message = "must not be null") Long actionId,
        @NotBlank(message = "must not be blank") String slackThreadId,
        String reason
) {
}
