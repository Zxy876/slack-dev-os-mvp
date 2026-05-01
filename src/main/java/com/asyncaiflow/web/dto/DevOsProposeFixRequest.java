package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * B-020 propose-fix 请求 DTO。
 *
 * 语义：将测试失败的证据（exit code、stdout/stderr 摘要）连同目标文件路径提交给后端，
 * 后端创建一个 mode=fix_preview 的 Action 并入队。Worker 会读取文件、结合失败上下文生成
 * 修复建议（[FIX_PLAN_ONLY] 或 [FIX_PATCH_PREVIEW]），不自动修改文件。
 *
 * 安全不变量：
 *  - 不自动写文件 / 不自动 apply / 不自动 commit / 不自动 push
 *  - failure context 会在 service 层截断（stdout/stderr 各 ≤8000 chars，hint ≤2000 chars）
 *  - repoPath/filePath 安全性由 worker.py safe_read_repo_file 校验
 */
public record DevOsProposeFixRequest(
        @NotBlank(message = "slackThreadId must not be blank") String slackThreadId,
        @NotBlank(message = "repoPath must not be blank")     String repoPath,
        @NotBlank(message = "filePath must not be blank")     String filePath,
        /** "FAILED" | "PASSED" — 通常为 "FAILED" */
        String testStatus,
        /** 测试命令的退出码，null 时 worker 使用 -1 */
        Integer exitCode,
        /** stdout 摘要（截断至 8000 chars）*/
        String stdoutExcerpt,
        /** stderr 摘要（截断至 8000 chars）*/
        String stderrExcerpt,
        /** 人工提示（可选，截断至 2000 chars）*/
        String hint
) {
}
