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
| B-010 | 0       | Stage 6 | 真实 Slack Token + LLM Key     | 生产环境接入：Slack Event API + OpenAI/GLM 真实 key；消除 DEMO_MODE 依赖     | P4     |
| B-011 | 17      | Stage 6 | 多模型异构调度                  | Worker 声明 `model_capability`；调度器按任务类型路由到不同 LLM 后端            | P4     |
| B-012 | 10      | Stage 3 | 中断作为异步信号                | 中断信号走 Redis PubSub；Worker 轮询期间收到信号则提前退出并提交 FAILED 状态  | P3     |
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
---

## 里程碑映射

```
Stage 2 (Context Restore):  B-001
Stage 3 (Fault Tolerance):  B-002, B-003, B-004, B-012
Stage 4 (Memory/Retrieval): B-005, B-009
Stage 5 (Concurrency):      B-006
Stage 6 (Production):       B-007, B-008, B-010, B-011
```
