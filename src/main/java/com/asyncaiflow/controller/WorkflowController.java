package com.asyncaiflow.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.service.WorkflowService;
import com.asyncaiflow.web.ApiResponse;
import com.asyncaiflow.web.dto.CreateWorkflowRequest;
import com.asyncaiflow.web.dto.WorkflowActionSummaryResponse;
import com.asyncaiflow.web.dto.WorkflowExecutionResponse;
import com.asyncaiflow.web.dto.WorkflowResponse;
import com.asyncaiflow.web.dto.WorkflowSummaryResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/workflow")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/create")
    public ApiResponse<WorkflowResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        return ApiResponse.ok("workflow created", workflowService.createWorkflow(request));
    }

    @GetMapping("/{workflowId}")
    public ApiResponse<WorkflowExecutionResponse> getWorkflow(@PathVariable Long workflowId) {
        return ApiResponse.ok("workflow execution state", workflowService.getWorkflowExecution(workflowId));
    }

    @GetMapping("/{workflowId}/actions")
    public ApiResponse<List<WorkflowActionSummaryResponse>> getWorkflowActions(@PathVariable Long workflowId) {
        return ApiResponse.ok("workflow action execution state", workflowService.getWorkflowActions(workflowId));
    }

    @GetMapping("/{workflowId}/summary")
    public ApiResponse<WorkflowSummaryResponse> getWorkflowSummary(@PathVariable Long workflowId) {
        return ApiResponse.ok("workflow summary", workflowService.getWorkflowSummary(workflowId));
    }
}