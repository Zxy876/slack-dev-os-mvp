package com.asyncaiflow.web.dto;

import java.util.List;

public record PlannerExecuteResponse(
        Long workflowId,
        int stepCount,
        int actionCount,
        List<Long> actionIds
) {
}
