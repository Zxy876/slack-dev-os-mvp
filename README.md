# Slack Dev OS MVP

[![CI — Build & Test](https://github.com/Zxy876/slack-dev-os-mvp/actions/workflows/ci.yml/badge.svg)](https://github.com/Zxy876/slack-dev-os-mvp/actions/workflows/ci.yml)
[![Demo E2E](https://github.com/Zxy876/slack-dev-os-mvp/actions/workflows/devos-demo-e2e.yml/badge.svg)](https://github.com/Zxy876/slack-dev-os-mvp/actions/workflows/devos-demo-e2e.yml)

> **v0.1.0-rc1** — Release Candidate. All Stage 0–6 capabilities implemented and verified. 108 Java tests + 21 Python tests, 0 failures. Two E2E scripts green. CI passes on every push.
>
> First-time demo: use `DEMO_MODE=true` (default). No real Slack tokens or LLM keys needed.

## Quickstart

```bash
# 1. Run all Java tests (H2 in-memory, no Docker)
mvn test -Dspring.profiles.active=local

# 2. Run all Python tests
cd python-workers/devos_chat_worker
python3 -m pytest test_tool_manager.py test_runtime_config.py -q
cd ../..

# 3. Demo E2E (local — needs Redis on localhost:6379)
bash scripts/run_demo_e2e.sh

# 4. Page Fault E2E (local — needs Redis on localhost:6379)
bash scripts/run_page_fault_e2e.sh

# 5. Validate production config readiness (no real API calls)
bash scripts/run_production_config_check.sh

# 6. Secret scan
bash scripts/secret_scan.sh
```

## What this is

A minimal working implementation of the **Slack Dev OS** concept, built on top of **AsyncAIFlow 4.8**.

Slack acts as the syscall entry point. AsyncAIFlow acts as the kernel. An `Action` acts as a Process Control Block (PCB). A Python worker acts as the agent execution unit.

```
User → Slack → POST /devos/start
             → create Workflow
             → create Action PCB (slack_thread_id, notepad_ref)
             → enqueue devos_chat capability
             → devos_chat_worker polls action
             → LLM generates response
             → result posted back to Slack thread
             → Action status becomes SUCCEEDED
             → notepad_ref persisted for context restore on retry
```

## MVP Execution Path

```
POST /devos/start
  { "text": "...", "slackThreadId": "C08XXXXXX/1234567890.123456" }

→ DevOsController → DevOsService
→ create Workflow ("devos:<text>")
→ create Action PCB
    type=devos_chat
    slack_thread_id=<channel/thread_ts>
    notepad_ref=null (set on completion)
    maxRetryCount=2, executionTimeoutSeconds=120
→ ActionQueueService.enqueue("devos_chat")

→ devos_chat_worker polls /action/poll
→ extracts user_text + slack_thread_id from payload
→ on retry: injects notepad_ref into LLM system prompt for context restore
→ calls LLM (GLM / OpenAI / DEMO stub)
→ posts response to Slack thread via chat.postMessage
→ submits result: { "response": "...", "notepad": "<context summary>" }
→ ActionService saves notepad_ref to Action PCB
→ Action status → SUCCEEDED
```

## Implemented Features

- `POST /devos/start` — syscall entry point
- Action PCB extensions: `slack_thread_id`, `notepad_ref`
- `devos_chat` capability routing (via `asyncaiflow.dispatch.capability-mapping`)
- `devos_chat` Python Worker (LLM + Slack integration)
- Minimal notepad persistence for context restore on retry
- **Stage 2: Context Restore** — `prevActionId` field propagates notepad across sequential Actions
- **Stage 3: Watchdog / Lease / Retry** — lease expiry reclaim, RETRY_WAIT backoff, DEAD_LETTER doom-loop prevention
- **Stage 3: DAG Acyclicity** — BFS cycle detection rejects directed cycles in action dependency graph
- **Stage 3: User Interrupt** — `POST /devos/interrupt` transitions any active Action to FAILED; terminal actions protected
- **Stage 4: Page Fault** — `repoPath` + `filePath` in `POST /devos/start`; worker safely reads file and injects `[PAGE_IN]` context
- **Stage 5: Workspace Single-Writer Mutex** — `writeIntent` + `workspaceKey` in `POST /devos/start`; Redis SETNX prevents concurrent writes to same repo/workspace
- **Stage 6: Tool Manager** — `ToolCall` / `ToolResponse` / `ToolManager` minimal tool protocol in `worker.py`; whitelist `{repo.read_file}` hard-coded; Page Fault path routes through `TOOL_MANAGER.execute()`; unknown tools return `ok=False` (no exception); 7 Python smoke tests in `test_tool_manager.py`
- **Stage 6: Ownership Guard (B-007)** — `slackThreadId` is the MVP resource scope boundary; `POST /devos/interrupt` requires `slackThreadId` matching the target Action; `POST /devos/start` with `prevActionId` requires same `slackThreadId`; cross-thread operations return 403 FORBIDDEN
- **Stage 6: Production Config Readiness (B-010)** — `select_llm_backend()` (DEMO > GLM > OpenAI > RuntimeError), `validate_runtime_config()`, `redact_secret()`, `REQUIRE_SLACK_POST` flag, dry-run `run_production_config_check.sh`; 14 Python tests in `test_runtime_config.py`. **Live Slack/LLM integration is config-ready but NOT wired in CI** (no real secrets in CI).
- Full test suite passing: **108 Java tests + 21 Python tests (7 tool_manager + 14 runtime_config), 0 failures, BUILD SUCCESS**

## Architecture Overview

| Layer | Component | Role |
|---|---|---|
| User Interface | Slack | Syscall entry point |
| Kernel | AsyncAIFlow 4.8 (Spring Boot) | Action lifecycle + scheduling |
| Process Control Block | `ActionEntity` | State, thread_id, retry, notepad |
| Capability Queue | Redis | Worker dispatch |
| Execution Unit | `devos_chat_worker` (Python) | LLM + Slack response |
| Persistence | MySQL (prod) / H2 (test) | Workflow + Action state |

## Local Run

### Prerequisites

- Java 21
- Maven 3.9+
- Docker (for MySQL + Redis)
- Python 3.10+

### Step 1 — Start MySQL + Redis

```bash
docker compose up -d
```

### Step 2 — Start Java backend

Using H2 in-memory (no Docker needed for testing):
```bash
cd <repo-root>
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Or with MySQL (production mode):
```bash
mvn spring-boot:run
# ensure application.yml datasource points to your MySQL
```

The server starts on **port 8080**.

### Step 3 — Start Python worker

```bash
cd python-workers/devos_chat_worker
cp .env.example .env
# fill in your keys in .env
pip install -r requirements.txt
python worker.py
```

### Step 4 — Send a test request

```bash
curl -X POST http://localhost:8080/devos/start \
  -H "Content-Type: application/json" \
  -d '{"text": "How do I reset a build?", "slackThreadId": "C08XXXXXX/1234567890.123456"}'
```

## Test

```bash
mvn test
```

**Verified result: 108 Java tests, 0 failures, BUILD SUCCESS**

Python tests: 21 total (7 `test_tool_manager.py` + 14 `test_runtime_config.py`), 0 failures.

The test suite runs entirely with H2 in-memory — no MySQL or Redis needed for tests.

## Environment Variables

Set these in `python-workers/devos_chat_worker/.env` (copy from `.env.example`):

```
ASYNCAIFLOW_URL=http://localhost:8080
DEVOS_WORKER_ID=devos-worker-1
OPENAI_API_KEY=
GLM_API_KEY=
SLACK_BOT_TOKEN=
DEMO_MODE=false
```

> **Security**: Never commit real keys. `.env` is in `.gitignore`.

LLM priority: `DEMO_MODE=true` (stub, highest) > `GLM_API_KEY` > `OPENAI_API_KEY`.

## Production Mode (B-010)

CI and all local E2E scripts always use `DEMO_MODE=true` — no real keys required.

To run with a real LLM locally:

```bash
cp python-workers/devos_chat_worker/.env.example python-workers/devos_chat_worker/.env
# Edit .env: set DEMO_MODE=false and fill GLM_API_KEY or OPENAI_API_KEY
# Optionally: set SLACK_BOT_TOKEN for Slack write-back
```

Validate config before starting the worker (no real API calls made):

```bash
bash scripts/run_production_config_check.sh
# Exit 0: config OK (DEMO or real key found)
# Exit 1: DEMO_MODE=false and no LLM key — clear error message
```

**LLM backend selection**:

| Condition | Backend |
|---|---|
| `DEMO_MODE=true` | `demo` (stub, highest priority) |
| `DEMO_MODE=false` + `GLM_API_KEY` | `glm` |
| `DEMO_MODE=false` + `OPENAI_API_KEY` | `openai` |
| `DEMO_MODE=false` + no key | `RuntimeError` (fail fast on startup) |

**Secret safety**:
- Logs always show redacted key (`sk-a***`, `xoxb***`)
- `REQUIRE_SLACK_POST=false` (default): missing token → skip Slack post, Action still SUCCEEDED
- `REQUIRE_SLACK_POST=true`: missing token → RuntimeError
- CI never has real secrets; `DEMO_MODE=true` is always explicitly set in CI/E2E scripts

## Personal Slack Live Smoke (B-010-live-personal)

This is a **local personal mode** only — not production, not CI. It lets you verify the end-to-end data flow with your own Slack workspace and LLM key.

**Data flow:**
```
curl POST /devos/start { text, slackThreadId }
→ AsyncAIFlow creates Action
→ devos_chat_worker polls action
→ calls real LLM (GLM or OpenAI)
→ posts response to your Slack channel/thread
→ Action COMPLETED
```

### Prerequisites

1. **Create a Slack App** at https://api.slack.com/apps  
   Add OAuth scope: `chat:write`  
   Install to your workspace → copy the **Bot User OAuth Token** (`xoxb-...`)

2. **Find your channel/thread ID**  
   - Channel ID: in Slack, open channel details → copy the ID at the bottom (e.g. `C08XXXXXX`)  
   - Thread ID format: `C08XXXXXX/1714500000.123456`  
     Right-click a message → "Copy link" → extract timestamp from URL

3. **LLM key**: `GLM_API_KEY` or `OPENAI_API_KEY` (or use `DEMO_MODE=true` for stub)

### Setup

```bash
# Create your local .env (never committed)
cp python-workers/devos_chat_worker/.env.example python-workers/devos_chat_worker/.env

# Edit .env with your real values:
#   DEMO_MODE=false
#   GLM_API_KEY=<your-key>           # or OPENAI_API_KEY
#   SLACK_BOT_TOKEN=xoxb-...
#   DEVOS_LIVE_SLACK_THREAD_ID=C08XXXXXX/1714500000.123456
#   (or DEVOS_LIVE_SLACK_CHANNEL=C08XXXXXX for top-level post)

source python-workers/devos_chat_worker/.env
```

### Run

```bash
# Terminal 1: start backend (Redis must be running)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 2: start worker
cd python-workers/devos_chat_worker
source .env
python worker.py

# Terminal 3: run live smoke
source python-workers/devos_chat_worker/.env
bash scripts/run_slack_live_smoke.sh
```

After the smoke passes, check your Slack channel — the LLM response should appear.

> **CI never runs live smoke.** CI always uses `DEMO_MODE=true` with no real secrets.  
> **Never commit your `.env` file.** It is in `.gitignore`.

---

## Devin-like Patch Preview / Dry-Run Coding (B-017)

Stage 7 adds a **dry-run coding** capability: the worker can apply a deterministic text replacement on a workspace-isolated copy of a repo file, generate a unified diff, and post the result to Slack — the original file is **never modified**.

**Data flow (with `replaceFrom`/`replaceTo`):**
```
POST /devos/start {
  "text": "Replace Hello Old Title with Hello Slack Dev OS",
  "slackThreadId": "C08XXXXXX",
  "repoPath": "/tmp/devos-patch-fixture",
  "filePath": "README.md",
  "writeIntent": true,
  "mode": "patch_preview",
  "replaceFrom": "Hello Old Title",
  "replaceTo":   "Hello Slack Dev OS"
}
→ Worker: create /tmp/devos-workspaces/<actionId>/README.md (copy)
→ Worker: apply replaceFrom→replaceTo in copy only
→ Worker: generate unified diff (original vs copy)
→ Worker: post [PATCH_PREVIEW] + diff to Slack
→ Worker: clean up workspace
→ INVARIANT: /tmp/devos-patch-fixture/README.md still contains "Hello Old Title"
```

**Without `replaceFrom`** (plan-only mode): the worker calls the LLM with the file content and returns `[PATCH_PLAN_ONLY]` with the LLM's structured patch plan.

**Security guarantees:**
- `file_path` must be relative (no absolute paths, no `..` traversal)
- Workspace files must stay under `/tmp/devos-workspaces/` (boundary enforced)
- No arbitrary shell execution

**Run the E2E proof:**
```bash
# Backend + Redis must already be running (SKIP_BACKEND=1 to reuse)
SKIP_BACKEND=1 bash scripts/run_patch_preview_e2e.sh
```

**Unit tests:** `python-workers/devos_chat_worker/test_patch_preview.py` (10 pytest cases)

---

## Human Confirm Apply Patch (B-018)

Stage 8 closes the human-in-the-loop loop: after reviewing the patch preview diff, the human explicitly confirms the patch. The backend applies `replaceFrom→replaceTo` to the **real** repo file — no auto-commit, no auto-push.

**Data flow:**
```
# 1. After reviewing the [PATCH_PREVIEW] diff, human confirms:
POST /devos/apply-patch {
  "previewActionId": 42,
  "slackThreadId":   "C08XXXXXX/1234567890.123456",
  "confirm":          true
}

# 2. Backend safety checks (in order):
#    a. confirm == true  (else 400 BAD_REQUEST)
#    b. previewActionId belongs to slackThreadId  (else 403 FORBIDDEN)
#    c. action status == SUCCEEDED  (else 409 CONFLICT)
#    d. SHA-256 hash of file matches preview-time hash  (else 409 "hash mismatch")
#    e. replaceFrom exists in file  (else 409 "replaceFrom not found")

# 3. Apply: first-occurrence replace only, write via Files.writeString()
# 4. Return: { applied: true, status: "APPLIED", filePath: "..." }
# NO git commit, NO git push
```

**Safety invariants:**

| Guard | Behaviour |
|---|---|
| `confirm=false` | 400 — rejected unconditionally |
| Cross-thread apply | 403 — slackThreadId ownership enforced (B-007) |
| Action not SUCCEEDED | 409 — only SUCCEEDED previews can be applied |
| Stale hash (file changed since preview) | 409 — SHA-256 mismatch, operation aborted |
| `replaceFrom` not found | 409 — idempotent guard, no partial write |
| No auto-commit/push | By design — human must run `git commit` manually |
| Path traversal | Blocked — `filePath` must be relative, no `..` |

**Run the E2E proof:**
```bash
# Backend + Redis must already be running
SKIP_BACKEND=1 bash scripts/run_apply_patch_e2e.sh
```

**Unit tests:** `src/test/java/com/asyncaiflow/DevOsApplyPatchTest.java` (5 scenarios: confirm=false, valid, cross-thread, stale-hash, replaceFrom-not-found)

---

## Test Command Runner (B-019)

Stage 9 enables post-apply verification: after applying a patch, the human can trigger a test run to verify the code is still healthy. The endpoint executes a single **allowlisted** command inside `repoPath` using `ProcessBuilder` (no shell injection possible).

**Data flow:**
```
POST /devos/run-test {
  "repoPath":       "/path/to/local/repo",
  "slackThreadId":  "C08XXXXXX/1234567890.123456",
  "command":        "bash scripts/secret_scan.sh",
  "timeoutSeconds": 30
}

→ { "status": "PASSED", "exitCode": 0, "durationMs": 1234,
    "stdoutExcerpt": "...", "stderrExcerpt": "...",
    "command": "bash scripts/secret_scan.sh", "repoPath": "..." }
```

**Allowlisted commands:**

| Command | Args (ProcessBuilder) |
|---|---|
| `mvn test -Dspring.profiles.active=local` | `["mvn","test","-Dspring.profiles.active=local"]` |
| `python -m pytest` | `["python3","-m","pytest"]` |
| `bash scripts/secret_scan.sh` | `["bash","scripts/secret_scan.sh"]` |
| `bash scripts/run_patch_preview_e2e.sh` | `["bash","scripts/run_patch_preview_e2e.sh"]` |
| `bash scripts/run_apply_patch_e2e.sh` | `["bash","scripts/run_apply_patch_e2e.sh"]` |

**Safety invariants:**

| Guard | Behaviour |
|---|---|
| Command not in allowlist | 400 BAD_REQUEST — error message mentions "allowlist" |
| Shell injection attempt | 400 — exact key match required; `"; rm -rf /"` suffix never matches |
| `repoPath` not a directory | 400 — validated with `Files.isDirectory(Path.of(repoPath).toRealPath())` |
| Test command exits nonzero | HTTP 200, `status="FAILED"`, `exitCode=<n>` — not a system error |
| Timeout exceeded | Returns `FAILED / exitCode=-1 / "timed out"` (process killed) |
| `timeoutSeconds > 180` | Silently clamped to 180 |
| `timeoutSeconds == null` | Defaults to 120s |
| No `sh -c` / no shell expansion | `ProcessBuilder(args)` with explicit list; `cwd=repoPath` |
| No auto-fix / auto-commit | By design — human reviews output and decides next action |

**Run the E2E proof:**
```bash
# Backend + Redis must already be running
SKIP_BACKEND=1 bash scripts/run_test_runner_e2e.sh
```

**Unit tests:** `src/test/java/com/asyncaiflow/DevOsRunTestTest.java` (9 scenarios: allowed→PASSED, disallowed×3→400, repoPath-missing→400, repoPath-is-file→400, fixture-fail→FAILED not 500, timeout-clamp, timeout-null)

---

## API Reference

### POST /devos/start

**Request:**
```json
{
  "text": "user message text",
  "slackThreadId": "C08XXXXXX/1234567890.123456",
  "prevActionId": 42,
  "repoPath": "/path/to/local/repo",
  "filePath": "src/main/README.md",
  "writeIntent": true,
  "workspaceKey": "repo:/path/to/local/repo",
  "mode": "patch_preview",
  "replaceFrom": "Hello Old Title",
  "replaceTo": "Hello Slack Dev OS"
}
```

> `prevActionId` is optional. When provided, the new Action inherits the notepad_ref from the referenced Action (Stage 2 Context Restore).
>
> `repoPath` + `filePath` are optional. When both are provided, the worker performs a **Page Fault** (Stage 4): reads the file safely and injects `[PAGE_IN]` content into the LLM response and `[page-in:filePath]` into the notepad.
>
> `writeIntent` + `workspaceKey` are optional (Stage 5 Workspace Mutex). When `writeIntent=true`, the kernel acquires a Redis SETNX lock on `workspaceKey` before transitioning the Action to RUNNING. Only one writer per `workspaceKey` can be RUNNING at a time; additional writers are re-queued until the current writer finishes. `writeIntent=false` actions bypass the mutex and schedule freely.
>
> `mode` is optional. Set to `"patch_preview"` for Stage 7 dry-run coding. `replaceFrom` + `replaceTo` are optional sub-fields for deterministic replacement diff. If `replaceFrom` is absent, the LLM generates a `[PATCH_PLAN_ONLY]` instead.

**Response:**
```json
{
  "code": 200,
  "data": {
    "actionId": 42,
    "workflowId": 7,
    "status": "QUEUED",
    "slackThreadId": "C08XXXXXX/1234567890.123456"
  }
}
```

`slackThreadId` format: `<channel_id>/<thread_ts>` (e.g. `C08ABC123/1714500000.000100`)

### POST /devos/interrupt

**Request:**
```json
{
  "actionId": 123,
  "slackThreadId": "C08XXXXXX/1234567890.123456",
  "reason": "User asked to stop this task"
}
```

> `slackThreadId` must match the Action's own `slackThreadId` (B-007 ownership guard). Mismatches return 403 FORBIDDEN.
> `reason` is optional. When omitted, `errorMessage` is set to `USER_INTERRUPTED`.

**Response (success):**
```json
{
  "success": true,
  "data": {
    "actionId": 123,
    "status": "FAILED",
    "interrupted": true
  }
}
```

**Response (already terminal — 409 CONFLICT):**
```json
{
  "success": false,
  "message": "Action is already in terminal state and cannot be interrupted: 123"
}
```

Active states that can be interrupted: `RUNNING`, `QUEUED`, `RETRY_WAIT`, `BLOCKED`.
Terminal states (`SUCCEEDED`, `FAILED`, `DEAD_LETTER`) are immutable.

## GitHub Actions CI

Two workflows run automatically on every push and pull request to `main`.

> **Phase 2 CI proof — both workflows passed on commit `6e5bb8c`**
> - CI run: https://github.com/Zxy876/slack-dev-os-mvp/actions/runs/25157666547
> - Demo E2E run: https://github.com/Zxy876/slack-dev-os-mvp/actions/runs/25157666524

> **Stage 2 Context Restore — local E2E verified (2026-04-30)**
> - `POST /devos/start { prevActionId }` → notepad propagated across sequential Actions
> - 3 integration tests: `DevOsContextRestoreTest` (all PASS)
> - GHA `devos-demo-e2e.yml` extended with Round 2 (Context Restore) verification

### CI — Build & Test

[`.github/workflows/ci.yml`](.github/workflows/ci.yml)

- Runs on **ubuntu-latest**, Java 21, H2 in-memory (no MySQL needed)
- Redis 7 service available at localhost:6379
- Command: `mvn test -Dspring.profiles.active=local`
- **108 tests, 0 failures, BUILD SUCCESS** (Stage 0–6, all Java integration tests)

### Demo E2E — devos_chat Instruction Cycle

[`.github/workflows/devos-demo-e2e.yml`](.github/workflows/devos-demo-e2e.yml)

Proves the full kernel instruction cycle without any real LLM keys or Slack tokens:

1. Builds backend JAR (`mvn package -DskipTests`)
2. Starts backend via `java -jar` (`local` profile — H2 + Redis)
3. Waits for `GET /health` → 200
4. Runs Tool Manager smoke tests (`test_tool_manager.py`, B-008)
5. Starts `devos_chat_worker` with `DEMO_MODE=true` (GLM/OpenAI keys unset)
6. **Round 1**: `POST /devos/start` → polls until COMPLETED → asserts `[DEMO]` + notepad present
7. **Round 2**: `POST /devos/start` with `prevActionId` → polls → asserts `[Notepad context was present]` (Stage 2 Context Restore verified)
8. **Round 3** (Page Fault E2E): `POST /devos/start` with `repoPath`+`filePath` → asserts `[PAGE_IN]` in response + `[page-in:...]` in notepad (Stage 4 verified)

**No real LLM keys or Slack tokens needed. CI always uses `DEMO_MODE=true`.**

Can also be triggered manually via `workflow_dispatch`.

## E2E Proof (Local)

Full instruction cycle validated locally:

```
POST /devos/start
  {"text":"How do I reset a build?","slackThreadId":"CDEMO/1714500000.123456"}

→ {"success":true,"data":{"actionId":1,"workflowId":1,"status":"QUEUED",...}}

→ devos_chat_worker polls /action/poll?workerId=devos-worker-1
→ DEMO stub executes (DEMO_MODE=true)
→ POST /action/result  {"response":"[DEMO]...","notepad":"..."}
→ Action status → SUCCEEDED → COMPLETED

GET /action/1
→ {"success":true,"data":{"status":"COMPLETED","result":{"response":"[DEMO]...","notepad":"..."}}}
```

**Verified**: 84 tests pass. Full E2E cycle completes in DEMO_MODE.

## Roadmap

See [docs/SCENARIO_MATRIX.md](docs/SCENARIO_MATRIX.md) for the full 9-stage kernel scenario matrix:

- **Stage 0** (✅ Complete): Syscall, PCB, capability dispatch, worker, DEMO stub, notepad, local E2E
- **Stage 1** (✅ Complete): GitHub Actions CI proof (mvn test + DEMO E2E)
- **Stage 2** (✅ Complete): Context restore — prevActionId, notepad propagation, isolation (3 tests)
- **Stage 3** (✅ Complete): Fault tolerance — Watchdog/Lease/Retry (B-002, 3 tests) + User Interrupt B-003 (4 tests) + DAG acyclicity B-004 (4 tests: `wouldCreateCycle` BFS, linear chain unlock, direct/indirect cycle detection)
- **Stage 4** (✅ Complete): Disk/Page Fault — `repoPath`+`filePath` payload 透传，`safe_read_repo_file` 安全校验，[PAGE_IN] marker 注入，[page-in] notepad 记录；2 集成测试 + Page Fault E2E PASSED
- **Stage 5** (✅ Complete): Single-writer mutex — `writeIntent`/`workspaceKey`, Redis SETNX, 4 tests (`DevOsWorkspaceMutexTest`)
- **Stage 6** (✅ Complete): Tool Manager — `ToolCall`/`ToolResponse`/`ToolManager`, whitelist `{repo.read_file}`, Page Fault → `TOOL_MANAGER.execute()`, 7 Python smoke tests; Ownership Guard — `slackThreadId` scope boundary, cross-thread 403, 4 Java integration tests; Production Config (B-010) — `select_llm_backend()`, `validate_runtime_config()`, `redact_secret()`, 14 Python tests, dry-run config check script; live Slack smoke PASSED 2026-05-01
- **Stage 7** (✅ Complete): Dry-Run Coding / Patch Preview — `mode=patch_preview`, `execute_patch_preview()`, workspace isolation, `[PATCH_PREVIEW]` diff, original file unchanged invariant; 10 Python tests + `run_patch_preview_e2e.sh`
- **Stage 8** (✅ Complete): Human Confirm Apply Patch — `POST /devos/apply-patch`: confirm guard, B-007 ownership, SHA-256 stale-hash guard, replaceFrom check, write-once, no auto-commit; 5 Java tests + `run_apply_patch_e2e.sh`
- **Stage 9** (✅ Complete): Test Command Runner — `POST /devos/run-test`: allowlist-only ProcessBuilder execution, timeout clamp(1–180s), PASSED/FAILED status, HTTP 200 on test failure, no auto-fix/commit; 9 Java tests + `run_test_runner_e2e.sh`

## Scope

This repository is an **extraction of the Slack Dev OS MVP only**.

It does **not** include:
- Minecraft / Paper server / world files
- Drift backend, Drift plugin, Drift RL agent
- Drift demo scripts or panels
- NotebookLM exports or personal documents
- Unrelated project files from the original monorepo

The Java framework base is **AsyncAIFlow 4.8**, modified to support the Slack Dev OS instruction cycle.
