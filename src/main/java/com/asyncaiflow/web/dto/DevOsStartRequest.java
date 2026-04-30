package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DevOs interrupt入口请求 — 对应 OS 中断：用户通过 Slack 发起的系统调用。
 *
 * prevActionId（可选）：上一个 Action 的 ID，用于 Context Restore。
 *   若提供，DevOsService 会读取该 Action 的 notepad_ref 并写入新 PCB，
 *   实现指令周期间的上下文传递。
 */
public record DevOsStartRequest(
        @NotBlank(message = "must not be blank") String text,
        @NotBlank(message = "must not be blank") String slackThreadId,
        Long prevActionId
) {
}
