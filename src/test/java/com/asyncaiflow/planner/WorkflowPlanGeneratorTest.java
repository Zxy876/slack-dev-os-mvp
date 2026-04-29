package com.asyncaiflow.planner;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WorkflowPlanGeneratorTest {

    private final WorkflowPlanGenerator generator = new WorkflowPlanGenerator();

    @Test
    void explainIssueGeneratesExplainPipeline() {
        List<PlanDraftStep> plan = generator.generatePlan(
                "Explain authentication module",
                "auth package",
                "src/main/java/com/example/auth/AuthService.java");

        assertEquals(List.of("search_semantic", "build_context_pack", "generate_explanation"),
                plan.stream().map(PlanDraftStep::type).toList());
        assertEquals(List.of(), plan.get(0).dependsOn());
        assertEquals(List.of(0), plan.get(1).dependsOn());
        assertEquals(List.of(1), plan.get(2).dependsOn());
        assertTrue(plan.get(0).payload().containsKey("scope"));
        assertEquals("$upstream[0].result.matches", injectionValue(plan.get(1).payload(), "retrievalResults"));
        assertEquals("$upstream[0].result", injectionValue(plan.get(2).payload(), "gathered_context"));
    }

    @Test
    void bugIssueGeneratesDiagnosisPipeline() {
        List<PlanDraftStep> plan = generator.generatePlan("Fix login bug", "web login flow", "");

        assertEquals(List.of(
                        "search_semantic",
                        "load_code",
                        "build_context_pack",
                        "design_solution",
                        "generate_patch",
                        "review_patch",
                        "apply_patch",
                        "commit_changes"),
                plan.stream().map(PlanDraftStep::type).toList());
        assertEquals(List.of(), plan.get(0).dependsOn());
        assertEquals(List.of(0), plan.get(1).dependsOn());
        assertEquals(List.of(0), plan.get(2).dependsOn());
        assertEquals(List.of(2), plan.get(3).dependsOn());
        assertEquals(List.of(1, 2, 3), plan.get(4).dependsOn());
        assertEquals(List.of(1, 2, 3, 4), plan.get(5).dependsOn());
        assertEquals(List.of(0, 1, 5), plan.get(6).dependsOn());
        assertEquals(List.of(6), plan.get(7).dependsOn());
        assertEquals("$upstream[0].result.matches[0].path", nestedInjectionFrom(plan.get(1).payload(), "path"));
        assertEquals("$upstreamByType.build_context_pack.result.summary", nestedInjectionFrom(plan.get(3).payload(), "context"));
        assertEquals("$upstreamByType.design_solution.result.summary", nestedInjectionFrom(plan.get(4).payload(), "designSummary"));
        assertEquals("$upstreamByType.review_patch.result.patch", nestedInjectionFrom(plan.get(6).payload(), "patch"));
        assertEquals("$upstreamByType.apply_patch.result.repoPath", nestedInjectionFrom(plan.get(7).payload(), "repoPath"));
    }

    @Test
    void driftMappingBugIssueGeneratesDiagnosisPipeline() {
        List<PlanDraftStep> plan = generator.generatePlan(
                "Find bug in resource mapping",
                "DriftSystem backend app core mapping subsystem",
                "/Users/zxydediannao/DriftSystem/backend/app/core/mapping/v2_mapper.py");

        assertEquals(List.of(
                        "search_semantic",
                        "load_code",
                        "build_context_pack",
                        "design_solution",
                        "generate_patch",
                        "review_patch",
                        "apply_patch",
                        "commit_changes"),
                plan.stream().map(PlanDraftStep::type).toList());
        assertTrue(plan.get(0).payload().containsKey("scope"));
            assertEquals(List.of(0, 1, 5), plan.get(6).dependsOn());
    }

    @Test
    void flowTraceIssueGeneratesExplainPipeline() {
        List<PlanDraftStep> plan = generator.generatePlan(
                "Trace the full flow of rule-event from Minecraft plugin to backend",
                "DriftSystem system/mc_plugin scene flow and backend routers",
                "");

        assertEquals(List.of("search_semantic", "build_context_pack", "generate_explanation"),
                plan.stream().map(PlanDraftStep::type).toList());
        assertEquals(List.of(1), plan.get(2).dependsOn());
    }

    @Test
    void whyMightFailIssueGeneratesDiagnosisPipeline() {
        List<PlanDraftStep> plan = generator.generatePlan(
                "Why might resource mapping fail in v2_mapper.py",
                "DriftSystem backend app core mapping subsystem",
                "/Users/zxydediannao/DriftSystem/backend/app/core/mapping/v2_mapper.py");

        assertEquals(List.of(
                        "search_semantic",
                        "load_code",
                        "build_context_pack",
                        "design_solution",
                        "generate_patch",
                        "review_patch",
                        "apply_patch",
                        "commit_changes"),
                plan.stream().map(PlanDraftStep::type).toList());
            assertEquals(List.of(0, 1, 5), plan.get(6).dependsOn());
            assertEquals(List.of(6), plan.get(7).dependsOn());
    }

    @Test
    void blankIssueReturnsEmptyPlan() {
        List<PlanDraftStep> plan = generator.generatePlan("   ", "", "");

        assertTrue(plan.isEmpty());
    }

    @Test
    void trimsInteractiveIssuePrefixBeforePlanning() {
        List<PlanDraftStep> plan = generator.generatePlan(
                "Describe your issue: 在 runtime 中定位 TODO",
                "",
                "");

        assertEquals("在 runtime 中定位 TODO", plan.get(0).payload().get("query"));
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
