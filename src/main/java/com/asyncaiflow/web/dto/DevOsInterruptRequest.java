package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * DevOs 用户中断请求 — 对应 OS 中断：用户通过 Slack 主动取消正在运行的 Action。
 *
 * <pre>
 * POST /devos/interrupt
 * {
 *   "actionId": 123,
 *   "reason": "User asked to stop this task"
 * }
 * </pre>
 */
public record DevOsInterruptRequest(
        @NotNull(message = "must not be null") Long actionId,
        String reason
) {
}
