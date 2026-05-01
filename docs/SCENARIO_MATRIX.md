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
| Fault tolerance / watchdog | lease expiry, RETRY_WAIT, DEAD_LETTER | ✅ Stage 3 |
| Page fault / disk access | Repo file search worker tool | ✅ Stage 4 |
| Single-writer mutex | Git branch workspace_snapshot + Redis SETNX | ✅ Stage 5 |
| Tool Manager / Tool Call protocol | `ToolManager` whitelist + `repo.read_file` | ✅ Stage 6 |
| Access Control / Ownership Guard | `slackThreadId` scope boundary; cross-thread 403 | ✅ Stage 6 |
| Real syscall (Slack + LLM) | Slack slash command + chat.postMessage | 🔶 Stage 6 (config ready, not live) |

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

## Stage 3 — Fault Tolerance & Watchdog (COMPLETED)

**Goal**: Validate kernel-level fault handling — lease expiry, dead letters, doom-loop prevention, and user interrupt.

**Validation**: ✅ 98 tests, 0 failures, BUILD SUCCESS. Stage 3 complete: B-002 (Watchdog), B-003 (Interrupt), B-004 (DAG Acyclicity).

### Scenario 3.1 — Worker Crash / Lease Expiry ✅

| Step | Expected |
|---|---|
| Worker polls and acquires action (status → RUNNING) | `leaseExpireAt` set |
| Worker crashes before submitting result | No result submitted |
| Watchdog detects expired lease | Action returned to QUEUED for retry |
| Retry count incremented | `retryCount <= maxRetryCount` checked |

**Tests**: `DevOsWatchdogLeaseTest.testLeaseExpiredTransitionsToRetryWaitWithCorrectFields` ✅

### Scenario 3.2 — Dead Letter After Max Retries ✅

| Step | Expected |
|---|---|
| Action fails `maxRetryCount` times | Status transitions to DEAD_LETTER |
| No further retries attempted | doom-loop prevented |

**Tests**: `DevOsWatchdogLeaseTest.testMaxRetriesExhaustedTransitionsToDeadLetter` ✅

### Scenario 3.3 — RETRY_WAIT Backoff ✅

| Step | Expected |
|---|---|
| Retried action waits backoff duration | `nextRunAt` is in the future |
| enqueueDueRetries() fires after backoff | Action → QUEUED → re-pollable |

**Tests**: `DevOsWatchdogLeaseTest.testRetryWaitReturnsToQueueAfterBackoffAndCanBePolled` ✅

### Scenario 3.4 — DAG Acyclicity Guarantee (B-004) ✅

| Aspect | Invariant | Validation |
|---|---|---|
| Linear chain A→B→C | Creates correctly; B/C BLOCKED; unlock in order | `testLinearChainBlocksAndUnlocksInOrder` ✅ |
| Direct reverse cycle (A→B, add B→A) | `wouldCreateCycle(A,[B])=true` | `testDirectReverseCycleDetected` ✅ |
| Indirect 3-node cycle (A→B→C, add C→A) | `wouldCreateCycle(A,[C])=true`; self-loop detected | `testIndirectThreeNodeCycleDetected` ✅ |
| Rejected createAction — no residue | ApiException + transaction rollback → 0 new rows | `testCreateActionRejectsNonexistentUpstreamAndLeavesNoResidue` ✅ |

**Implementation**: `ActionService.wouldCreateCycle(Long downstreamId, List<Long> proposedUpstreamIds)` — BFS from downstreamId via existing downstream edges; if any reachable node equals a proposed upstream, cycle detected. Called defensively in `createAction` after action insert. Self-loop check included.

### Scenario 3.5 — User Interrupt Signal (B-003) ✅

| Scenario | Input State | Expected Outcome | Invariant |
|---|---|---|---|
| RUNNING action interrupted | Worker has lease, action RUNNING | status=FAILED, lastReclaimReason=USER_INTERRUPTED, lock released | Worker can no longer submit result for this action |
| QUEUED action interrupted | Action in Redis queue, status QUEUED | status=FAILED; subsequent pollAction returns empty | pollAction checks DB status and skips FAILED |
| RETRY_WAIT action interrupted | Backoff not yet expired, status RETRY_WAIT | status=FAILED; enqueueDueRetries returns 0 | enqueueDueRetries checks RETRY_WAIT status and skips FAILED |
| Terminal action interrupt attempt | status SUCCEEDED or DEAD_LETTER | ApiException 409 CONFLICT; status unchanged | Terminal states are immutable |

**Implementation**:
- `POST /devos/interrupt` → `DevOsController.interrupt()` → `DevOsService.interrupt()` → `ActionService.interruptAction()`
- `ActionService.interruptAction(Long actionId, String reason)`: loads PCB, rejects terminal, transitions to FAILED, sets `errorMessage="USER_INTERRUPTED: <reason>"`, `lastReclaimReason="USER_INTERRUPTED"`, releases lock
- `isValidTransition` extended: QUEUED→FAILED, RETRY_WAIT→FAILED, BLOCKED→FAILED now valid

**Tests**: `DevOsInterruptTest` (4 tests)
- `testRunningActionCanBeInterrupted` ✅
- `testQueuedActionCanBeInterrupted` ✅
- `testRetryWaitActionCanBeInterrupted` ✅
- `testTerminalActionsCannotBeInterrupted` ✅

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

## Stage 4 — Page Fault / Repository File Retrieval (COMPLETED)

**Goal**: Prove the minimal Disk / Page Fault capability: worker reads a repo file and injects content as page-in context.

**Validation**: ✅ 100 tests, 0 failures, BUILD SUCCESS. Page Fault E2E PASSED. B-005 complete.

### Scenario 4.1 — Page Fault payload 传递 ✅

| Aspect | Invariant | Validation |
|---|---|---|
| `repoPath` + `filePath` written to Action payload | `payload` JSON contains `repo_path` and `file_path` | `testPayloadContainsRepoPathAndFilePath` ✅ |
| Requests without repoPath/filePath unaffected | `payload` does NOT contain `repo_path` or `file_path` | `testPayloadWithoutRepoPathHasNoRepoFields` ✅ |

**Implementation**: `DevOsStartRequest` extended with `repoPath` + `filePath` optional fields. `DevOsService.buildPayload()` writes them to JSON payload when non-blank.

### Scenario 4.2 — safe_read_repo_file security invariants ✅

| Security Check | Input | Expected |
|---|---|---|
| Absolute file_path rejected | `file_path="/etc/passwd"` | `{ok:false, error:"file_path must be relative"}` |
| Path traversal rejected | `file_path="../../etc/passwd"` | `{ok:false, error:"must not contain '..'"}` |
| Symlink escape prevented | Resolved path outside repo_path | `{ok:false, error:"escapes repo boundary"}` |
| Non-existent file | `file_path="missing.txt"` | `{ok:false, error:"not a regular file"}` |
| Valid file read | `file_path="README.md"` in fixture repo | `{ok:true, content:"# DevOS..."}` |

**Implementation**: `worker.py safe_read_repo_file(repo_path, file_path, max_bytes=32768)`: 4-step security validation chain before file open.

### Scenario 4.3 — Worker Page-In Response ✅

| Scenario | Input | Expected Outcome |
|---|---|---|
| DEMO_MODE + valid repoPath+filePath | `repo_path=/tmp/devos-fixture-repo, file_path=README.md` | response contains `[PAGE_IN] Loaded file: README.md` |
| DEMO_MODE + valid repoPath+filePath | same | notepad contains `[page-in:README.md] loaded from ...` |
| DEMO_MODE without repoPath/filePath | normal request | response contains `[DEMO]`, no `[PAGE_IN]` — backward compatible |
| File not found | non-existent file | response contains `[PAGE_IN: FILE NOT FOUND]` |

**Implementation**: `call_llm(user_text, notepad, payload=None)` — DEMO_MODE checks `payload.get("repo_path")` and `payload.get("file_path")`; calls `safe_read_repo_file`; injects `[PAGE_IN]` excerpt. `execute()` builds `[page-in:filePath]` notepad entry.

---

## Stage 5 — Single-Writer Mutex (COMPLETED)

**Goal**: Prevent concurrent writes to the same workspace branch (analogous to mutex/lock).

**Validation**: ✅ 104 tests, 0 failures, BUILD SUCCESS. B-006 complete.

### Scenario 5.1 — Write Intent Blocking ✅

| Aspect | Invariant | Validation |
|---|---|---|
| First write-intent action acquires workspace lock | `tryAcquireWorkspaceLock` returns true | `testWriteIntentBlocksSecondWriter` ✅ |
| Second write-intent action is re-queued | Lock contention → action re-enqueued, not double-executed | `testWriteIntentBlocksSecondWriter` ✅ |
| Read-only actions bypass mutex | `writeIntent=false` → no lock attempted | `testReadOnlyActionsBypassMutex` ✅ |

### Scenario 5.2 — Lock Release on Completion / Interrupt ✅

| Aspect | Invariant | Validation |
|---|---|---|
| SUCCEEDED releases workspace lock | Next writer can now acquire | `testLockReleasedOnSucceeded` ✅ |
| Interrupt releases workspace lock | `interruptAction` calls `releaseWorkspaceLockIfApplicable` | `testLockReleasedOnInterrupt` ✅ |

**Implementation**: `ActionQueueService.tryAcquireWorkspaceLock/releaseWorkspaceLock` Redis SETNX; `ActionService.pollAction` workspace check; `DevOsStartRequest.writeIntent/workspaceKey`; `DevOsWorkspaceMutexTest` (4 tests).

---

## Stage 6 — Tool Manager (COMPLETED)

**Goal**: Minimal tool call protocol: ToolManager whitelist, repo.read_file, B-008.

---

## Stage 6 — Access Control / Ownership Guard (COMPLETED)

**Goal**: slackThreadId as MVP resource scope boundary. Cross-thread operations rejected with 403 FORBIDDEN.

### Scenario 6.2 — Context Restore Ownership Check

| Step | Component | Expected |
|---|---|---|
| `POST /devos/start` with `prevActionId` same thread | `DevOsService.resolveNotepadRef()` | notepad_ref inherited |
| `POST /devos/start` with `prevActionId` different thread | `DevOsService.resolveNotepadRef()` | 403 FORBIDDEN, no notepad leak |
| Transaction rolls back on 403 | `@Transactional start()` | No orphan action created |

**Validation**: `DevOsAccessControlTest.testSameThreadNotepadInheritanceAllowed` + `testCrossThreadNotepadInheritanceRejected`

### Scenario 6.3 — Interrupt Ownership Check

| Step | Component | Expected |
|---|---|---|
| `POST /devos/interrupt` same thread | `DevOsService.interrupt()` | Action transitions to FAILED |
| `POST /devos/interrupt` different thread | `DevOsService.interrupt()` | 403 FORBIDDEN, action unchanged |
| Target action status after rejection | `ActionEntity` | Remains QUEUED |

**Validation**: `DevOsAccessControlTest.testSameThreadInterruptAllowed` + `testCrossThreadInterruptRejected`

**Data flow**:
```
/devos/interrupt {actionId, slackThreadId}
  → DevOsService.interrupt()
    → actionMapper.selectById(actionId)
    → if target.slackThreadId != request.slackThreadId → throw 403
    → else → actionService.interruptAction() → FAILED

/devos/start {prevActionId, slackThreadId}
  → DevOsService.start() [@Transactional]
    → DevOsService.resolveNotepadRef(prevActionId, slackThreadId)
      → if prev.slackThreadId != request.slackThreadId → throw 403 (tx rollback)
      → else → return prev.notepadRef
```

**Goal**: Consolidate the scattered Page Fault file-read capability into a minimal Tool Manager / Tool Call protocol.

**Validation**: ✅ 104 Java tests + 7 Python smoke tests, 0 failures. B-008 complete.

### Scenario 6.1 — ToolManager Whitelist Enforcement ✅

| Aspect | Invariant | Validation |
|---|---|---|
| Non-whitelisted tool registration rejected | `ToolManager.register("shell.exec", ...)` raises `ValueError` | `test_register_non_whitelisted_tool_raises_value_error` ✅ |
| Unknown tool execute returns ok=False | No exception raised; caller handles gracefully | `test_execute_unknown_tool_returns_ok_false` ✅ |
| Execute always returns ToolResponse | Never raises | `test_execute_unknown_tool_does_not_raise` ✅ |

### Scenario 6.2 — repo.read_file Tool (Security Boundary) ✅

| Security Check | Input | Expected |
|---|---|---|
| Valid file read | `file_path="hello.md"` in fixture repo | `ok=True`, content present |
| Path traversal rejected | `file_path="../etc/passwd"` | `ok=False`, error non-empty |
| Absolute path rejected | `file_path="/etc/passwd"` | `ok=False`, error non-empty |
| Non-existent file | `file_path="ghost.txt"` | `ok=False`, error non-empty |

**Implementation**: `ToolCall` / `ToolResponse` dataclasses + `ToolManager` class in `worker.py`; WHITELIST `{"repo.read_file"}`; `_repo_read_file_handler` wraps `safe_read_repo_file()`; `TOOL_MANAGER` module-level singleton; `call_llm()` Page Fault path uses `TOOL_MANAGER.execute()` instead of direct call; `[PAGE_IN]` response marker preserved (E2E backward compatible).

**Tests**: `python-workers/devos_chat_worker/test_tool_manager.py` (7 pytest cases). CI `devos-demo-e2e.yml` runs smoke tests before worker start.

---

## Stage 6 — Production Config Readiness / B-010 (PARTIAL)

**Goal**: Enable safe local switch to production-like mode: real Slack Bot Token / GLM / OpenAI key via env; CI and default local tests always use DEMO_MODE, no real secrets required.

**What was done (config boundary, not live integration)**:
- `select_llm_backend()`: DEMO → GLM → OpenAI → RuntimeError (fail fast)
- `validate_runtime_config()`: startup config summary with redacted secrets
- `redact_secret()`: first 4 chars + `***`, prevents key leak in logs
- `REQUIRE_SLACK_POST` flag: false (skip) or true (fail fast on missing token)
- `main()` logs config on start, raises RuntimeError if no LLM backend
- `.env.example`: complete template with DEMO_MODE=true default + comments
- `scripts/run_production_config_check.sh`: dry-run config validator (exit 0/1)

**Not done (intentional)**:
- No real Slack API calls in CI
- No real LLM calls in CI
- No OAuth, no public webhook, no deployment

### Scenario 6.4 — LLM Backend Selection

| Input | Expected Backend |
|---|---|
| `DEMO_MODE=true` (any keys) | `demo` |
| `DEMO_MODE=false` + `GLM_API_KEY` | `glm` |
| `DEMO_MODE=false` + `OPENAI_API_KEY` | `openai` |
| `DEMO_MODE=false` + no key | `RuntimeError` (fail fast) |
| `GLM_API_KEY` + `OPENAI_API_KEY` | `glm` (GLM has higher priority) |

### Scenario 6.5 — Secret Safety

| Check | Invariant |
|---|---|
| `redact_secret("sk-abc123")` | Returns `sk-a***`, never full key |
| `SLACK_BOT_TOKEN` missing, `REQUIRE_SLACK_POST=false` | `post_to_slack()` returns `False`, no exception |
| `SLACK_BOT_TOKEN` missing, `REQUIRE_SLACK_POST=true` | `post_to_slack()` raises `RuntimeError` |
| CI / E2E scripts | Always `DEMO_MODE=true`, always `unset OPENAI_API_KEY GLM_API_KEY` |

**Tests**: `python-workers/devos_chat_worker/test_runtime_config.py` (14 pytest cases: A–F + config summary).

**Config check script**: `scripts/run_production_config_check.sh` — exit 0 on DEMO_MODE, exit 1 on missing key.

---

## Stage 7 — Dry-Run Coding / Patch Preview Executor (COMPLETED)

**Goal**: Devin-like dry-run coding capability: worker creates an isolated workspace copy, applies deterministic text replacement, generates a unified diff, and returns a `[PATCH_PREVIEW]` response — original repo file is **never** modified.

**Validation**: ✅ 108 Java + 37 Python tests, 0 failures. `test_patch_preview.py` (10 tests). `run_patch_preview_e2e.sh` PASSED. Original file invariant held. B-017 complete.

### Scenario 7.1 — Patch Preview with Deterministic Replace ✅

| Step | Component | Expected |
|---|---|---|
| `POST /devos/start` with `mode=patch_preview`, `replaceFrom`, `replaceTo` | `DevOsController` → `DevOsService` | `{actionId, status:"QUEUED"}` |
| Payload includes `mode`, `replace_from`, `replace_to` | `DevOsService.buildPayload()` | JSON payload contains all 3 new fields |
| Worker routes to patch preview path | `execute()` checks `mode == "patch_preview"` | Calls `execute_patch_preview()` |
| Workspace copy created | `repo.create_workspace_copy` tool | `/tmp/devos-workspaces/<actionId>/README.md` created |
| Replacement applied in workspace copy | `repo.replace_in_file_preview` tool | Workspace file modified; original file unchanged |
| Unified diff generated | `repo.diff_workspace` tool | `difflib.unified_diff` output |
| Response posted to Slack | `post_to_slack()` | Contains `[PATCH_PREVIEW]` + diff excerpt |
| **INVARIANT** | Original `repo_path/file_path` file | `"Hello Old Title"` still present, no `"Hello Slack Dev OS"` |

**Security invariants**:
- `file_path` must be relative (absolute path → `ok=False`)
- `file_path` must not contain `..` (path traversal → `ok=False`)
- `workspace_file` must be under `_PATCH_WORKSPACE_ROOT=/tmp/devos-workspaces` (boundary enforcement)
- No arbitrary shell command execution
- Workspace directory cleaned up after diff captured

### Scenario 7.2 — Patch Plan Only (No replaceFrom) ✅

| Input | Expected |
|---|---|
| `mode=patch_preview`, `replaceFrom` absent/empty | LLM called with file content + user request |
| DEMO_MODE | `[DEMO PATCH_PLAN_ONLY]` stub returned |
| Non-DEMO_MODE | `[PATCH_PLAN_ONLY]\n<llm plan>` returned |
| Workspace | Created then immediately cleaned up (no patch applied) |

### Scenario 7.3 — Unknown Mode Fallback ✅

| Input | Expected |
|---|---|
| `mode="something_unknown"` | Falls through to normal devos_chat LLM path |
| `mode=null` or absent | Normal path (backward compatible) |

### Scenario 7.4 — Tool Security Boundary (B-017) ✅

| Tool | Security Check | Invariant |
|---|---|---|
| `repo.create_workspace_copy` | `file_path` absolute path | `ok=False, "absolute"` |
| `repo.create_workspace_copy` | `file_path` with `..` | `ok=False, ".."`or `"escape"` |
| `repo.replace_in_file_preview` | `workspace_file` outside workspace root | `ok=False, "outside"` |
| `repo.diff_workspace` | `workspace_file` outside workspace root | `ok=False, "outside"` |
| `repo.replace_in_file_preview` | `replace_from` not in file | `ok=False, "not found"` |

**Tests**: `python-workers/devos_chat_worker/test_patch_preview.py` (10 pytest cases).
**E2E**: `scripts/run_patch_preview_e2e.sh` — creates fixture, posts `patch_preview`, asserts `[PATCH_PREVIEW]` + original file unchanged.

---

## Stage 8 — Human Confirm Apply Patch (COMPLETED)

**Goal**: Close the human-in-the-loop loop. After reviewing the `[PATCH_PREVIEW]` diff, the human sends `POST /devos/apply-patch` with `confirm=true`. The backend applies `replaceFrom→replaceTo` to the **real** repo file — no auto-commit, no auto-push.

**Validation**: ✅ 113 Java + 41 Python tests, 0 failures. `DevOsApplyPatchTest` (5 tests). `run_apply_patch_e2e.sh` available. B-018 complete.

### Scenario 8.1 — Confirm Guard ✅

| Input | Expected |
|---|---|
| `confirm=false` | `400 BAD_REQUEST` — patch unconditionally rejected |
| `confirm=true` | Proceeds to remaining safety checks |

### Scenario 8.2 — Valid Apply → File Modified ✅

| Step | Component | Expected |
|---|---|---|
| `POST /devos/apply-patch` with valid `previewActionId` + same `slackThreadId` + `confirm=true` | `DevOsController` → `DevOsService.applyPatch()` | `{applied:true, status:"APPLIED", filePath:...}` |
| Ownership check | `slackThreadId` matches action's `slackThreadId` | Passes |
| Status check | Action status is `SUCCEEDED` | Passes |
| SHA-256 check | File bytes match `originalSha256` from `patchPreview` metadata | Passes |
| `replaceFrom` present | `file.contains(replaceFrom)` | First occurrence replaced |
| Write | `Files.writeString()` | Real file now contains `replaceTo` |
| No commit | `git status` | File modified but not committed |

### Scenario 8.3 — Cross-Thread Apply Denied ✅

| Input | Expected |
|---|---|
| `previewActionId` belongs to `threadA`, request uses `threadB` | `403 FORBIDDEN` (B-007 ownership guard) |
| Real file | Unchanged |

### Scenario 8.4 — Stale Hash Guard ✅

| Input | Expected |
|---|---|
| File mutated between preview and apply | `409 CONFLICT` — `"hash mismatch"` |
| Real file | Unchanged (write never attempted) |

### Scenario 8.5 — replaceFrom Not Found ✅

| Input | Expected |
|---|---|
| `replaceFrom` text no longer in file (idempotency) | `409 CONFLICT` — `"replaceFrom not found"` |
| Real file | Unchanged |

**Tests**: `src/test/java/com/asyncaiflow/DevOsApplyPatchTest.java` (5 scenarios).
**E2E**: `scripts/run_apply_patch_e2e.sh` — fixture → patch_preview → assert unchanged → apply-patch → assert replaced + no commit.

---

## Stage 9 — Test Command Runner / Post-Apply Verification (COMPLETED)

**Goal**: After applying a patch, the human can trigger a test run to verify the code is still healthy. `POST /devos/run-test` executes a single allowlisted command inside `repoPath` using `ProcessBuilder` (no shell). Test failure → `FAILED` with HTTP 200. No auto-fix, no auto-commit.

**Validation**: ✅ 122 Java + 41 Python tests, 0 failures. `DevOsRunTestTest` (9 tests). `run_test_runner_e2e.sh` available. B-019 complete.

### Scenario 9.1 — Allowed Command → PASSED ✅

| Input | Expected |
|---|---|
| `command="bash scripts/secret_scan.sh"`, clean repo | `{status:"PASSED", exitCode:0}` |
| `durationMs` | Non-negative |
| `stdoutExcerpt` | Non-null string |

### Scenario 9.2 — Disallowed Command → 400 ✅

| Input | Expected |
|---|---|
| `command="rm -rf /tmp/evil"` | `400 BAD_REQUEST` — error mentions "allowlist" |
| `command="bash scripts/secret_scan.sh; rm -rf /"` | `400 BAD_REQUEST` — injection rejected by exact key match |
| `command="ls -la"` | `400 BAD_REQUEST` — not in allowlist |

### Scenario 9.3 — Invalid repoPath → 400 ✅

| Input | Expected |
|---|---|
| `repoPath="/no/such/path"` | `400 BAD_REQUEST` |
| `repoPath` points at a file (not directory) | `400 BAD_REQUEST` |

### Scenario 9.4 — Command Fails → FAILED Not 500 ✅

| Input | Expected |
|---|---|
| Fixture repo with `scripts/secret_scan.sh` returning `exit 1` | `{status:"FAILED", exitCode:1}` |
| HTTP status | 200 (not 500 — test failure is a business result) |
| `success` field | `true` |

### Scenario 9.5 — Timeout Clamp ✅

| Input | Expected |
|---|---|
| `timeoutSeconds=999` | Clamped to 180, command still runs normally |
| `timeoutSeconds=null` | Defaults to 120s |

**Tests**: `src/test/java/com/asyncaiflow/DevOsRunTestTest.java` (9 scenarios).
**E2E**: `scripts/run_test_runner_e2e.sh` — 5 assertions: PASSED, disallowed→400, bad-path→400, fixture-fail→FAILED, timeout-clamp.

---

## Stage 10 — One-step Fix Loop / Test Failure → Fix Proposal (COMPLETED)

**Goal**: Close the test–fix feedback cycle by queuing a read-only fix-plan action when tests fail.

**Validation**: ✅ 129 Java + 54 Python tests, 0 failures. `DevOsProposeFixTest` (8 tests). `test_fix_preview.py` (13 tests). `run_fix_loop_e2e.sh` (9 assertions). B-020 complete.

### Scenario 10.1 — Valid propose-fix request → QUEUED action with mode=fix_preview ✅

**Given**: Backend running, valid `repoPath`, `filePath=README.md`, `testStatus=FAILED`, `exitCode=1`\
**When**: `POST /devos/propose-fix`\
**Then**: HTTP 200, `status=QUEUED`, `actionId` set, `payload.mode=fix_preview`, `failure_context` embedded

### Scenario 10.2 — stdout/stderr truncated at 8000 chars ✅

**Given**: `stdoutExcerpt` = 15000 chars, `stderrExcerpt` = 12000 chars\
**When**: `POST /devos/propose-fix`\
**Then**: Stored payload fields `≤8000` chars; content starts with original prefix

### Scenario 10.3 — hint truncated at 2000 chars ✅

**Given**: `hint` = 5000 chars\
**When**: `POST /devos/propose-fix`\
**Then**: Stored `failure_context.hint` `≤2000` chars

### Scenario 10.4 — DEMO_MODE worker returns [FIX_PLAN_ONLY] stub ✅

**Given**: `DEMO_MODE=true`, valid payload with `mode=fix_preview`\
**When**: `execute_fix_preview()` called\
**Then**: `status=SUCCEEDED`, `response` contains `[FIX_PLAN_ONLY]`, original file unchanged

### Scenario 10.5 — Unsafe file path rejected ✅

**Given**: `file_path` is absolute path or `../../etc/passwd`\
**When**: `execute_fix_preview()` called\
**Then**: `status=FAILED`, no file read performed, no mutation

**E2E**: `scripts/run_fix_loop_e2e.sh` — 9 assertions: propose-fix→QUEUED, status=QUEUED, thread echoed, message has actionId, payload.mode verified, README unchanged, missing fields→400×2, secret scan PASS.

---

## Stage 11: Human Git Commit Snapshot (B-021) ✅

**Capability**: `POST /devos/git-commit` — Human-confirmed local-only git commit with no push, no remote modification, and no global git config writes.

**Validation**: ✅ 139 Java + 54 Python tests, 0 failures. `DevOsGitCommitTest` (10 tests). `run_git_commit_e2e.sh` (9 assertions). B-021 complete.

### Scenario 11.1 — confirm=false → 400, no commit executed ✅

**Given**: Valid git repo with pending changes, `confirm=false`\
**When**: `POST /devos/git-commit`\
**Then**: HTTP 400, `confirm` mentioned in error, git commit count unchanged

### Scenario 11.2 — Non-git directory → 400 ✅

**Given**: `repoPath` is a regular directory (no `.git`)\
**When**: `POST /devos/git-commit` with `confirm=true`\
**Then**: HTTP 400, error mentions git repository

### Scenario 11.3 — Clean working tree → NO_CHANGES (HTTP 200) ✅

**Given**: Git repo with no uncommitted changes, `confirm=true`\
**When**: `POST /devos/git-commit`\
**Then**: HTTP 200, `status=NO_CHANGES`, `commitHash=null`, `changedFiles=[]`, no commit added

### Scenario 11.4 — Changed file + confirm=true → COMMITTED ✅

**Given**: Git repo with modified `README.md`, `confirm=true`, valid message\
**When**: `POST /devos/git-commit`\
**Then**: HTTP 200, `status=COMMITTED`, `commitHash` is 40-char SHA-1, `changedFiles` includes `README.md`, `git rev-list --count HEAD == 2`

### Scenario 11.5 — Commit message > 200 chars → 400 ✅

**Given**: `message` is 201 characters, `confirm=true`\
**When**: `POST /devos/git-commit`\
**Then**: HTTP 400, error mentions 200-char limit

### Scenario 11.6 — Non-existent / file repoPath → 400 ✅

**Given**: `repoPath` does not exist, or points to a file (not a directory)\
**When**: `POST /devos/git-commit`\
**Then**: HTTP 400

**E2E**: `scripts/run_git_commit_e2e.sh` — 9 assertions: confirm=false→400+no-commit, COMMITTED+changedFiles, count==2, hash-matches-HEAD, no-remote, NO_CHANGES-idempotent, non-git→400, long-msg→400, secret scan PASS.

---

## Stage 12: Slack Minimal Loop Bridge (B-021.5) ✅

**Goal**: 最小 Slack → DevOS 桥接：`devos: <instruction>` 消息 → `POST /devos/start` → Slack 回帖。本地 dry-run 可运行，不需要 Slack Events API、OAuth、Socket Mode 或公网 tunnel。

**Validation**: ✅ `test_slack_bridge.py` 10 tests (A–J). `scripts/run_slack_bridge_mock.sh` dry-run verified. 139 Java + 64+ Python tests, 0 failures.

### Scenario 12.1 — Non-devos message ignored ✅

| Step | Expected |
|---|---|
| Slack message `"hello world"` | `parse_devos_command` returns `None` |
| `handle_slack_event` called | `handled=False`, `reason` contains "not_devos_command" |
| Backend not called | No HTTP request to `/devos/start` |

### Scenario 12.2 — devos prefix starts action ✅

| Step | Expected |
|---|---|
| Slack message `"devos: summarize this repo"` | `parse_devos_command` returns `"summarize this repo"` |
| `handle_slack_event` calls backend | `POST /devos/start` with `text="summarize this repo"` |
| Backend returns `actionId=42` | `result.handled=True`, `result.actionId=42` |
| Reply text | `"DevOS session started — action 42"` |

### Scenario 12.3 — thread_ts mapping ✅

| Step | Expected |
|---|---|
| Event has `thread_ts="1710000000.000100"`, `ts="1710000000.000200"` | `build_slack_thread_id` uses `thread_ts` |
| Event has no `thread_ts` | `build_slack_thread_id` uses `ts` |
| slackThreadId format | `"channel/ts"` |

### Scenario 12.4 — dry-run no backend ✅

| Step | Expected |
|---|---|
| `DEVOS_BRIDGE_DRY_RUN=true` | `handle_slack_event` does not call backend |
| `handled=True` | Dry-run still counts as handled |
| `replyText` | Contains `[DRY RUN]` and instruction |

### Scenario 12.5 — backend failure safe reply ✅

| Step | Expected |
|---|---|
| Backend returns 5xx or connection error | `RuntimeError` raised in `call_devos_start` |
| `handle_slack_event` catches exception | `handled=True`, `reason="backend error"` |
| `replyText` | `"DevOS failed to start: ..."` (no token leak) |

**Mock script**: `scripts/run_slack_bridge_mock.sh` — constructs mock event, calls `handle_slack_event`, prints JSON result. Default `DEVOS_BRIDGE_DRY_RUN=true`.

---

## Status Summary

> **v0.1.0-rc2 + Stage 12** — All scenarios in scope have been implemented and CI-verified.
> 139 Java tests + 64+ Python tests, 0 failures. Seven E2E scripts green + `run_slack_bridge_mock.sh`. No real secrets in CI.

| Stage | Name | Status | CI Proof |
|---|---|---|---|
| 0 | MVP Kernel | ✅ Complete | Local E2E |
| 1 | CI Proof | ✅ Complete | GitHub Actions |
| 2 | Context Restore Under Load | ✅ Complete | GHA E2E Round 2 |
| 3 | Fault Tolerance & Watchdog | ✅ Complete | 108 tests |
| 4 | Disk / Page Fault Simulation | ✅ Complete | GHA E2E Round 3 |
| 5 | Single-Writer Mutex | ✅ Complete | 108 tests |
| 6 | Tool Manager (minimal) | ✅ Complete | 7 Python smoke tests + GHA |
| 6 | Ownership Guard (B-007) | ✅ Complete | 4 integration tests |
| 6 | Production Config Readiness (B-010) | ✅ Complete | `test_runtime_config.py` (14 tests) + config check script |
| 6 | Real Slack + LLM live | ✅ Complete | Live smoke PASSED 2026-05-01 (C0AV55H69QT) |
| 7 | Dry-Run Coding / Patch Preview | ✅ Complete | `test_patch_preview.py` (10 tests) + `run_patch_preview_e2e.sh` |
| 8 | Human Confirm Apply Patch | ✅ Complete | `DevOsApplyPatchTest` (5 tests) + `run_apply_patch_e2e.sh` |
| 9 | Test Command Runner | ✅ Complete | `DevOsRunTestTest` (9 tests) + `run_test_runner_e2e.sh` |
| 10 | One-step Fix Loop | ✅ Complete | `DevOsProposeFixTest` (8 tests) + `test_fix_preview.py` (13 tests) + `run_fix_loop_e2e.sh` |
| 11 | Human Git Commit Snapshot | ✅ Complete | `DevOsGitCommitTest` (10 tests) + `run_git_commit_e2e.sh` |
| 12 | Slack Minimal Loop Bridge | ✅ Complete | `test_slack_bridge.py` (10 tests) + `run_slack_bridge_mock.sh` |
