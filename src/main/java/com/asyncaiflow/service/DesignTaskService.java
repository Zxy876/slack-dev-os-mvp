package com.asyncaiflow.service;

import com.asyncaiflow.web.dto.design.CreateDesignRequest;
import com.asyncaiflow.web.dto.design.CreateDesignTaskResponse;
import com.asyncaiflow.web.dto.design.TaskResultResponse;
import com.asyncaiflow.web.dto.design.TaskStatusResponse;

public interface DesignTaskService {

    CreateDesignTaskResponse createDesignTask(CreateDesignRequest request);

    TaskStatusResponse getTaskStatus(String taskId);

    TaskResultResponse getTaskResult(String taskId);
}