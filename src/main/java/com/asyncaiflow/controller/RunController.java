package com.asyncaiflow.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.service.PlannerService;
import com.asyncaiflow.web.ApiResponse;
import com.asyncaiflow.web.dto.PlannerExecuteRequest;
import com.asyncaiflow.web.dto.PlannerExecuteResponse;
import com.asyncaiflow.web.dto.RunResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/run")
public class RunController {

    private final PlannerService plannerService;

    public RunController(PlannerService plannerService) {
        this.plannerService = plannerService;
    }

    @PostMapping
    public ApiResponse<RunResponse> run(@RequestBody @Valid PlannerExecuteRequest request) {
        PlannerExecuteResponse result = plannerService.executePlan(request);
        String statusUrl = "/workflow/" + result.workflowId() + "/actions";
        return ApiResponse.ok("workflow started",
                new RunResponse(result.workflowId(), result.actionCount(), statusUrl));
    }
}
