package com.asyncaiflow.support;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.asyncaiflow.domain.entity.ActionEntity;
import com.asyncaiflow.domain.enums.ActionStatus;

public final class RuntimeStatusView {

    private RuntimeStatusView() {
    }

    public static String actionStatus(String rawStatus) {
        ActionStatus status = parseActionStatus(rawStatus);
        return switch (status) {
            case BLOCKED, QUEUED, RETRY_WAIT -> "PENDING";
            case RUNNING -> "RUNNING";
            case SUCCEEDED -> "COMPLETED";
            case FAILED, DEAD_LETTER -> "FAILED";
        };
    }

    public static String workflowStatus(List<ActionEntity> actions) {
        if (actions.stream().anyMatch(action -> "FAILED".equals(actionStatus(action.getStatus())))) {
            return "FAILED";
        }
        if (!actions.isEmpty() && actions.stream().allMatch(action -> "COMPLETED".equals(actionStatus(action.getStatus())))) {
            return "COMPLETED";
        }
        return "RUNNING";
    }

    private static ActionStatus parseActionStatus(String rawStatus) {
        try {
            return ActionStatus.valueOf(rawStatus);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unknown action status: " + rawStatus);
        }
    }
}