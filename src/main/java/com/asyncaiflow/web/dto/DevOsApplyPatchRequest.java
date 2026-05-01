package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * B-018 Human Confirm Apply Patch — 申请将 patch preview 写入真实文件。
 *
 * previewActionId：必填，对应已 SUCCEEDED 的 patch_preview action ID。
 * slackThreadId：必填，ownership guard（B-007）— 必须与 preview action 同 thread。
 * confirm：必须为 true；false 时无条件拒绝（防误触）。
 *
 * <pre>
 * POST /devos/apply-patch
 * {
 *   "previewActionId": 123,
 *   "slackThreadId": "C1234567890/1234567890.123456",
 *   "confirm": true
 * }
 * </pre>
 */
public record DevOsApplyPatchRequest(
        @NotNull(message = "must not be null") Long previewActionId,
        @NotBlank(message = "must not be blank") String slackThreadId,
        @NotNull(message = "must not be null") Boolean confirm
) {
}
