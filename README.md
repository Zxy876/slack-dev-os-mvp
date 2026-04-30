# Slack Dev OS MVP

[![CI — Build & Test](https://github.com/Zxy876/slack-dev-os-mvp/actions/workflows/ci.yml/badge.svg)](https://github.com/Zxy876/slack-dev-os-mvp/actions/workflows/ci.yml)
[![Demo E2E](https://github.com/Zxy876/slack-dev-os-mvp/actions/workflows/devos-demo-e2e.yml/badge.svg)](https://github.com/Zxy876/slack-dev-os-mvp/actions/workflows/devos-demo-e2e.yml)

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
- Full test suite passing: **108 Java tests + 7 Python smoke tests, 0 failures, BUILD SUCCESS**

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
  "workspaceKey": "repo:/path/to/local/repo"
}
```

> `prevActionId` is optional. When provided, the new Action inherits the notepad_ref from the referenced Action (Stage 2 Context Restore).
>
> `repoPath` + `filePath` are optional. When both are provided, the worker performs a **Page Fault** (Stage 4): reads the file safely and injects `[PAGE_IN]` content into the LLM response and `[page-in:filePath]` into the notepad.
>
> `writeIntent` + `workspaceKey` are optional (Stage 5 Workspace Mutex). When `writeIntent=true`, the kernel acquires a Redis SETNX lock on `workspaceKey` before transitioning the Action to RUNNING. Only one writer per `workspaceKey` can be RUNNING at a time; additional writers are re-queued until the current writer finishes. `writeIntent=false` actions bypass the mutex and schedule freely.

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
- **Passed on commit `6e5bb8c`: 84 tests, 0 failures, BUILD SUCCESS**

### Demo E2E — devos_chat Instruction Cycle

[`.github/workflows/devos-demo-e2e.yml`](.github/workflows/devos-demo-e2e.yml)

Proves the full kernel instruction cycle without any real LLM keys or Slack tokens:

1. Builds backend JAR (`mvn package -DskipTests`)
2. Starts backend via `java -jar` (`local` profile — H2 + Redis)
3. Waits for `GET /health` → 200
4. Starts `devos_chat_worker` with `DEMO_MODE=true`
5. `POST /devos/start` with test payload
6. Polls `GET /action/{actionId}` until `status == COMPLETED`
7. Asserts `result.response` contains `[DEMO]`
8. Asserts `result.notepad` is non-empty

**Passed on commit `6e5bb8c`**: Full instruction cycle verified in CI.

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

See [docs/SCENARIO_MATRIX.md](docs/SCENARIO_MATRIX.md) for the full 7-stage kernel scenario matrix:

- **Stage 0** (✅ Complete): Syscall, PCB, capability dispatch, worker, DEMO stub, notepad, local E2E
- **Stage 1** (✅ Complete): GitHub Actions CI proof (mvn test + DEMO E2E)
- **Stage 2** (✅ Complete): Context restore — prevActionId, notepad propagation, isolation (3 tests)
- **Stage 3** (✅ Complete): Fault tolerance — Watchdog/Lease/Retry (B-002, 3 tests) + User Interrupt B-003 (4 tests) + DAG acyclicity B-004 (4 tests: `wouldCreateCycle` BFS, linear chain unlock, direct/indirect cycle detection)
- **Stage 4** (✅ Complete): Disk/Page Fault — `repoPath`+`filePath` payload 透传，`safe_read_repo_file` 安全校验，[PAGE_IN] marker 注入，[page-in] notepad 记录；2 集成测试 + Page Fault E2E PASSED
- **Stage 5** (✅ Complete): Single-writer mutex — `writeIntent`/`workspaceKey`, Redis SETNX, 4 tests (`DevOsWorkspaceMutexTest`)
- **Stage 6** (✅ Partial): Tool Manager — `ToolCall`/`ToolResponse`/`ToolManager`, whitelist `{repo.read_file}`, Page Fault → `TOOL_MANAGER.execute()`, 7 Python smoke tests; Ownership Guard — `slackThreadId` scope boundary, cross-thread 403, 4 Java integration tests; Production Config (B-010) — `select_llm_backend()`, `validate_runtime_config()`, `redact_secret()`, 14 Python tests, dry-run config check script
- **Stage 6** (🔶 Partial): Real Slack slash command + LLM keys — B-010 production config boundary done (14 Python tests, dry-run script, .env.example); live Slack/LLM integration not yet wired in CI

## Scope

This repository is an **extraction of the Slack Dev OS MVP only**.

It does **not** include:
- Minecraft / Paper server / world files
- Drift backend, Drift plugin, Drift RL agent
- Drift demo scripts or panels
- NotebookLM exports or personal documents
- Unrelated project files from the original monorepo

The Java framework base is **AsyncAIFlow 4.8**, modified to support the Slack Dev OS instruction cycle.
