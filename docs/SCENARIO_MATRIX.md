# Slack Dev OS — Scenario Matrix & Roadmap

This document maps the OS kernel concepts implemented in **Slack Dev OS** to their test scenarios, validation criteria, and implementation stages.

---

## Kernel Architecture Mapping

| OS Concept | Slack Dev OS Component | Status |
|---|---|---|
| Syscall entry | `POST /devos/start` | ✅ Stage 0 |
| Process Control Block (PCB) | `ActionEntity` (slack_thread_id, notepad_ref, retry) | ✅ Stage 0 |
| Capability dispatch / ready queue | Redis capability queue + `ActionQueueService` | ✅ Stage 0 |
| Instruction cycle | Worker poll → execute → result submit | ✅ Stage 0 |
| Working memory / register snapshot | `notepad_ref` (CLOB in ActionEntity) | ✅ Stage 0 |
| Context restore on retry | notepad_ref injection into LLM system prompt | ✅ Stage 0 |
| Context switch (CI proof) | GitHub Actions E2E workflow | ✅ Stage 1 |
| Fault tolerance / watchdog | lease expiry, RETRY_WAIT, DEAD_LETTER | ⏳ Stage 3 |
| Page fault / disk access | Repo file search worker tool | ⏳ Stage 4 |
| Single-writer mutex | Git branch workspace_snapshot + Redis SETNX | ⏳ Stage 5 |
| Real syscall (Slack + LLM) | Slack slash command + chat.postMessage | ⏳ Stage 6 |

---

## Stage 0 — MVP Kernel (COMPLETED)

**Goal**: Prove the full instruction cycle works end-to-end in DEMO_MODE (no real keys).

### Scenario 0.1 — Kernel Syscall Round-Trip

| Step | Component | Expected |
|---|---|---|
| `POST /devos/start` | `DevOsController` → `DevOsService` | `{actionId, status:"QUEUED"}` |
| Action PCB created | `ActionEntity` | `slack_thread_id` + `notepad_ref=null` saved |
| Capability enqueue | `ActionQueueService.enqueue("devos_chat")` | Action visible in Redis queue |
| Worker polls | `GET /action/poll?workerId=X` | `ActionAssignmentResponse` returned |
| DEMO stub executes | `call_llm()` with `DEMO_MODE=true` | `[DEMO] response` string returned |
| Result submitted | `POST /action/result` with `{response, notepad}` | Action status → SUCCEEDED |
| Notepad persisted | `ActionService.extractNotepadFromResult()` | `notepad_ref` written to `ActionEntity` |
| Status query | `GET /action/{actionId}` | `{status:"COMPLETED", result:{response, notepad}}` |

**Validation**: ✅ Local E2E confirmed. 84 tests, 0 failures, BUILD SUCCESS.

### Scenario 0.2 — Context Restore on Retry

| Step | Expected |
|---|---|
| Worker receives action with existing `notepad_ref` | `notepad_ref` injected into LLM system prompt |
| LLM (or DEMO stub) receives prior context | Response references prior conversation state |
| Submitted notepad updated | Reflects merged context |

**Validation**: ✅ Code implemented. Test coverage via existing integration tests.

---

## Stage 1 — CI Proof (COMPLETED)

**Goal**: Make Stage 0 reproducibly verifiable on GitHub Actions without any real keys or external services.

### Scenario 1.1 — Maven Test Suite (CI)

- **Trigger**: Push / PR to `main`
- **Workflow**: `.github/workflows/ci.yml`
- **Environment**: ubuntu-latest, Java 21, Redis 7 (service), H2 in-memory
- **Command**: `mvn test -Dspring.profiles.active=local`
- **Pass Criteria**: 84 tests, 0 failures, BUILD SUCCESS

### Scenario 1.2 — DEMO E2E Instruction Cycle (CI)

- **Trigger**: Push / PR to `main`, `workflow_dispatch`
- **Workflow**: `.github/workflows/devos-demo-e2e.yml`
- **Environment**: ubuntu-latest, Java 21, Python 3.11, Redis 7 (service)
- **Steps**:
  1. Build backend (mvn package -DskipTests)
  2. Start backend (local profile — H2 + Redis)
  3. Wait for `GET /health` → 200
  4. Start `devos_chat_worker` with `DEMO_MODE=true`
  5. `POST /devos/start` → extract `actionId`
  6. Poll `GET /action/{actionId}` until `status == COMPLETED` (max 120s)
  7. Assert `result.response` contains `[DEMO]`
  8. Assert `result.notepad` is non-empty
- **Pass Criteria**: All assertions pass, exit code 0

---

## Stage 2 — Context Restore Under Load (COMPLETED)

**Goal**: Validate PCB context restore across multiple sequential instruction cycles.

**Validation**: ✅ Local E2E two-round PASSED. 3 integration tests all PASS. 87 tests, BUILD SUCCESS.

### Scenario 2.1 — Sequential Notepad Propagation (prevActionId)

| Step | Expected |
|---|---|
| Action 1 completes with notepad | `notepad_ref` saved to ActionEntity via `extractNotepadFromResult()` |
| POST /devos/start with `prevActionId=Action1.id` | `DevOsService.resolveNotepadRef()` reads Action 1 notepad_ref, writes to new PCB |
| Worker polls Action 2 | `ActionAssignmentResponse.notepadRef` == Action 1's notepad |
| `call_llm()` with notepad (retry=0) | Context Restore injected into LLM prompt (retry guard removed) |
| DEMO stub response | Contains `[Notepad context was present]` |

**Test**: `DevOsContextRestoreTest.testNotepadPropagatesAcrossSequentialActions` ✅

### Scenario 2.2 — Context Switch Isolation

| Step | Expected |
|---|---|
| Two concurrent slackThreadIds active | Each PCB carries its own `notepad_ref` |
| T-BETA started with no prevActionId | `notepadRef == null` in poll response |
| T-ALPHA notepad unaffected after T-BETA completes | No cross-contamination |

**Test**: `DevOsContextRestoreTest.testNotepadIsolatedAcrossThreads` ✅

### Scenario 2.3 — prevActionId Not Found Fallback

| Step | Expected |
|---|---|
| POST /devos/start with `prevActionId=999999999` (non-existent) | `resolveNotepadRef()` returns null gracefully |
| New Action PCB | `notepad_ref = null`, no error |

**Test**: `DevOsContextRestoreTest.testPrevActionIdNotFoundFallsBackToNull` ✅

### GHA Workflow Update

- `.github/workflows/devos-demo-e2e.yml` extended with Round 2 (Context Restore) steps
- Worker started with `env -u OPENAI_API_KEY -u GLM_API_KEY ... DEMO_MODE=true` to ensure DEMO_MODE priority
- DEMO_MODE now takes priority over LLM keys in `call_llm()` (worker.py fix)

---

## Stage 3 — Fault Tolerance & Watchdog (PLANNED)

**Goal**: Validate kernel-level fault handling — lease expiry, dead letters, doom-loop prevention.

### Scenario 3.1 — Worker Crash / Lease Expiry

| Step | Expected |
|---|---|
| Worker polls and acquires action (status → RUNNING) | `leaseExpireAt` set |
| Worker crashes before submitting result | No result submitted |
| Watchdog detects expired lease | Action returned to QUEUED for retry |
| Retry count incremented | `retryCount <= maxRetryCount` checked |

### Scenario 3.2 — Dead Letter After Max Retries

| Step | Expected |
|---|---|
| Action fails `maxRetryCount` times | Status transitions to FAILED / DEAD_LETTER |
| No further retries attempted | doom-loop prevented |

### Scenario 3.3 — RETRY_WAIT Backoff

| Step | Expected |
|---|---|
| Retried action waits backoff duration | `scheduledAt` is in the future |
| Worker cannot claim action before backoff expires | Action not polled prematurely |

---

## Stage 4 — Disk / Page Fault Simulation (PLANNED)

**Goal**: Validate that the kernel can dispatch file-reading workers (analogous to page fault handlers).

### Scenario 4.1 — Repository File Search

| Step | Expected |
|---|---|
| `POST /devos/start` with intent requiring file lookup | Planner creates `read_file` or `search_code` action |
| Repository worker executes | Target file content retrieved |
| Result returned to devos_chat worker | LLM includes file context in response |

---

## Stage 5 — Single-Writer Mutex (PLANNED)

**Goal**: Prevent concurrent writes to the same workspace branch (analogous to mutex/lock).

### Scenario 5.1 — Git Branch Workspace Snapshot

| Step | Expected |
|---|---|
| Worker acquires Redis SETNX lock for `workspace:<repoId>` | Lock granted |
| Second worker attempts same lock | Lock denied, worker waits or aborts |
| First worker commits and releases lock | Second worker can now acquire |

---

## Stage 6 — Real Slack + LLM Integration (PLANNED)

**Goal**: Full production-mode instruction cycle with real Slack bot and LLM.

### Scenario 6.1 — Slack Slash Command

| Step | Expected |
|---|---|
| User sends `/devos How do I reset a build?` in Slack | Slack delivers payload to backend |
| `POST /devos/start` triggered | Action PCB created |
| LLM processes with real key (GLM or OpenAI) | Real response generated |
| `chat.postMessage` to Slack thread | User sees response in thread |

### Scenario 6.2 — Multi-Turn Conversation

| Step | Expected |
|---|---|
| User follows up in same thread | `slackThreadId` matches previous action |
| `notepad_ref` injected | LLM has prior context |
| Response references earlier turns | Context restore validated |

---

## Status Summary

| Stage | Name | Status | CI Proof |
|---|---|---|---|
| 0 | MVP Kernel | ✅ Complete | Local E2E |
| 1 | CI Proof | ✅ Complete | GitHub Actions |
| 2 | Context Restore Under Load | ⏳ Planned | — |
| 3 | Fault Tolerance & Watchdog | ⏳ Planned | — |
| 4 | Disk / Page Fault Simulation | ⏳ Planned | — |
| 5 | Single-Writer Mutex | ⏳ Planned | — |
| 6 | Real Slack + LLM | ⏳ Planned | — |
