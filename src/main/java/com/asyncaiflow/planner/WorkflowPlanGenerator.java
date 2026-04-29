package com.asyncaiflow.planner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WorkflowPlanGenerator {

    public List<PlanDraftStep> generatePlan(String issue, String repoContext, String file) {
        String normalizedIssue = normalize(issue);
        if (normalizedIssue.isBlank()) {
            return List.of();
        }

        String normalizedRepoContext = normalize(repoContext);
        String normalizedFile = normalize(file);

        if (isExplainIntent(normalizedIssue) || isFlowTraceIntent(normalizedIssue)) {
            return List.of(
                    searchSemanticStep(normalizedIssue, normalizedFile),
                    buildContextPackStep(normalizedIssue, normalizedRepoContext, normalizedFile, 0),
                    generateExplanationStep(normalizedIssue, normalizedRepoContext, normalizedFile, 1)
            );
        }

        if (isReviewIntent(normalizedIssue)) {
            return List.of(
                    searchSemanticStep(normalizedIssue, normalizedFile),
                loadCodeStep(normalizedFile, 0),
                buildContextPackStep(normalizedIssue, normalizedRepoContext, normalizedFile, 0),
                reviewCodeStep(normalizedIssue, normalizedRepoContext, List.of(1, 2))
            );
        }

        if (isDiagnosisIntent(normalizedIssue)) {
            return repairPipeline(normalizedIssue, normalizedRepoContext, normalizedFile);
        }

        return repairPipeline(normalizedIssue, normalizedRepoContext, normalizedFile);
    }

    private List<PlanDraftStep> repairPipeline(String issue, String repoContext, String file) {
        return List.of(
                searchSemanticStep(issue, file),
                loadCodeStep(file, 0),
                buildContextPackStep(issue, repoContext, file, 0),
                designSolutionStep(issue, repoContext, 2),
                generatePatchStep(issue, repoContext, file, List.of(1, 2, 3)),
                reviewPatchStep(issue, repoContext, file, List.of(1, 2, 3, 4)),
            applyPatchStep(List.of(0, 1, 5)),
                commitChangesStep(issue, List.of(6))
        );
    }

    private PlanDraftStep searchSemanticStep(String issue, String file) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("query", issue);
        payload.put("topK", 5);

        if (!file.isBlank()) {
            payload.put("scope", Map.of("paths", List.of(file)));
        }

        return new PlanDraftStep("search_semantic", payload, List.of());
    }

    private PlanDraftStep buildContextPackStep(String issue, String repoContext, String file, int dependsOnIndex) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("issue", issue);
        payload.put("query", issue);
        payload.put("maxFiles", 3);
        payload.put("maxCharsPerFile", 4000);
        payload.put("inject", Map.of(
                "retrievalResults", "$upstream[0].result.matches"));

        if (!repoContext.isBlank()) {
            payload.put("repo_context", repoContext);
        }
        if (!file.isBlank()) {
            payload.put("file", file);
            payload.put("scope", Map.of("paths", List.of(file)));
        }

        return new PlanDraftStep("build_context_pack", payload, List.of(dependsOnIndex));
    }

    private PlanDraftStep loadCodeStep(String file, int dependsOnIndex) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("maxFiles", 3);
        payload.put("maxCharsPerFile", 4000);
        if (!file.isBlank()) {
            payload.put("path", file);
        }

        Map<String, Object> pathRule = new LinkedHashMap<>();
        pathRule.put("from", "$upstream[0].result.matches[0].path");
        if (!file.isBlank()) {
            pathRule.put("default", file);
        }
        payload.put("inject", Map.of("path", pathRule));

        return new PlanDraftStep("load_code", payload, List.of(dependsOnIndex));
    }

    private PlanDraftStep generateExplanationStep(String issue, String repoContext, String file, int dependsOnIndex) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("issue", issue);
        if (!repoContext.isBlank()) {
            payload.put("repo_context", repoContext);
        }
        if (!file.isBlank()) {
            payload.put("file", file);
        }
        payload.put("gathered_context", Map.of(
                "source", "build_context_pack",
                "hint", "Use upstream build_context_pack result as the primary repository evidence."));
        payload.put("inject", Map.of(
            "gathered_context", "$upstream[0].result"));

        return new PlanDraftStep("generate_explanation", payload, List.of(dependsOnIndex));
    }

    private PlanDraftStep designSolutionStep(String issue, String repoContext, int dependsOnIndex) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("issue", issue);
        if (!repoContext.isBlank()) {
            payload.put("context", repoContext);
        }
        payload.put("constraints", List.of(
                "respond in Simplified Chinese",
                "format output in Markdown",
                "use sections: 结论, 发现, 代码位置, 建议修复, 风险",
                "keep plan minimal and execution-oriented",
                "base recommendations on build_context_pack evidence"));
        payload.put("inject", Map.of(
            "context", Map.of(
                "from", "$upstreamByType.build_context_pack.result.summary",
                "default", repoContext
            )));

        return new PlanDraftStep("design_solution", payload, List.of(dependsOnIndex));
    }

    private PlanDraftStep generatePatchStep(String issue, String repoContext, String file, List<Integer> dependsOnIndices) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("issue", issue);
    if (!file.isBlank()) {
        payload.put("file", file);
    }
    if (!repoContext.isBlank()) {
        payload.put("context", repoContext);
    }
    payload.put("constraints", List.of(
        "respond in Simplified Chinese",
        "format output in Markdown",
        "return exactly one fenced unified diff block",
        "keep the patch minimal and directly applicable by git apply",
        "preserve existing behavior except for the required fix"
    ));
    payload.put("inject", Map.of(
        "file", Map.of(
            "from", "$upstreamByType.load_code.result.files[0].path",
            "fallbackFrom", List.of("$upstreamByType.search_semantic.result.matches[0].path"),
            "default", file),
        "context", Map.of(
            "from", "$upstreamByType.build_context_pack.result.summary",
            "default", repoContext),
        "code", Map.of(
            "from", "$upstreamByType.load_code.result.code",
            "fallbackFrom", List.of(
                "$upstreamByType.build_context_pack.result.sources[0].content",
                "$upstreamByType.build_context_pack.result.retrieval[0].chunk"
            ),
            "required", true),
        "designSummary", Map.of(
            "from", "$upstreamByType.design_solution.result.summary",
            "fallbackFrom", List.of("$upstreamByType.design_solution.result.content")
        )
    ));

    return new PlanDraftStep("generate_patch", payload, dependsOnIndices);
    }

    private PlanDraftStep reviewPatchStep(String issue, String repoContext, String file, List<Integer> dependsOnIndices) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("issue", issue);
    if (!file.isBlank()) {
        payload.put("file", file);
    }
    if (!repoContext.isBlank()) {
        payload.put("context", repoContext);
    }
    payload.put("inject", Map.of(
        "file", Map.of(
            "from", "$upstreamByType.load_code.result.files[0].path",
            "fallbackFrom", List.of("$upstreamByType.search_semantic.result.matches[0].path"),
            "default", file),
        "context", Map.of(
            "from", "$upstreamByType.build_context_pack.result.summary",
            "default", repoContext),
        "code", Map.of(
            "from", "$upstreamByType.load_code.result.code",
            "fallbackFrom", List.of("$upstreamByType.build_context_pack.result.sources[0].content")
        ),
        "designSummary", Map.of(
            "from", "$upstreamByType.design_solution.result.summary",
            "fallbackFrom", List.of("$upstreamByType.design_solution.result.content")
        ),
        "patch", Map.of(
            "from", "$upstreamByType.generate_patch.result.patch",
            "required", true)
    ));

    return new PlanDraftStep("review_patch", payload, dependsOnIndices);
    }

    private PlanDraftStep applyPatchStep(List<Integer> dependsOnIndices) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("threeWay", true);
    payload.put("inject", Map.of(
        "patch", Map.of(
            "from", "$upstreamByType.review_patch.result.patch",
            "fallbackFrom", List.of("$upstreamByType.generate_patch.result.patch"),
            "required", true),
        "repoPath", Map.of(
            "from", "$upstreamByType.load_code.result.files[0].path",
            "fallbackFrom", List.of("$upstreamByType.search_semantic.result.matches[0].path"),
            "required", true)
    ));

    return new PlanDraftStep("apply_patch", payload, dependsOnIndices);
    }

    private PlanDraftStep commitChangesStep(String issue, List<Integer> dependsOnIndices) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("message", buildCommitMessage(issue));
    payload.put("all", true);
    payload.put("inject", Map.of(
        "repoPath", Map.of(
            "from", "$upstreamByType.apply_patch.result.repoPath",
            "fallbackFrom", List.of(
                "$upstreamByType.load_code.result.files[0].path",
                "$upstreamByType.search_semantic.result.matches[0].path"
            ),
            "required", true)
    ));

    return new PlanDraftStep("commit_changes", payload, dependsOnIndices);
    }

    private String buildCommitMessage(String issue) {
    String normalized = issue == null ? "" : issue.trim();
    if (normalized.isBlank()) {
        return "Apply AI-generated patch";
    }

    String prefixed = "Apply fix: " + normalized;
    return prefixed.length() <= 72 ? prefixed : prefixed.substring(0, 72);
    }

    private PlanDraftStep reviewCodeStep(String issue, String repoContext, List<Integer> dependsOnIndices) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("focus", "issue-driven review");
        if (!repoContext.isBlank()) {
            payload.put("context", repoContext);
        }
        payload.put("knownIssues", List.of(issue));
        payload.put("code", "No source code payload was retrieved; use retrieval snippets as conservative evidence.");
        payload.put("inject", Map.of(
            "context", Map.of(
                "from", "$upstreamByType.build_context_pack.result.summary",
                "default", repoContext),
                "code", Map.of(
                "from", "$upstreamByType.load_code.result.code",
                "fallbackFrom", List.of(
                    "$upstreamByType.build_context_pack.result.sources[0].content",
                    "$upstreamByType.build_context_pack.result.retrieval[0].chunk"
                ),
                "default", "No source code payload was retrieved; use retrieval snippets as conservative evidence."
                )
        ));

        return new PlanDraftStep("review_code", payload, dependsOnIndices);
    }

    private boolean isExplainIntent(String issue) {
        String normalized = issue.toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "explain",
                "understand",
                "authentication",
                "module",
                "how does",
                "how do",
                "interacts with",
                "builds");
    }

    private boolean isReviewIntent(String issue) {
        String normalized = issue.toLowerCase(Locale.ROOT);
        return containsAny(normalized, "review", "audit")
                && !containsAny(normalized, "fix", "bug", "error", "failure", "failed");
    }

    private boolean isDiagnosisIntent(String issue) {
        String normalized = issue.toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "fix",
                "bug",
                "debug",
                "fail",
                "failure",
                "failed",
                "error",
                "why is",
                "why does",
                "why might",
                "root cause");
    }

    private boolean isFlowTraceIntent(String issue) {
        String normalized = issue.toLowerCase(Locale.ROOT);
        return normalized.contains("trace")
                && containsAny(normalized, "flow", "pipeline", "path", "interaction");
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        String normalized = raw.trim();
        if (normalized.toLowerCase(Locale.ROOT).startsWith("describe your issue:")) {
            normalized = normalized.substring("describe your issue:".length()).trim();
        }
        return normalized;
    }

    // =========================================================================
    // Drift 专案 DAG  —  generateDriftPlan(issue, difficulty, playerId)
    // =========================================================================

    /**
     * 为 Drift 黑客松专案生成执行计划。
     *
     * @param issue    玩家输入的原始 issue 文本
     * @param difficulty 难度等级 3-5（<3 应走标准 repairPipeline）
     * @param playerId  MC 玩家名，透传给 drift_refresh action
     * @return DAG step 列表，依赖关系通过 dependsOn 索引表达
     */
    public List<PlanDraftStep> generateDriftPlan(String issue, int difficulty, String playerId) {
        String normalizedIssue = normalize(issue);
        String player = playerId != null ? playerId : "unknown";

        if (difficulty == 3) {
            // plan(0) → code(1) → review(2) → deploy(3) → refresh(4)
            return List.of(
                driftPlanStep(normalizedIssue, player, List.of()),
                driftCodeStep(normalizedIssue, List.of(0)),
                driftReviewStep(normalizedIssue, List.of(0, 1)),
                driftDeployStep(normalizedIssue, List.of(2)),
                driftRefreshStep(normalizedIssue, player, List.of(3))
            );
        }

        if (difficulty == 4) {
            // plan(0) → code(1) → review(2) → test(3) → deploy(4) → git_push(5) → refresh(6)
            return List.of(
                driftPlanStep(normalizedIssue, player, List.of()),
                driftCodeStep(normalizedIssue, List.of(0)),
                driftReviewStep(normalizedIssue, List.of(0, 1)),
                driftTestStep(normalizedIssue, List.of(2)),
                driftDeployStep(normalizedIssue, List.of(3)),
                driftGitPushStep(normalizedIssue, player, List.of(4)),
                driftRefreshStep(normalizedIssue, player, List.of(5))
            );
        }

        // difficulty == 5（或更高）
        // web_search(0) → plan(1) → code(2) → review(3) → test(4) → deploy(5) → git_push(6) → refresh(7)
        return List.of(
            driftWebSearchStep(normalizedIssue),
            driftPlanStep(normalizedIssue, player, List.of(0)),
            driftCodeStep(normalizedIssue, List.of(1)),
            driftReviewStep(normalizedIssue, List.of(1, 2)),
            driftTestStep(normalizedIssue, List.of(3)),
            driftDeployStep(normalizedIssue, List.of(4)),
            driftGitPushStep(normalizedIssue, player, List.of(5)),
            driftRefreshStep(normalizedIssue, player, List.of(6))
        );
    }

    /** Convenience overload without playerId for tests. */
    public List<PlanDraftStep> generateDriftPlan(String issue, int difficulty) {
        return generateDriftPlan(issue, difficulty, "unknown");
    }

    // ── Drift plan step ───────────────────────────────────────────────────────

    private PlanDraftStep driftPlanStep(String issue, String playerId, List<Integer> dependsOn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("issue_text", issue);
        payload.put("issue", issue);
        payload.put("player_id", playerId);
        payload.put("target", "drift-system");
        payload.put("format", "task_list");
        payload.put("constraints", List.of(
            "respond in Simplified Chinese",
            "break the issue into atomic executable sub-tasks",
            "each sub-task must have: id, title, type (code/test/deploy/config), priority"
        ));
        return new PlanDraftStep("drift_plan", payload, dependsOn);
    }

    // ── Drift code step ───────────────────────────────────────────────────────

    private PlanDraftStep driftCodeStep(String issue, List<Integer> dependsOn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("issue", issue);
        payload.put("target", "drift-system");
        payload.put("constraints", List.of(
            "respond in Simplified Chinese",
            "output a unified diff patch applicable by git apply",
            "minimal change — only what is needed to address the issue",
            "preserve existing behavior outside the fix scope"
        ));
        payload.put("inject", Map.of(
            "plan_result", Map.of(
                "from", "$upstreamByType.drift_plan.result.tasks",
                "fallbackFrom", List.of("$upstreamByType.drift_plan.result.summary"),
                "required", false)
        ));
        return new PlanDraftStep("drift_code", payload, dependsOn);
    }

    // ── Drift review step (gate) ──────────────────────────────────────────────

    private PlanDraftStep driftReviewStep(String issue, List<Integer> dependsOn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("issue", issue);
        payload.put("gate", true);
        payload.put("constraints", List.of(
            "respond in Simplified Chinese",
            "check: patch addresses the issue",
            "check: no regressions introduced",
            "check: Drift backend Python style conventions",
            "output approved: true/false and reason"
        ));
        payload.put("inject", Map.of(
            "code_result", Map.of(
                "from", "$upstreamByType.drift_code.result.patch",
                "required", true),
            "plan_result", Map.of(
                "from", "$upstreamByType.drift_plan.result.tasks",
                "required", false)
        ));
        return new PlanDraftStep("drift_review", payload, dependsOn);
    }

    // ── Drift test step ───────────────────────────────────────────────────────

    private PlanDraftStep driftTestStep(String issue, List<Integer> dependsOn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("test_scope", "drift_backend");
        payload.put("issue", issue);
        payload.put("inject", Map.of(
            "patch_content", Map.of(
                "from", "$upstreamByType.drift_code.result.patch",
                "required", false)
        ));
        return new PlanDraftStep("drift_test", payload, dependsOn);
    }

    // ── Drift deploy step ─────────────────────────────────────────────────────

    private PlanDraftStep driftDeployStep(String issue, List<Integer> dependsOn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("issue", issue);
        payload.put("inject", Map.of(
            "patch_content", Map.of(
                "from", "$upstreamByType.drift_code.result.patch",
                "required", true),
            "patch_path", Map.of(
                "from", "$upstreamByType.drift_code.result.patch_path",
                "required", false)
        ));
        return new PlanDraftStep("drift_deploy", payload, dependsOn);
    }

    // ── Drift git push step ───────────────────────────────────────────────────

    private PlanDraftStep driftGitPushStep(String issue, String playerId, List<Integer> dependsOn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("issue_text", issue.length() > 100 ? issue.substring(0, 100) : issue);
        payload.put("player_id", playerId);
        payload.put("branch_prefix", "demo");
        payload.put("inject", Map.of(
            "deploy_result", Map.of(
                "from", "$upstreamByType.drift_deploy.result.summary",
                "required", false)
        ));
        return new PlanDraftStep("drift_git_push", payload, dependsOn);
    }

    // ── Drift refresh step ────────────────────────────────────────────────────

    private PlanDraftStep driftRefreshStep(String issue, String playerId, List<Integer> dependsOn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("event", "drift_issue_resolved");
        payload.put("issue", issue);
        payload.put("player_id", playerId);
        payload.put("inject", Map.of(
            // Pull deploy step's git_output as summary; fall back to code step's result lines count
            "summary", Map.of(
                "from", "$upstreamByType.drift_deploy.result.git_output",
                "fallbackFrom", List.of(
                    "$upstreamByType.drift_deploy.result.reload",
                    "$upstreamByType.drift_code.result.patch_path"
                ),
                "required", false)
        ));
        return new PlanDraftStep("drift_refresh", payload, dependsOn);
    }

    // ── Web search step (difficulty 5) ────────────────────────────────────────

    private PlanDraftStep driftWebSearchStep(String issue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v1");
        payload.put("query", issue);
        payload.put("purpose", "find external reference implementations or solutions for Drift system");
        return new PlanDraftStep("drift_web_search", payload, List.of());
    }
}
