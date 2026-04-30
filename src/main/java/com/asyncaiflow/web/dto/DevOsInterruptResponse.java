package com.asyncaiflow.web.dto;

/**
 * DevOs 用户中断响应 — 返回被中断 Action 的最终状态。
 */
public record DevOsInterruptResponse(
        Long actionId,
        String status,
        boolean interrupted
) {
}
