package com.asyncaiflow.web.dto;

/**
 * B-019 Test Command Runner — 测试命令执行结果。
 *
 * status：       "PASSED"（exitCode==0）或 "FAILED"（exitCode!=0）
 * exitCode：     进程退出码
 * durationMs：   命令实际执行时长（毫秒）
 * stdoutExcerpt：stdout 前 8000 字符
 * stderrExcerpt：stderr 前 8000 字符
 * command：      实际执行的命令字符串（来自 allowlist key）
 * repoPath：     工作目录
 *
 * 注意：status=FAILED 是业务状态（测试失败），API 层仍返回 HTTP 200 success=true。
 * 仅当请求参数不合法（command 不在 allowlist、repoPath 不存在等）时才返回 4xx。
 */
public record DevOsRunTestResponse(
        String status,
        int exitCode,
        long durationMs,
        String stdoutExcerpt,
        String stderrExcerpt,
        String command,
        String repoPath
) {
}
