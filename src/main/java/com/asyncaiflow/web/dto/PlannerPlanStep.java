package com.asyncaiflow.web.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PlannerPlanStep(
        String type,
        Map<String, Object> payload,
        @JsonProperty("depends_on") List<Integer> dependsOn
) {

    public PlannerPlanStep {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
