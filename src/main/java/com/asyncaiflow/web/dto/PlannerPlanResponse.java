package com.asyncaiflow.web.dto;

import java.util.List;

public record PlannerPlanResponse(
        List<PlannerPlanStep> plan
) {

    public PlannerPlanResponse {
        plan = plan == null ? List.of() : List.copyOf(plan);
    }
}
