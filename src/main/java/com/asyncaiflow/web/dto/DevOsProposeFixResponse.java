package com.asyncaiflow.web.dto;

/**
 * B-020 propose-fix 响应 DTO。
 *
 * 返回创建的 Action 信息，调用方可通过 actionId 轮询 Action 状态，
 * 待 SUCCEEDED 后从结果中读取 [FIX_PLAN_ONLY] / [FIX_PATCH_PREVIEW] 内容。
 */
public record DevOsProposeFixResponse(
        Long actionId,
        Long workflowId,
        String status,
        String slackThreadId,
        String message
) {
}
