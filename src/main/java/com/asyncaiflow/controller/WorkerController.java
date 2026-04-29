package com.asyncaiflow.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.service.WorkerService;
import com.asyncaiflow.web.ApiResponse;
import com.asyncaiflow.web.dto.RegisterWorkerRequest;
import com.asyncaiflow.web.dto.WorkerHeartbeatRequest;
import com.asyncaiflow.web.dto.WorkerResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/worker")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @PostMapping("/register")
    public ApiResponse<WorkerResponse> register(@Valid @RequestBody RegisterWorkerRequest request) {
        return ApiResponse.ok("worker registered", workerService.register(request));
    }

    @PostMapping("/heartbeat")
    public ApiResponse<WorkerResponse> heartbeat(@Valid @RequestBody WorkerHeartbeatRequest request) {
        return ApiResponse.ok("heartbeat accepted", workerService.heartbeat(request.workerId()));
    }
}