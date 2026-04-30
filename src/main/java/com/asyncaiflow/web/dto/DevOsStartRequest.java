package com.asyncaiflow.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DevOs interrupt入口请求 — 对应 OS 中断：用户通过 Slack 发起的系统调用。
 *
 * prevActionId（可选）：上一个 Action 的 ID，用于 Context Restore。
 *   若提供，DevOsService 会读取该 Action 的 notepad_ref 并写入新 PCB，
 *   实现指令周期间的上下文传递。
 *
 * repoPath（可选）：本地 Git 仓库绝对路径，用于 B-005 Page Fault 文件检索。
 * filePath（可选）：相对于 repoPath 的文件路径（不得含 ".." 或绝对路径头）。
 *   若同时提供 repoPath 和 filePath，worker 会将文件内容作为 page-in context 注入。
 *
 * writeIntent（可选）：true 表示此 Action 将写入 workspace/repo，需持有 workspace 互斥锁。
 * workspaceKey（可选）：互斥锁的 key（通常为 "repoPath" 或 "workspace:name"）。
 *   仅在 writeIntent=true 时生效；同一 workspaceKey 同时只允许一个 RUNNING writer。
 */
public record DevOsStartRequest(
        @NotBlank(message = "must not be blank") String text,
        @NotBlank(message = "must not be blank") String slackThreadId,
        Long prevActionId,
        String repoPath,
        String filePath,
        Boolean writeIntent,
        String workspaceKey
) {
}
