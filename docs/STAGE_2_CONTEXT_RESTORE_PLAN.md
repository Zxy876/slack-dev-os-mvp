# Stage 2 Context Restore Plan

> **目标**：验证 PCB Context Switch 在顺序指令周期中正确恢复 —— Action N 执行时能读到 Action N-1 写入的 notepad_ref，且不同 slackThreadId 之间不交叉污染。
>
> **来源 ID**：7, 8, 16, 18（AUTOPILOT_SOURCE_INDEX.md）
> **BACKLOG**：B-001
> **预计周期**：Stage 2（优先级 P1）

---

## ✅ Implementation Status: COMPLETE（2026-04-30）

**所有验收条件已满足**：

| 变更 | 文件 | 说明 |
|------|------|------|
| `prevActionId` 字段（optional） | `DevOsStartRequest.java` | 请求 DTO 新增 Long 字段，无 @NotNull |
| `resolveNotepadRef()` 方法 | `DevOsService.java` | 查询 prevActionId 的 notepad_ref，写入新 PCB |
| notepad 注入修复（retry guard 移除） | `worker.py` | retry=0 时也正确注入 notepad（prevActionId 场景） |
| DEMO_MODE 优先级修复 | `worker.py` | DEMO_MODE 优先于 GLM/OpenAI key — E2E 稳定性保证 |
| E2E 脚本双轮验证 | `scripts/run_demo_e2e.sh` | Round 2 使用 prevActionId，断言 `[Notepad context was present]` |
| GHA 工作流扩展 | `.github/workflows/devos-demo-e2e.yml` | Round 2 步骤 + `env -u` 屏蔽 LLM key |
| 3 个集成测试全通过 | `DevOsContextRestoreTest.java` | testNotepadPropagatesAcrossSequentialActions ✅ testNotepadIsolatedAcrossThreads ✅ testPrevActionIdNotFoundFallsBackToNull ✅ |
| 本地 E2E 全通过 | `run_demo_e2e.sh` | EXIT 0 — 两轮均含 `[DEMO]` 和 `[Notepad context was present]` |
| 总测试数 | 87 tests BUILD SUCCESS | — |

---

## 背景

当前已实现（Stage 0/1）：

1. Action 完成时，`ActionService.extractNotepadFromResult()` 将 `notepad` 字段写入 `action.notepad_ref`
2. Worker poll 响应 `ActionAssignmentResponse` 包含 `notepadRef` 字段
3. `worker.py` 在 `call_llm()` 中注入 notepad 为 "Context Restore" system message
4. DEMO_MODE stub 回复包含 `[Notepad context was present]` 标记

**缺口**：尚无集成测试验证跨 Action 的 notepad 传播路径，也无跨 Thread 隔离测试。

---

## 场景定义

### Scenario 2.1 — 顺序 notepad 传播

```
slackThreadId = "T-ALPHA"

  [Action 1]  type=devos_chat  status: QUEUED → RUNNING → SUCCEEDED
               payload: "设计系统架构"
               notepad_ref（写入）= "[action:1] 设计系统架构 → {stub response}"

  [Action 2]  type=devos_chat  status: BLOCKED → QUEUED → RUNNING → SUCCEEDED
               payload: "继续上一步"
               在 RUNNING 前，Worker poll 应拿到：
                 notepadRef = "[action:1] 设计系统架构 → {stub response}"
               notepad_ref（写入）= "[action:2] 继续上一步 → {stub response with notepad marker}"
```

**验收条件**：
- `Action 1.notepad_ref` ≠ null 且包含 action_id=1
- Worker poll for Action 2 的 `notepadRef` == Action 1 的 `notepad_ref`（或由 DevOsService 传递）
- `Action 2` 的 result 包含 `[Notepad context was present]`（DEMO_MODE 标记）

### Scenario 2.2 — 跨 Thread 隔离

```
slackThreadId = "T-ALPHA" → Action A1（notepad_ref = "ALPHA-DATA"）
slackThreadId = "T-BETA"  → Action B1（poll 时 notepadRef 应为 null 或 BETA 专属值）
```

**验收条件**：
- B1 的 poll 响应中 `notepadRef` 不含 "ALPHA-DATA"
- B1 完成后 A1 的 `notepad_ref` 未被修改

---

## 实现步骤

### Step 1：编写集成测试 `DevOsContextRestoreTest.java`

文件位置：`src/test/java/com/asyncaiflow/integration/DevOsContextRestoreTest.java`

测试要点：
```java
// 测试 1：单线程两轮 notepad 传播
@Test
void testNotepadPropagatesAcrossSequentialActions() {
    // 1. POST /devos/start slackThreadId="T-ALPHA" payload="step1"
    // 2. Worker poll → execute → submit SUCCEEDED with notepad
    // 3. assert action1.notepad_ref != null
    // 4. POST /devos/start slackThreadId="T-ALPHA" payload="step2"
    //    （or create Action 2 with upstream=Action 1 via /action/create）
    // 5. Worker poll for Action 2 → assert notepadRef == action1.notepad_ref
    // 6. Worker submit SUCCEEDED
    // 7. assert action2 result contains notepad marker
}

// 测试 2：跨 Thread 隔离
@Test
void testNotepadIsolatedAcrossThreads() {
    // 1. POST /devos/start T-ALPHA → complete with notepad "ALPHA-DATA"
    // 2. POST /devos/start T-BETA  → worker poll → assert notepadRef != "ALPHA-DATA"
}
```

### Step 2：确认 notepad 传播路径

当前路径（需 trace）：

```
Action 1 SUCCEEDED
    → ActionService.extractNotepadFromResult()
    → action1.notepad_ref = "..."
    → (Stage 2 实现) DevOsService.startNextAction() or 
       ActionService.createAction() with notepad_ref 从上游读取
    → Action 2 创建时 notepad_ref 从 action1 读取
    → Worker poll → ActionAssignmentResponse.notepadRef = action2.notepad_ref
```

**关键 gap**：`DevOsService` 目前每次 `/devos/start` 创建独立 Action，
**不会自动**将上一个 Action 的 notepad_ref 传给下一个。

**解决方案选项**：

| 选项 | 说明 | 工作量 |
|------|------|--------|
| A（推荐）| `/devos/start` 接受可选 `prevActionId` 字段；服务端查询其 notepad_ref 并写入新 Action | 小 |
| B | Worker 主动携带 notepad_ref 到 `/action/result`，再由服务传递给下一个 Action（需客户端感知） | 中 |
| C | 按 slackThreadId 查询最近 SUCCEEDED Action 的 notepad_ref（自动关联）| 中（需新查询）|

**推荐方案 A**：在 `DevOsStartRequest` 添加 `prevActionId`（optional），`DevOsService` 查询其 notepad_ref 写入新 PCB。

### Step 3：实现方案 A

```java
// DevOsStartRequest.java 新增字段
Long prevActionId;  // optional: 上一个 Action 的 ID，用于 Context Restore

// DevOsService.createAction() 中
if (request.prevActionId() != null) {
    ActionEntity prev = actionMapper.selectById(request.prevActionId());
    if (prev != null && prev.getNotepadRef() != null) {
        action.setNotepadRef(prev.getNotepadRef());
    }
}
```

### Step 4：更新 E2E 脚本

`scripts/run_demo_e2e.sh` 添加两轮调用：

```bash
# Round 1
RESPONSE1=$(curl -s -X POST .../devos/start -d '{"slackThreadId":"T-DEMO","text":"step1"}')
ACTION_ID_1=$(echo $RESPONSE1 | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['actionId'])")

# 等待 Action 1 完成...

# Round 2 — 携带 prevActionId
curl -s -X POST .../devos/start -d "{\"slackThreadId\":\"T-DEMO\",\"text\":\"step2\",\"prevActionId\":$ACTION_ID_1}"
```

### Step 5：验证

```bash
# 本地验证
cd /Users/zxydediannao/slack-dev-os-mvp
mvn test -Dspring.profiles.active=local -Dtest=DevOsContextRestoreTest
```

---

## 验收标准

- [ ] `DevOsContextRestoreTest.testNotepadPropagatesAcrossSequentialActions` PASS
- [ ] `DevOsContextRestoreTest.testNotepadIsolatedAcrossThreads` PASS
- [ ] `mvn test` 全量 84+ tests PASS
- [ ] DEMO_MODE E2E 两轮调用，第二轮 result 含 `[Notepad context was present]`
- [ ] CI `ci.yml` 绿色

---

## 依赖

- 无外部依赖，Stage 2 完全在 H2 + Redis 本地环境可验证
- DEMO_MODE=true 即可，无需真实 LLM key

---

## 参考文件

| 文件 | 说明 |
|------|------|
| `src/main/java/com/asyncaiflow/service/DevOsService.java` | 系统调用入口，需添加 prevActionId 逻辑 |
| `src/main/java/com/asyncaiflow/service/ActionService.java` | extractNotepadFromResult() |
| `src/main/java/com/asyncaiflow/web/dto/DevOsStartRequest.java` | 需添加 prevActionId 字段 |
| `python-workers/devos_chat_worker/worker.py` | Context Restore 注入逻辑（已实现） |
| `docs/AUTOPILOT_SOURCE_INDEX.md` | ID 7, 16, 18 |
| `docs/BACKLOG.md` | B-001 |
