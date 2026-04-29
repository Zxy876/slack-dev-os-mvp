package com.asyncaiflow.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.service.WorkflowService;
import com.asyncaiflow.web.ApiResponse;
import com.asyncaiflow.web.dto.WorkflowListItemResponse;

@RestController
@RequestMapping("/workflows")
public class WorkflowListController {

    private final WorkflowService workflowService;

    public WorkflowListController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping
    public ApiResponse<List<WorkflowListItemResponse>> listWorkflows(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok("recent workflows", workflowService.getRecentWorkflows(limit));
    }
}