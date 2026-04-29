package com.asyncaiflow.planner;

import java.util.List;
import java.util.Map;

public record PlanDraftStep(
        String type,
        Map<String, Object> payload,
        List<Integer> dependsOn
) {

    public PlanDraftStep {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
