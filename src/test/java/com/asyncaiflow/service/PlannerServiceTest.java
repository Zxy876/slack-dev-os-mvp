package com.asyncaiflow.service;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import com.asyncaiflow.web.dto.PlannerPlanRequest;
import com.asyncaiflow.web.dto.PlannerPlanResponse;

class PlannerServiceTest {

    @Test
    void previewPlanReturnsExpectedExplainPlan() {
        PlannerService plannerService = new PlannerService();

        PlannerPlanResponse response = plannerService.previewPlan(
                new PlannerPlanRequest(
                        "Explain authentication module",
                        "auth package",
                        "src/main/java/com/example/auth/AuthService.java"
                ));

        assertEquals(3, response.plan().size());
        assertEquals("search_semantic", response.plan().get(0).type());
        assertEquals("build_context_pack", response.plan().get(1).type());
        assertEquals("generate_explanation", response.plan().get(2).type());
        assertEquals(List.of(0), response.plan().get(1).dependsOn());
        assertEquals("$upstream[0].result.matches", injectionValue(response.plan().get(1).payload(), "retrievalResults"));
        assertEquals("$upstream[0].result", injectionValue(response.plan().get(2).payload(), "gathered_context"));
    }

    @Test
    void previewPlanReturnsDiagnosisPlanForBugIssue() {
        PlannerService plannerService = new PlannerService();

        PlannerPlanResponse response = plannerService.previewPlan(
                new PlannerPlanRequest("Fix login bug", "", ""));

        assertEquals(List.of(
                "search_semantic",
                "load_code",
                "build_context_pack",
                "design_solution",
                "generate_patch",
                "review_patch",
                "apply_patch",
                "commit_changes"),
                response.plan().stream().map(step -> step.type()).toList());
        assertEquals(List.of(2), response.plan().get(3).dependsOn());
        assertEquals(List.of(0, 1, 5), response.plan().get(6).dependsOn());
        assertEquals(List.of(6), response.plan().get(7).dependsOn());
        assertEquals("$upstreamByType.build_context_pack.result.summary", nestedInjectionFrom(response.plan().get(3).payload(), "context"));
        assertEquals("$upstreamByType.review_patch.result.patch", nestedInjectionFrom(response.plan().get(6).payload(), "patch"));
        assertEquals("$upstreamByType.apply_patch.result.repoPath", nestedInjectionFrom(response.plan().get(7).payload(), "repoPath"));
    }

    @Test
    void previewPlanReturnsDiagnosisPlanForDriftMappingIssue() {
        PlannerService plannerService = new PlannerService();

        PlannerPlanResponse response = plannerService.previewPlan(
                new PlannerPlanRequest(
                        "Find bug in resource mapping",
                        "DriftSystem backend app core mapping subsystem",
                        "/Users/zxydediannao/DriftSystem/backend/app/core/mapping/v2_mapper.py"));

        assertEquals(List.of(
                "search_semantic",
                "load_code",
                "build_context_pack",
                "design_solution",
                "generate_patch",
                "review_patch",
                "apply_patch",
                "commit_changes"),
                response.plan().stream().map(step -> step.type()).toList());
        assertEquals(List.of(0, 1, 5), response.plan().get(6).dependsOn());
        assertEquals(List.of(6), response.plan().get(7).dependsOn());
    }

    @Test
    void previewPlanReturnsExplainPlanForFlowTraceIssue() {
        PlannerService plannerService = new PlannerService();

        PlannerPlanResponse response = plannerService.previewPlan(
                new PlannerPlanRequest(
                        "Trace the full flow of rule-event from Minecraft plugin to backend",
                        "DriftSystem system/mc_plugin scene flow and backend routers",
                        ""));

        assertEquals(List.of("search_semantic", "build_context_pack", "generate_explanation"),
                response.plan().stream().map(step -> step.type()).toList());
        assertEquals(List.of(1), response.plan().get(2).dependsOn());
    }

    @Test
    void previewPlanReturnsDiagnosisPlanForWhyMightFailIssue() {
        PlannerService plannerService = new PlannerService();

        PlannerPlanResponse response = plannerService.previewPlan(
                new PlannerPlanRequest(
                        "Why might resource mapping fail in v2_mapper.py",
                        "DriftSystem backend app core mapping subsystem",
                        "/Users/zxydediannao/DriftSystem/backend/app/core/mapping/v2_mapper.py"));

        assertEquals(List.of(
                "search_semantic",
                "load_code",
                "build_context_pack",
                "design_solution",
                "generate_patch",
                "review_patch",
                "apply_patch",
                "commit_changes"),
                response.plan().stream().map(step -> step.type()).toList());
        assertEquals(List.of(0, 1, 5), response.plan().get(6).dependsOn());
        assertEquals(List.of(6), response.plan().get(7).dependsOn());
    }

        private String injectionValue(Map<String, Object> payload, String key) {
                Object injectObject = payload.get("inject");
                if (!(injectObject instanceof Map<?, ?> injectMap)) {
                        return "";
                }
                Object value = injectMap.get(key);
                return value == null ? "" : String.valueOf(value);
        }

        private String nestedInjectionFrom(Map<String, Object> payload, String key) {
                Object injectObject = payload.get("inject");
                if (!(injectObject instanceof Map<?, ?> injectMap)) {
                        return "";
                }

                Object nested = injectMap.get(key);
                if (!(nested instanceof Map<?, ?> nestedMap)) {
                        return "";
                }

                Object from = nestedMap.get("from");
                return from == null ? "" : String.valueOf(from);
        }
}
