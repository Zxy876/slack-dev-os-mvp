package com.asyncaiflow.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.service.PlannerService;
import com.asyncaiflow.web.ApiResponse;
import com.asyncaiflow.web.dto.PlannerExecuteRequest;
import com.asyncaiflow.web.dto.PlannerExecuteResponse;
import com.asyncaiflow.web.dto.PlannerPlanRequest;
import com.asyncaiflow.web.dto.PlannerPlanResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/planner")
public class PlannerController {

    private final PlannerService plannerService;

    public PlannerController(PlannerService plannerService) {
        this.plannerService = plannerService;
    }

    @PostMapping("/plan")
    public PlannerPlanResponse plan(@Valid @RequestBody PlannerPlanRequest request) {
        return plannerService.previewPlan(request);
    }

    @PostMapping("/execute")
    public ApiResponse<PlannerExecuteResponse> execute(@Valid @RequestBody PlannerExecuteRequest request) {
        return ApiResponse.ok("workflow created and actions queued", plannerService.executePlan(request));
    }
}
