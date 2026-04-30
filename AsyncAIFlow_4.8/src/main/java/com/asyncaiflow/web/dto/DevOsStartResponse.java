package com.asyncaiflow.web.dto;

/**
 * DevOs 启动响应 — 返回创建的 PCB(Action) 信息，供调用方轮询状态。
 */
public record DevOsStartResponse(
        Long actionId,
        Long workflowId,
        String status,
        String slackThreadId
) {
}
