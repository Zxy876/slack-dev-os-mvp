package com.asyncaiflow.controller;

import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.service.ActionService;
import com.asyncaiflow.web.ApiResponse;
import com.asyncaiflow.web.dto.ActionAssignmentResponse;
import com.asyncaiflow.web.dto.ActionExecutionResponse;
import com.asyncaiflow.web.dto.ActionResponse;
import com.asyncaiflow.web.dto.CreateActionRequest;
import com.asyncaiflow.web.dto.RenewActionLeaseRequest;
import com.asyncaiflow.web.dto.SubmitActionResultRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/action")
public class ActionController {

    private final ActionService actionService;

    public ActionController(ActionService actionService) {
        this.actionService = actionService;
    }

    @PostMapping("/create")
    public ApiResponse<ActionResponse> createAction(@Valid @RequestBody CreateActionRequest request) {
        return ApiResponse.ok("action created", actionService.createAction(request));
    }

    @GetMapping("/{actionId}")
    public ApiResponse<ActionExecutionResponse> getAction(@PathVariable Long actionId) {
        return ApiResponse.ok("action execution state", actionService.getActionExecution(actionId));
    }

    @GetMapping("/poll")
    public ResponseEntity<?> pollAction(@RequestParam String workerId) {
        Optional<ActionAssignmentResponse> assignment = actionService.pollAction(workerId);
        return assignment
                .map(value -> ResponseEntity.ok(ApiResponse.ok("action assigned", value)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/result")
    public ApiResponse<ActionResponse> submitResult(@Valid @RequestBody SubmitActionResultRequest request) {
        return ApiResponse.ok("action result accepted", actionService.submitResult(request));
    }

    @PostMapping("/{actionId}/renew-lease")
    public ApiResponse<ActionResponse> renewLease(
            @PathVariable Long actionId,
            @Valid @RequestBody RenewActionLeaseRequest request) {
        return ApiResponse.ok("action lease renewed", actionService.renewLease(actionId, request.workerId()));
    }
}