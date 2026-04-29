package com.asyncaiflow.worker.sdk;

import com.asyncaiflow.worker.sdk.model.ActionAssignment;

public interface WorkerActionHandler {

    WorkerExecutionResult execute(ActionAssignment assignment) throws Exception;
}