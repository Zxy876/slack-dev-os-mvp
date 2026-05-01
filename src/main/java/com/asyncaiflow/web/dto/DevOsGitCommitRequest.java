package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * B-021 Human Git Commit Snapshot — 请求在指定 repo 中创建一个本地 git commit。
 *
 * 安全边界：
 *  - confirm 必须为 true；false 时立即返回 400
 *  - message 必填，长度 1–200 字符
 *  - repoPath 必须是一个存在的目录，且是 Git 仓库
 *  - 只执行本地 commit，不 push，不修改 remote，不写全局 git config
 *
 * <pre>
 * POST /devos/git-commit
 * {
 *   "repoPath":      "/Users/dev/my-repo",
 *   "slackThreadId": "C1234567890/1234567890.123456",
 *   "message":       "devos: apply README title fix",
 *   "confirm":       true
 * }
 * </pre>
 */
public record DevOsGitCommitRequest(
        @NotBlank(message = "repoPath must not be blank")  String repoPath,
        @NotBlank(message = "slackThreadId must not be blank") String slackThreadId,
        @NotBlank(message = "message must not be blank")
        @Size(max = 200, message = "commit message must be 200 chars or fewer")
        String message,
        @NotNull(message = "confirm must be provided") Boolean confirm
) {
}
