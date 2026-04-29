# Slack Dev OS MVP

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
- Full test suite passing: **84 tests, 0 failures, BUILD SUCCESS**

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

**Verified result: 84 tests, 0 failures, BUILD SUCCESS**

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

LLM priority: `GLM_API_KEY` > `OPENAI_API_KEY` > `DEMO_MODE=true` (stub response).

## API Reference

### POST /devos/start

**Request:**
```json
{
  "text": "user message text",
  "slackThreadId": "C08XXXXXX/1234567890.123456"
}
```

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

## Scope

This repository is an **extraction of the Slack Dev OS MVP only**.

It does **not** include:
- Minecraft / Paper server / world files
- Drift backend, Drift plugin, Drift RL agent
- Drift demo scripts or panels
- NotebookLM exports or personal documents
- Unrelated project files from the original monorepo

The Java framework base is **AsyncAIFlow 4.8**, modified to support the Slack Dev OS instruction cycle.
