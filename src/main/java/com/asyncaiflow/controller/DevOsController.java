package com.asyncaiflow.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.service.DevOsService;
import com.asyncaiflow.web.ApiResponse;
import com.asyncaiflow.web.dto.DevOsApplyPatchRequest;
import com.asyncaiflow.web.dto.DevOsApplyPatchResponse;
import com.asyncaiflow.web.dto.DevOsGitCommitRequest;
import com.asyncaiflow.web.dto.DevOsGitCommitResponse;
import com.asyncaiflow.web.dto.DevOsInterruptRequest;
import com.asyncaiflow.web.dto.DevOsInterruptResponse;
import com.asyncaiflow.web.dto.DevOsProposeFixRequest;
import com.asyncaiflow.web.dto.DevOsProposeFixResponse;
import com.asyncaiflow.web.dto.DevOsRunTestRequest;
import com.asyncaiflow.web.dto.DevOsRunTestResponse;
import com.asyncaiflow.web.dto.DevOsStartRequest;
import com.asyncaiflow.web.dto.DevOsStartResponse;

import jakarta.validation.Valid;

/**
 * DevOsController — Slack Dev OS 系统调用入口（Syscall Gateway / Interrupt Layer）
 *
 * POST /devos/start
 *   接收 Slack 用户指令，触发内核创建 PCB 并入队。
 *   等价于用户在 Slack 输入 /devos start "..."
 *
 * OS 类比：
 *   - HTTP 请求  = 硬中断 (Hardware Interrupt)
 *   - 本 Controller = 中断向量表入口 (Interrupt Vector)
 *   - DevOsService  = 中断服务例程 (ISR)
 */
@RestController
@RequestMapping("/devos")
public class DevOsController {

    private final DevOsService devOsService;

    public DevOsController(DevOsService devOsService) {
        this.devOsService = devOsService;
    }

    /**
     * 启动一个新的 DevOs 指令周期。
     *
     * <pre>
     * POST /devos/start
     * {
     *   "text": "write a hello world flask app",
     *   "slackThreadId": "C1234567890/1234567890.123456"
     * }
     * </pre>
     *
     * 响应：
     * <pre>
     * {
     *   "success": true,
     *   "data": {
     *     "actionId": 123456789,
     *     "workflowId": 987654321,
     *     "status": "QUEUED",
     *     "slackThreadId": "C1234567890/1234567890.123456"
     *   }
     * }
     * </pre>
     */
    @PostMapping("/start")
    public ApiResponse<DevOsStartResponse> start(@Valid @RequestBody DevOsStartRequest request) {
        DevOsStartResponse response = devOsService.start(request);
        return ApiResponse.ok("devos session started — action queued", response);
    }

    /**
     * 用户中断指令。
     *
     * <pre>
     * POST /devos/interrupt
     * {
     *   "actionId": 123,
     *   "slackThreadId": "C08XXXXXX/1234567890.123456",
     *   "reason": "User asked to stop this task"
     * }
     * </pre>
     *
     * 响应：
     * <pre>
     * {
     *   "success": true,
     *   "data": {
     *     "actionId": 123,
     *     "status": "FAILED",
     *     "interrupted": true
     *   }
     * }
     * </pre>
     *
     * 如果 Action 已经是终态（SUCCEEDED / FAILED / DEAD_LETTER），返回 409 CONFLICT。
     * 如果 slackThreadId 与目标 Action 的 slackThreadId 不匹配，返回 403 FORBIDDEN（B-007）。
     */
    @PostMapping("/interrupt")
    public ApiResponse<DevOsInterruptResponse> interrupt(@Valid @RequestBody DevOsInterruptRequest request) {
        DevOsInterruptResponse response = devOsService.interrupt(request);
        return ApiResponse.ok("action interrupted", response);
    }

    /**
     * B-018 — Human Confirm Apply Patch.
     *
     * <pre>
     * POST /devos/apply-patch
     * {
     *   "previewActionId": 123,
     *   "slackThreadId": "C1234567890/1234567890.123456",
     *   "confirm": true
     * }
     * </pre>
     *
     * 响应：
     * <pre>
     * {
     *   "success": true,
     *   "data": {
     *     "previewActionId": 123,
     *     "status": "APPLIED",
     *     "filePath": "README.md",
     *     "applied": true,
     *     "message": "Patch applied to README.md; no git commit was made"
     *   }
     * }
     * </pre>
     *
     * 安全不变量：
     *  - confirm 必须为 true
     *  - previewActionId 必须属于同一 slackThreadId（B-007）
     *  - previewAction 必须是 SUCCEEDED 状态
     *  - 文件路径必须安全（相对路径，无 ".."，在 repoPath 内）
     *  - originalSha256 校验：文件若被改动则拒绝
     *  - 不 git commit，不 git push
     */
    @PostMapping("/apply-patch")
    public ApiResponse<DevOsApplyPatchResponse> applyPatch(@Valid @RequestBody DevOsApplyPatchRequest request) {
        DevOsApplyPatchResponse response = devOsService.applyPatch(request);
        return ApiResponse.ok("patch applied", response);
    }

    /**
     * B-019 — Run an allowlisted test command in the specified repo.
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
     *
     * 响应 (HTTP 200 always when request is valid):
     * <pre>
     * {
     *   "success": true,
     *   "data": {
     *     "status":        "PASSED" | "FAILED",
     *     "exitCode":      0,
     *     "durationMs":    12345,
     *     "stdoutExcerpt": "...",
     *     "stderrExcerpt": "...",
     *     "command":       "mvn test -Dspring.profiles.active=local",
     *     "repoPath":      "/Users/dev/my-repo"
     *   }
     * }
     * </pre>
     *
     * 注意：status=FAILED 是测试业务失败，API 层仍返回 success=true。
     * 仅当 command 不在 allowlist、repoPath 不存在等时返回 4xx。
     */
    @PostMapping("/run-test")
    public ApiResponse<DevOsRunTestResponse> runTest(@Valid @RequestBody DevOsRunTestRequest request) {
        DevOsRunTestResponse response = devOsService.runTest(request);
        return ApiResponse.ok("test command executed", response);
    }

    /**
     * B-020 — Propose a fix for a test failure.
     *
     * <pre>
     * POST /devos/propose-fix
     * {
     *   "slackThreadId":  "C08XXXXXX/1234567890.123456",
     *   "repoPath":       "/path/to/local/repo",
     *   "filePath":       "src/main/Example.java",
     *   "testStatus":     "FAILED",
     *   "exitCode":       1,
     *   "stdoutExcerpt":  "...",
     *   "stderrExcerpt":  "...",
     *   "hint":           "optional human guidance"
     * }
     * </pre>
     *
     * 响应（HTTP 200）:
     * <pre>
     * {
     *   "success": true,
     *   "data": {
     *     "actionId":     123,
     *     "workflowId":   456,
     *     "status":       "QUEUED",
     *     "slackThreadId": "C08XXXXXX/1234567890.123456",
     *     "message":      "fix proposal queued — action 123"
     *   }
     * }
     * </pre>
     *
     * 安全不变量：
     *  - 不自动修改任何文件
     *  - 不自动 apply patch
     *  - 不自动 commit / push
     *  - failure context 内容截断后存入 payload
     *  - Worker 返回 [FIX_PLAN_ONLY] 或 [FIX_PATCH_PREVIEW]，交由人类审阅
     */
    @PostMapping("/propose-fix")
    public ApiResponse<DevOsProposeFixResponse> proposeFix(@Valid @RequestBody DevOsProposeFixRequest request) {
        DevOsProposeFixResponse response = devOsService.proposeFix(request);
        return ApiResponse.ok("fix proposal queued", response);
    }

    /**
     * B-021 — Create a local git commit snapshot in the specified repo.
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
     *
     * 响应：
     * <pre>
     * {
     *   "success": true,
     *   "data": {
     *     "status":       "COMMITTED" | "NO_CHANGES",
     *     "commitHash":   "a1b2c3d...",
     *     "changedFiles": ["README.md"],
     *     "message":      "devos: apply README title fix",
     *     "diffExcerpt":  "...",
     *     "repoPath":     "/Users/dev/my-repo"
     *   }
     * }
     * </pre>
     *
     * 安全不变量：
     *  - confirm 必须为 true
     *  - repoPath 必须是 git 仓库
     *  - message 长度 ≤ 200 字符
     *  - 只创建本地 commit，不 push，不修改 remote
     *  - 不写全局 git config
     *  - 无改动时返回 NO_CHANGES，HTTP 200
     */
    @PostMapping("/git-commit")
    public ApiResponse<DevOsGitCommitResponse> gitCommit(@Valid @RequestBody DevOsGitCommitRequest request) {
        DevOsGitCommitResponse response = devOsService.gitCommit(request);
        return ApiResponse.ok("git commit executed", response);
    }
}
