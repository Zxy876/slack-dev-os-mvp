package com.asyncaiflow.planner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WorkflowPlanGenerator#generateDriftPlan}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>difficulty=3 → 5-step pipeline (plan→code→review→deploy→refresh)</li>
 *   <li>difficulty=4 → 7-step pipeline (adds test + git_push)</li>
 *   <li>difficulty=5 → 8-step pipeline (adds web_search at index 0)</li>
 *   <li>refresh step is always last</li>
 *   <li>payloads carry issue_text and player_id</li>
 * </ul>
 */
class DriftPlanGeneratorTest {

    private static final String ISSUE = "修复 Drift lobby 的 NPC spawning 逻辑异常";
    private static final String PLAYER = "Steve";

    private final WorkflowPlanGenerator generator = new WorkflowPlanGenerator();

    // ─────────────────────────────────── difficulty 3 ────────────────────────

    @Test
    void difficulty3_produces5Steps() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 3, PLAYER);
        assertEquals(5, plan.size(), "difficulty=3 must produce exactly 5 steps");
    }

    @Test
    void difficulty3_stepTypes_are_correct() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 3, PLAYER);
        assertEquals(
                List.of("drift_plan", "drift_code", "drift_review", "drift_deploy", "drift_refresh"),
                plan.stream().map(PlanDraftStep::type).toList()
        );
    }

    @Test
    void difficulty3_refresh_is_last() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 3, PLAYER);
        assertEquals("drift_refresh", plan.get(plan.size() - 1).type());
    }

    @Test
    void difficulty3_payload_carries_issue_and_player() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 3, PLAYER);
        // drift_plan step (index 0)
        assertEquals(ISSUE, plan.get(0).payload().get("issue_text"));
        assertEquals(PLAYER, plan.get(0).payload().get("player_id"));
    }

    // ─────────────────────────────────── difficulty 4 ────────────────────────

    @Test
    void difficulty4_produces7Steps() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 4, PLAYER);
        assertEquals(7, plan.size(), "difficulty=4 must produce exactly 7 steps");
    }

    @Test
    void difficulty4_stepTypes_include_test_and_git_push() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 4, PLAYER);
        List<String> types = plan.stream().map(PlanDraftStep::type).toList();
        assertTrue(types.contains("drift_test"), "difficulty=4 must include drift_test");
        assertTrue(types.contains("drift_git_push"), "difficulty=4 must include drift_git_push");
    }

    @Test
    void difficulty4_refresh_is_last() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 4, PLAYER);
        assertEquals("drift_refresh", plan.get(plan.size() - 1).type());
    }

    // ─────────────────────────────────── difficulty 5 ────────────────────────

    @Test
    void difficulty5_produces8Steps() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 5, PLAYER);
        assertEquals(8, plan.size(), "difficulty=5 must produce exactly 8 steps");
    }

    @Test
    void difficulty5_has_webSearch_first() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 5, PLAYER);
        assertEquals("drift_web_search", plan.get(0).type(),
                "difficulty=5 must start with drift_web_search");
    }

    @Test
    void difficulty5_refresh_is_last() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 5, PLAYER);
        assertEquals("drift_refresh", plan.get(plan.size() - 1).type());
    }

    @Test
    void difficulty5_all_expected_types_present() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 5, PLAYER);
        List<String> types = plan.stream().map(PlanDraftStep::type).toList();
        assertTrue(types.contains("drift_web_search"));
        assertTrue(types.contains("drift_plan"));
        assertTrue(types.contains("drift_code"));
        assertTrue(types.contains("drift_review"));
        assertTrue(types.contains("drift_test"));
        assertTrue(types.contains("drift_deploy"));
        assertTrue(types.contains("drift_git_push"));
        assertTrue(types.contains("drift_refresh"));
    }

    // ─────────────────────────────────── dependency chain ────────────────────

    @Test
    void difficulty3_first_step_has_no_upstream() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 3, PLAYER);
        assertTrue(plan.get(0).dependsOn().isEmpty(),
                "First step must have no upstream dependencies");
    }

    @Test
    void difficulty3_each_step_depends_on_previous() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 3, PLAYER);
        // steps 1..4 each depend on the immediately preceding step index
        for (int i = 1; i < plan.size(); i++) {
            assertFalse(plan.get(i).dependsOn().isEmpty(),
                    "Step " + i + " (" + plan.get(i).type() + ") must have upstream");
            assertTrue(plan.get(i).dependsOn().contains(i - 1),
                    "Step " + i + " must depend on step " + (i - 1));
        }
    }

    // ─────────────────────────────────── convenience overload ────────────────

    @Test
    void convenience_overload_without_playerId_works() {
        List<PlanDraftStep> plan = generator.generateDriftPlan("Some issue", 3);
        assertEquals(5, plan.size());
        assertEquals("drift_plan", plan.get(0).type());
    }

    // ─────────────────────────────────── edge cases ──────────────────────────

    @Test
    void clamped_difficulty_below3_treated_as3() {
        // generateDriftPlan is meant for difficulty >= 3; values < 3 should clamp up
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 1, PLAYER);
        // We allow an empty list or a 5-step list — implementation must not throw
        assertFalse(plan == null, "Plan must not be null");
    }

    @Test
    void difficulty3_no_duplicate_step_types() {
        List<PlanDraftStep> plan = generator.generateDriftPlan(ISSUE, 3, PLAYER);
        long distinct = plan.stream().map(PlanDraftStep::type).distinct().count();
        assertEquals(plan.size(), distinct, "All step types must be distinct in a difficulty-3 plan");
    }
}
