package com.asyncaiflow.web.dto;

/**
 * B-018 Human Confirm Apply Patch — apply patch 执行结果。
 *
 * status：  "APPLIED" / "REJECTED"
 * applied：  true 表示已写入真实文件
 * message：  成功或拒绝原因
 */
public record DevOsApplyPatchResponse(
        Long previewActionId,
        String status,
        String filePath,
        boolean applied,
        String message
) {
}
