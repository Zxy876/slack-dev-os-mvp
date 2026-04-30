package com.asyncaiflow.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.service.DevOsService;
import com.asyncaiflow.web.ApiResponse;
import com.asyncaiflow.web.dto.DevOsInterruptRequest;
import com.asyncaiflow.web.dto.DevOsInterruptResponse;
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
     */
    @PostMapping("/interrupt")
    public ApiResponse<DevOsInterruptResponse> interrupt(@Valid @RequestBody DevOsInterruptRequest request) {
        DevOsInterruptResponse response = devOsService.interrupt(request);
        return ApiResponse.ok("action interrupted", response);
    }
}
