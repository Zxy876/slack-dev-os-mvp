package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * B-019 Test Command Runner — 请求在指定 repo 中执行受限测试命令。
 *
 * repoPath：     必填，测试命令的工作目录。
 * slackThreadId：必填，用于审计和 ownership 关联。
 * command：      必填，必须在服务端 allowlist 中；不支持任意 shell。
 * timeoutSeconds：可选，1–180 秒（超限自动 clamp）；默认 120。
 *
 * <pre>
 * POST /devos/run-test
 * {
 *   "repoPath":      "/Users/dev/my-repo",
 *   "slackThreadId": "C1234567890/1234567890.123456",
 *   "command":       "mvn test -Dspring.profiles.active=local",
 *   "timeoutSeconds": 120
 * }
 * </pre>
 */
public record DevOsRunTestRequest(
        @NotBlank(message = "repoPath must not be blank") String repoPath,
        @NotBlank(message = "slackThreadId must not be blank") String slackThreadId,
        @NotBlank(message = "command must not be blank") String command,
        Integer timeoutSeconds
) {
}
