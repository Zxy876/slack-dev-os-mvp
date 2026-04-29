package com.asyncaiflow.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.service.DesignTaskService;
import com.asyncaiflow.web.Result;
import com.asyncaiflow.web.dto.design.CreateDesignRequest;
import com.asyncaiflow.web.dto.design.CreateDesignTaskResponse;
import com.asyncaiflow.web.dto.design.TaskResultResponse;
import com.asyncaiflow.web.dto.design.TaskStatusResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping({"/api/design/tasks", "/api/v1/design/tasks"})
@Tag(name = "Design Tasks", description = "Minimal design task APIs for the designer-facing experience")
public class DesignTaskController {

    private final DesignTaskService designTaskService;

    public DesignTaskController(DesignTaskService designTaskService) {
        this.designTaskService = designTaskService;
    }

    @PostMapping
    @Operation(summary = "Create design task", description = "Creates an asynchronous design task and returns its initial status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload",
                    content = @Content(schema = @Schema(implementation = Result.class)))
    })
    public Result<CreateDesignTaskResponse> createDesignTask(@Valid @RequestBody CreateDesignRequest request) {
        return Result.ok("task created", designTaskService.createDesignTask(request));
    }

    @GetMapping("/{taskId}/status")
    @Operation(summary = "Get task status", description = "Returns the visible status of a design task for UI polling")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status returned successfully"),
            @ApiResponse(responseCode = "404", description = "Task not found",
                    content = @Content(schema = @Schema(implementation = Result.class)))
    })
    public Result<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        return Result.ok(designTaskService.getTaskStatus(taskId));
    }

    @GetMapping("/{taskId}/result")
    @Operation(summary = "Get task result", description = "Returns the final result for a completed design task")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task result returned successfully"),
            @ApiResponse(responseCode = "404", description = "Task not found",
                    content = @Content(schema = @Schema(implementation = Result.class))),
            @ApiResponse(responseCode = "409", description = "Task is not ready yet",
                    content = @Content(schema = @Schema(implementation = Result.class)))
    })
    public Result<TaskResultResponse> getTaskResult(@PathVariable String taskId) {
        return Result.ok(designTaskService.getTaskResult(taskId));
    }
}