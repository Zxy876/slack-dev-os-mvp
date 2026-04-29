package com.asyncaiflow.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PlannerExecuteRequest(
        @NotBlank(message = "must not be blank") String issue,
        @JsonProperty("repo_context") String repoContext,
        String file,
        /**
         * Drift 专案难度评分 1-5（由 intent_engine 评分，透传此处）。
         * 为 null 时走标准 repairPipeline；>= 3 时走 Drift 专案 DAG。
         */
        @Min(1) @Max(5)
        @JsonProperty("difficulty") Integer difficulty,
        /**
         * 触发此 issue 的 Minecraft 玩家名，用于 drift_refresh 步骤回写通知。
         */
        @JsonProperty("player_id") String playerId,
        /**
         * git push 时的分支前缀（如 "demo/judge1-20260406"）。
         * 为 null 时使用 git-worker 默认配置。
         */
        @JsonProperty("branch_prefix") String branchPrefix
) {
}
