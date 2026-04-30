# AUTOPILOT SOURCE INDEX — Slack Dev OS MVP

> **目的**：把三份核心设计材料（架构报告、系统建模结构表、26 题）逐段映射到可执行工程动作，形成有 ID 的执行索引。
>
> **来源**：架构报告 = ARC | 系统建模结构表 = MODEL | 26 题 = Q26
> **状态符号**：✅ DONE | 🔶 PARTIAL | ⏳ DEFERRED | ❌ NOT STARTED

---

## 执行索引总表

| ID  | 来源    | 主题                             | 状态    | 证据 / 实现位置                              | 下一步 / 备注                     |
|-----|---------|----------------------------------|---------|----------------------------------------------|----------------------------------|
| 0   | ARC     | 运行时现实（Runtime Reality）    | ✅ DONE | 见 ID-0 详细记录                             | —                                |
| 1   | ARC     | Slack 系统调用入口               | ✅ DONE | `DevOsController.POST /devos/start`          | —                                |
| 2   | ARC     | Workflow / 进程组初始化          | ✅ DONE | `DevOsService.createWorkflow()`              | —                                |
| 3   | ARC     | PCB 初始化 → QUEUED             | ✅ DONE | `DevOsService.createAction()` status=QUEUED  | —                                |
| 4   | ARC     | Redis 能力队列（就绪队列）       | ✅ DONE | `ActionQueueService.enqueue()` key=devos_chat| —                                |
| 5   | MODEL   | PCB 完整字段集                   | ✅ DONE | 见 ID-5 详细记录                             | —                                |
| 6   | MODEL   | 7 状态机（含 DEAD_LETTER）       | ✅ DONE | `ActionStatus.java` enum + isValidTransition | —                                |
| 7   | MODEL   | Context Switch / Notepad 恢复   | ✅ DONE    | `DevOsContextRestoreTest`：testNotepadPropagatesAcrossSequentialActions, testNotepadIsolatedAcrossThreads, testPrevActionIdNotFoundFallsBackToNull; `worker.py` notepad 注入修复; `DevOsService.resolveNotepadRef()` | —    |
| 8   | ARC     | 多层检索器（工具调用 / RAG）     | ⏳ DEFERRED | — | Stage 4 → B-009                  |
| 9   | ARC     | Watchdog 超时回收                | ✅ DONE    | `ActionService.reclaimExpiredLeases()` + `DevOsWatchdogLeaseTest` (3 tests: RETRY_WAIT, DEAD_LETTER, enqueueDueRetries) | B-002 ✓ |
| 10  | Q26     | 用户中断信号（Interrupt Signal） | ✅ DONE | `DevOsController.POST /devos/interrupt` → `DevOsService.interrupt()` → `ActionService.interruptAction()`; `isValidTransition` 扩展支持 QUEUED/RETRY_WAIT/BLOCKED → FAILED; `DevOsInterruptTest` (4 tests: RUNNING, QUEUED, RETRY_WAIT, terminal guard) | B-003 ✓ |
| 11  | MODEL   | DAG 依赖 / 上游 ID               | ✅ DONE | `action_dependency` 表 + `validateUpstreamActions()` + `ActionService.wouldCreateCycle()` BFS; `DevOsDagCycleTest` (4 tests) | B-004 ✓ |
| 12  | Q26     | Page Fault / 仓库检索            | ✅ DONE | `DevOsStartRequest.repoPath/filePath` 透传 payload；`worker.py safe_read_repo_file()`：安全校验 + 读取；DEMO_MODE [PAGE_IN] marker；`DevOsPageFaultRequestTest`（2 tests）；Page Fault E2E PASSED | B-005 ✓ |
| 13  | MODEL   | Redis SETNX 互斥锁               | ⏳ DEFERRED | — | Stage 5 → B-006                  |
| 14  | Q26     | 访问控制 / 权限隔离              | ⏳ DEFERRED | — | Stage 6 → B-007                  |
| 15  | Q26     | 工具管理器（Tool Manager）       | ⏳ DEFERRED | — | Stage 6 → B-008                  |
| 16  | MODEL   | notepad_ref 持久化与注入         | ✅ DONE | `extractNotepadFromResult()` + `notepadRef` in poll response | —  |
| 17  | Q26     | 多模型异构调度                   | ⏳ DEFERRED | — | Stage 6 → B-011                  |
| 18  | ARC     | 顺序指令周期完整性验证           | ✅ DONE    | E2E 双轮（Round 1 + Round 2 prevActionId）均通过；`DevOsContextRestoreTest`（3 tests）；87 tests BUILD SUCCESS | — |
| 19  | ARC     | BLOCKED → QUEUED 依赖解锁        | ✅ DONE | `ActionService.unblockDownstreamActions()` + isValidTransition(BLOCKED→QUEUED) | — |
| 20  | MODEL   | Lease 字段集（PCB 可靠性字段）   | ✅ DONE | `lease_expire_at`, `retry_count`, `backoff_seconds`, `last_reclaim_reason` in schema | — |

---

## ID-0 详细记录 — 运行时现实（Runtime Reality Audit）

**来源**：ARC（架构报告 §1）

### ✅ 已实现

| 层次           | 证据                                                                          |
|----------------|-------------------------------------------------------------------------------|
| HTTP 入口      | `DevOsController.java` — `POST /devos/start` → `ApiResponse<DevOsStartResponse>` |
| 进程组初始化   | `DevOsService.java` — 创建 `WorkflowEntity`，status=RUNNING                  |
| PCB 创建       | `DevOsService.java` — 创建 `ActionEntity`，status=QUEUED，slackThreadId 写入  |
| 就绪队列       | `ActionQueueService.java` — `enqueue()` → Redis List key `action_queue:devos_chat` |
| Worker poll    | `python-workers/devos_chat_worker/worker.py` — `GET /action/poll?workerId=X` |
| LLM 调用       | `worker.py` — `call_llm(user_text, notepad)` → OpenAI / GLM / DEMO stub      |
| Result 提交    | `worker.py` → `POST /action/result`；`ActionService.submitResult()` 处理     |
| notepad 持久化 | `ActionService.extractNotepadFromResult()` → `notepad_ref` CLOB 字段          |
| 健康检查       | `HealthController.java` — `GET /health` → `Result.ok("healthy","ok")`         |
| CI             | `.github/workflows/ci.yml` — Maven test，Java 21，Redis 7，通过 commit `6e5bb8c` |
| E2E CI         | `.github/workflows/devos-demo-e2e.yml` — DEMO_MODE E2E，通过 commit `6e5bb8c` |

### ❌ 运行时缺口

| 缺口                         | 说明                           | 追踪            |
|------------------------------|--------------------------------|-----------------|
| 真实 Slack Token / LLM Key   | 仅 DEMO_MODE 通过，生产需配置  | B-010           |
| 跨线程 notepad 隔离测试      | 见 ID-7                        | B-001           |

---

## ID-5 详细记录 — PCB 完整字段集

**来源**：MODEL（系统建模结构表 §PCB）

| 设计字段             | schema-h2.sql / ActionEntity.java             | 状态  |
|----------------------|-----------------------------------------------|-------|
| action_id            | `id BIGINT PRIMARY KEY`                       | ✅    |
| workflow_id          | `workflow_id BIGINT NOT NULL`                 | ✅    |
| type (capability)    | `type VARCHAR(64)`                            | ✅    |
| status               | `status VARCHAR(32)` + `ActionStatus` enum    | ✅    |
| payload              | `payload CLOB`                                | ✅    |
| worker_id (mutex)    | `worker_id VARCHAR(64)`                       | ✅    |
| retry_count          | `retry_count INT DEFAULT 0`                   | ✅    |
| max_retry_count      | `max_retry_count INT DEFAULT 3`               | ✅    |
| backoff_seconds      | `backoff_seconds INT DEFAULT 5`               | ✅    |
| execution_timeout_seconds | `execution_timeout_seconds INT DEFAULT 300` | ✅  |
| lease_expire_at      | `lease_expire_at TIMESTAMP`                   | ✅    |
| next_run_at          | `next_run_at TIMESTAMP`                       | ✅    |
| claim_time           | `claim_time TIMESTAMP`                        | ✅    |
| reclaim_time         | `reclaim_time TIMESTAMP`                      | ✅    |
| last_reclaim_reason  | `last_reclaim_reason VARCHAR(64)`             | ✅    |
| slack_thread_id      | `slack_thread_id VARCHAR(128)`                | ✅    |
| notepad_ref          | `notepad_ref CLOB`                            | ✅    |
| error_message        | `error_message VARCHAR(512)`                  | ✅    |
| upstream_action_ids  | `action_dependency` 表（单独存储 DAG）        | ✅    |
| lease_renew_success_count | `lease_renew_success_count INT`         | ✅    |
| lease_renew_failure_count | `lease_renew_failure_count INT`         | ✅    |

---

## ID-6 详细记录 — 7 状态机

**来源**：MODEL（系统建模结构表 §状态机）

```
BLOCKED ──→ QUEUED ──→ RUNNING ──→ SUCCEEDED
                              ├──→ FAILED
                              ├──→ RETRY_WAIT ──→ QUEUED
                              │                └──→ DEAD_LETTER
                              └──→ DEAD_LETTER
```

实现位置：
- `ActionStatus.java` — 7 个枚举值，`isTerminal()` 方法
- `ActionService.isValidTransition()` — 完整转移矩阵，拒绝非法转移
- `ActionService.reclaimExpiredLeases()` — 超时 → RETRY_WAIT 或 DEAD_LETTER

---

## ID-7 详细记录 — Context Switch / Notepad 恢复

**来源**：MODEL（系统建模结构表 §Context Switch）

### ✅ 已实现

| 步骤                        | 证据                                                           |
|-----------------------------|----------------------------------------------------------------|
| notepad 写入 PCB            | `extractNotepadFromResult()` → `action.setNotepadRef(notepad)` |
| notepad 随 poll 下发        | `ActionAssignmentResponse.notepadRef` 字段                     |
| worker 注入 LLM prompt      | `worker.py` `_call_openai()` / `_call_glm()` — Context Restore message |
| retry 时恢复上下文          | `worker.py` line ~303: `if retry_count > 0 and notepad:`       |

### ⏳ 待实现（→ B-001）

- 跨 slackThreadId 隔离验证（相同 Thread 看到自己的 notepad，不同 Thread 互不污染）
- 多轮顺序指令周期（Action N-1 完成后 Action N 拿到 N-1 的 notepad）的集成测试

---

## ID-11 详细记录 — DAG 依赖 / BLOCKED 解锁

**来源**：MODEL（系统建模结构表 §DAG）

### ✅ 已实现

| 步骤                         | 证据                                                         |
|------------------------------|--------------------------------------------------------------|
| 依赖存储                     | `action_dependency` 表：`upstream_action_id`, `downstream_action_id` |
| 创建时设置 BLOCKED           | `ActionService.createAction()` — `upstreamActionIds` 非空 → BLOCKED |
| 上游完成 → 解锁下游          | `ActionService.unblockDownstreamActions()` → BLOCKED→QUEUED  |
| 转移合法性                   | `isValidTransition(BLOCKED, QUEUED)` = true                  |
| 唯一索引                     | `uk_action_dependency (upstream_action_id, downstream_action_id)` |

### ⏳ 待实现（→ B-004）

- 环检测（A→B→A 应被拒绝）
- 多层 DAG E2E 测试（A→B→C）

---
