# BACKLOG — Slack Dev OS MVP

> 追踪所有 **AUTOPILOT_SOURCE_INDEX.md** 中标注为 ⏳ DEFERRED 或需后续迭代的工程任务。
>
> **格式**：`B-NNN | 来源 ID | 阶段 | 标题 | 说明`

---

## 活跃积压

| 编号  | 来源 ID | 阶段    | 标题                            | 说明                                                                        | 优先级 |
|-------|---------|---------|-------------------------------|-----------------------------------------------------------------------------|--------|
~~| B-001 | 7, 18   | Stage 2 | Context Restore 隔离集成测试   | 验证：同 slackThreadId 的 Action N 能拿到 N-1 的 notepad；不同 Thread 不互污 | P1     |~~ → ✅ DONE
~~| B-002 | 9       | Stage 3 | Watchdog E2E 场景测试          | 用 `SchedulerReliabilityIntegrationTest` 框架，验证 lease 超时 → RETRY_WAIT → 重跑 | P2     |~~ → ✅ DONE
~~| B-003 | 10      | Stage 3 | 用户中断信号（Interrupt Signal）| 设计并实现 `POST /devos/interrupt`：将 RUNNING Action 标记为 FAILED，触发 Interrupt Handler | P2     |~~ → ✅ DONE
~~| B-004 | 11      | Stage 3 | DAG 环检测                     | `validateUpstreamActions()` 应检测有向环（A→B→A）并返回 400                  | P2     |~~ → ✅ DONE
~~| B-005 | 12      | Stage 4 | Page Fault / 仓库文件检索      | 实现 `repo_retrieval_worker`：接收文件路径，返回内容摘要注入 notepad          | P3     |~~ → ✅ DONE
~~| B-006 | 13      | Stage 5 | Redis SETNX Worker 互斥锁      | `ActionQueueService.pollAction()` 使用 SETNX 防止双 Worker 竞争同一 Action   | P3     |~~ → ✅ DONE
| B-007 | 14      | Stage 6 | 访问控制 / 权限隔离             | Workflow 粒度 RBAC；slackThreadId 绑定用户 scope                              | P4     | → ✅ DONE
~~| B-008 | 15      | Stage 6 | 工具管理器（Tool Manager）      | Worker 能力注册中心：动态加载 / 卸载 capability；工具调用结果回写 notepad     | P4     |~~ → ✅ DONE
| B-009 | 8       | Stage 4 | 多层检索器（RAG）               | 实现向量检索 Worker；notepad 中附加检索结果                                   | P3     |
~~| B-010 | 0       | Stage 6 | 真实 Slack Token + LLM Key     | 生产环境接入：Slack Event API + OpenAI/GLM 真实 key；消除 DEMO_MODE 依赖     | P4     |~~ → 🔶 PARTIAL
| B-011 | 17      | Stage 6 | 多模型异构调度                  | Worker 声明 `model_capability`；调度器按任务类型路由到不同 LLM 后端            | P4     |
| B-012 | 10      | Stage 3 | 中断作为异步信号                | 中断信号走 Redis PubSub；Worker 轮询期间收到信号则提前退出并提交 FAILED 状态  | P3     |
~~| B-014 | RELEASE | RC      | Release Candidate Audit / v0.1.0-rc1 | 整理 README、RELEASE_NOTES、SECURITY_CHECKLIST、secret_scan.sh；打 tag v0.1.0-rc1；不新增功能 | P1     |~~ → ✅ DONE
~~| B-015 | RELEASE | RC      | RC Final Verification / v0.1.0-rc2   | 确认 tag + Actions 状态；修正 SECURITY_CHECKLIST S-010 过度声明；创建 GitHub prerelease v0.1.0-rc2 | P1     |~~ → ✅ DONE
| B-010-live-personal | 0 | Stage 6 | Personal Slack Live Smoke | 本地真实 Slack Bot Token + LLM key live smoke；`run_slack_live_smoke.sh` + `test_slack_posting.py` (10 tests)；CI 不跑 live | P2 | → ✅ DONE (live smoke PASSED 2026-05-01)
~~| B-017 | 0 | Stage 7 | Git Patch Preview Executor (Dry-Run Coding) | `mode=patch_preview`：workspace 隔离副本 + replaceFrom→replaceTo + unified diff [PATCH_PREVIEW]；或无 replace* 时 LLM 生成 [PATCH_PLAN_ONLY]；原 repo 文件永不修改（不变量）；worker.py patch tools + test_patch_preview.py (10 tests) + run_patch_preview_e2e.sh | P1 |~~ → ✅ DONE (dry-run preview — original repo never modified)
| B-013 | CI      | CI      | Flaky Test 稳定化              | `dispatchRespectsPerWorkflowParallelLimit` 在 GHA 中偶发失败；根因：`spring.task.scheduling.enabled=false` 不能阻止 `@Scheduled` bean 运行；修复：为 `SchedulerMaintenanceService` 加 `@ConditionalOnProperty` 使该属性真正生效 | P1     | → ✅ DONE

---

## 已完成（参考）

| 编号  | 标题                              | 完成于          |
|-------|-----------------------------------|-----------------|
| —     | Stage 0: MVP Kernel（84 tests）   | commit `6e5bb8c` (local) |
| —     | Stage 1: CI proof（ci.yml + e2e） | commit `6e5bb8c` |
| —     | PCB 完整字段集（21 字段）         | schema-h2.sql   |
| —     | 7 状态机 + isValidTransition      | ActionService.java |
| —     | BLOCKED→QUEUED DAG 解锁           | unblockDownstreamActions() |
| —     | notepad_ref 持久化 + 下发 Worker  | extractNotepadFromResult() |
| B-001 | Stage 2: Context Restore（prevActionId + 3 集成测试 + E2E 双轮） | commit `feat(stage2)` — 2026-04-30 |
| B-002 | Stage 3: Watchdog Lease/Retry（3 集成测试: RETRY_WAIT, DEAD_LETTER, enqueueDueRetries 全循环） | commit `feat(stage3)` — 2026-04-30 |
| B-003 | Stage 3: User Interrupt Signal（RUNNING/QUEUED/RETRY_WAIT/BLOCKED → FAILED，`POST /devos/interrupt`，4 集成测试: RUNNING中断, QUEUED中断+poll保护, RETRY_WAIT中断+retry保护, 终态拒绝） | commit `feat(stage3): B-003` — 2026-04-30 |
| B-004 | Stage 3: DAG 循环检测（BFS循环检测 + 工作流防循环 + 4 集成测试） | commit `feat(stage3): B-004` — 2026-04-30 |
| B-005 | Stage 4: Page Fault / Repository File Retrieval（DevOsStartRequest.repoPath/filePath 透传 payload；worker safe_read_repo_file()：安全校验 + 文件读取；DEMO_MODE [PAGE_IN] marker；notepad 含 [page-in:filePath]；2 集成测试 + Page Fault E2E） | commit 'feat(stage4): B-005' — 2026-04-30 |
| B-006 | Stage 5: Workspace Single-Writer Mutex（`DevOsStartRequest.writeIntent/workspaceKey` 透传 payload；`ActionQueueService.tryAcquireWorkspaceLock/releaseWorkspaceLock` Redis SETNX；`ActionService.pollAction` 変化：获得锁失败则重入队列；在 submitResult/interruptAction/applyFailureWithRetry 释放锁；4 集成测试: 阻塞防护, SUCCEEDED释锁, 只读不预, 中断释锁） | commit `feat(stage5): B-006` — 2026-04-30 |
| B-008 | Stage 6: Tool Manager（`ToolCall` / `ToolResponse` dataclasses；`ToolManager` 白名单注册表（WHITELIST={repo.read_file}）；Page Fault 路径改走 `TOOL_MANAGER.execute()`；未知工具返回 ok=False 不抛异常；`test_tool_manager.py`：7 pytest smoke tests（whitelist拒绝, 未知工具, 路径穿越, 绝对路径, 正常读取）；CI devos-demo-e2e.yml 新增 smoke test step） | commit `feat(stage6): B-008` — 2026-04-30 |
| B-013 | CI: Flaky Test 稳定化（`SchedulerMaintenanceService` 加 `@ConditionalOnProperty(name="spring.task.scheduling.enabled", matchIfMissing=true)`，使所有测试类中的 `spring.task.scheduling.enabled=false` 真正阻止后台调度器运行；`dispatchRespectsPerWorkflowParallelLimit` 5 轮全绿） | commit `test(ci): B-013` — 2026-04-30 |
| B-007 | Stage 6: slackThread Ownership Guard（`DevOsInterruptRequest` 新增 required `slackThreadId`；`DevOsService.resolveNotepadRef()` 加 thread 归属校验 — 跨 thread prevActionId → 403；`DevOsService.interrupt()` 加 thread 归属校验 — 跨 thread interrupt → 403；`ApiException.defaultCode()` 增 FORBIDDEN→ACCESS_DENIED；`DevOsAccessControlTest`：4 集成测试（A 同 thread notepad 继承, B 跨 thread notepad 被拒, C 同 thread interrupt, D 跨 thread interrupt 被拒）） | commit `feat(stage6): B-007` — 2026-04-30 |
| B-010 | Stage 6: Production Config Readiness（PARTIAL — config boundary, not live integration）（`select_llm_backend()`：DEMO→GLM→OpenAI→RuntimeError；`validate_runtime_config()`：启动时配置汇总含遮掩 secret；`redact_secret()`：前4字符+***；`REQUIRE_SLACK_POST` 标志；`.env.example` 完整模板；`scripts/run_production_config_check.sh` 干跑验证；`test_runtime_config.py`：14 pytest cases（A~F + config summary）；不调用真实 Slack/LLM，不在 CI 注入 secret） | commit `feat(stage6): B-010` — 2026-04-30 |
| B-014 | Release Candidate Audit（`RELEASE_NOTES.md`、`docs/SECURITY_CHECKLIST.md`、`scripts/secret_scan.sh`；README 加 Quickstart / RC Status / CI coverage 说明；BACKLOG/SCENARIO_MATRIX/README 一致性审计通过；108 Java + 21 Python tests，E2E x2 全绿，secret scan PASS；tag `v0.1.0-rc1` 推送） | commit `docs(release): B-014` — 2026-04-30 |
| B-015 | RC Final Verification（核查 tag `v0.1.0-rc1` 指向正确 commit `c0f8aa1`；CI + Demo E2E 全绿；修正 SECURITY_CHECKLIST S-010 "No known CVEs" 过度声明 → ⚠️ PENDING（pip-audit 未运行）；文档修正 → 新建 tag `v0.1.0-rc2` 指向修正后 commit；创建 GitHub prerelease `v0.1.0-rc2`） | commit `docs(release): B-015` — 2026-05-01 |
| B-010-live-personal | Personal Slack Live Smoke — ✅ DONE（`scripts/run_slack_live_smoke.sh`：preflight检查 + config dry-run + backend health + curl /devos/start + poll until COMPLETED；`test_slack_posting.py`：10 mock tests（channel解析, thread_ts解析, missing token行为, Slack API error, token不泄漏）；`.env.example` 新增 DEVOS_LIVE_SLACK_THREAD_ID / DEVOS_LIVE_SLACK_CHANNEL；README 新增 Personal Slack Live Smoke 章节；CI 不跑 live smoke；无真实 token 提交；**live smoke verified 2026-05-01: Action COMPLETED, Slack post sent to C0AV55H69QT**） | commit `feat(stage6): B-010-live` + `docs(stage6): B-010-live-personal DONE` — 2026-05-01 |
| B-017 | Stage 7: Dry-Run Coding / Patch Preview（`DevOsStartRequest` 新增 `mode`, `replaceFrom`, `replaceTo` 字段；`DevOsService.buildPayload()` 透传 `mode/replace_from/replace_to`；`worker.py` ToolManager 扩展白名单 repo.create_workspace_copy / repo.replace_in_file_preview / repo.diff_workspace；`execute_patch_preview()` 函数：workspace 隔离副本 + replaceFrom→replaceTo diff → [PATCH_PREVIEW]；无 replace_from 时 LLM 生成 [PATCH_PLAN_ONLY]；原 repo 文件永不修改（不变量）；DEMO_MODE 下 patch_preview 返回 stub；`test_patch_preview.py`：10 pytest cases（workspace 隔离, 路径穿越, diff 生成, 未知 mode 回退, replace→PATCH_PREVIEW, no-replace→PLAN）；`scripts/run_patch_preview_e2e.sh`：fixture + 原文件不变断言） | commit `feat(stage7): B-017` — 2026-05-01 |
---

## 里程碑映射

```
Stage 2 (Context Restore):  B-001
Stage 3 (Fault Tolerance):  B-002, B-003, B-004, B-012
Stage 4 (Memory/Retrieval): B-005, B-009
Stage 5 (Concurrency):      B-006
Stage 6 (Production):       B-007, B-008, B-010, B-011
```
