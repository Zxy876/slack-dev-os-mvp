package com.asyncaiflow.web.dto;

import java.util.List;

/**
 * B-021 Human Git Commit Snapshot — git commit 执行结果。
 *
 * status：
 *   "COMMITTED"   — commit 成功创建
 *   "NO_CHANGES"  — working tree 无改动（git status --porcelain 为空）
 *   "REJECTED"    — confirm=false（服务层已通过 400 拦截，此值仅内部备用）
 *
 * commitHash：  新建 commit 的 SHA-1（COMMITTED 时非空；NO_CHANGES 时为 null）
 * changedFiles：本次 commit 涉及的文件列表（来自 git status --porcelain）
 * message：     提交信息（来自请求）
 * diffExcerpt： git diff --stat 的截断输出（最多 2000 字符）
 * repoPath：    实际执行 commit 的目录（canonical 路径）
 */
public record DevOsGitCommitResponse(
        String status,
        String commitHash,
        List<String> changedFiles,
        String message,
        String diffExcerpt,
        String repoPath
) {
}
