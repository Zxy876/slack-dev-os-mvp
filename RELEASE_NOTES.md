# Slack Dev OS — Release Notes

## v0.1.0-rc2 (2026-05-01)

> **Release Candidate 2**: Documentation accuracy fix only. No code changes.
> Corrects `docs/SECURITY_CHECKLIST.md` S-010 — the v0.1.0-rc1 checklist claimed `✅ PASS` for Python dependency CVE scan but `pip-audit` was not run. Changed to `⚠️ PENDING` with instructions for manual verification. Overall security summary updated from 10/10 to 9/10 PASS + 1 PENDING.
>
> All capabilities, test counts, and CI proof from v0.1.0-rc1 remain unchanged.

---

## v0.1.0-rc1 (2026-04-30)

> **Release Candidate 1**: All Stage 0–6 capabilities implemented, tested, and audited.
> This release is demonstration-ready and CI-verified. Live Slack/LLM integration is config-ready; full production wiring (live tokens in CI) is deferred to a subsequent release.

---

## Summary

Slack Dev OS MVP is a minimal working implementation of the Slack Dev OS concept. Slack acts as the syscall entry point; AsyncAIFlow 4.8 (Spring Boot) acts as the kernel; a Python worker acts as the agent execution unit. Actions (Process Control Blocks) carry full lifecycle state: `slack_thread_id`, `notepad_ref`, lease, retry, and workspace mutex.

This release closes the B-001 through B-008, B-010, B-013, and B-014 backlog items. No new product functionality is introduced in this RC; it is an audit, documentation, and signal-integrity release.

---

## Capabilities by Stage

### Stage 0 — Kernel Bootstrap
- Spring Boot 3.3.6 / Java 21 backend on port 8080
- `POST /devos/start` syscall entry point
- `ActionEntity` PCB with 21 fields (state, thread_id, notepad, lease, retry, workspaceKey, …)
- 7-state state machine + `isValidTransition()` guard
- BLOCKED → QUEUED DAG unblocking (`unblockDownstreamActions()`)
- `notepad_ref` persistence + worker delivery

### Stage 1 — CI Proof
- GitHub Actions `ci.yml`: Java 21, H2 in-memory, 0-dependency test run
- GitHub Actions `devos-demo-e2e.yml`: full kernel instruction cycle in CI
- `DEMO_MODE=true` default for all CI/E2E — no real secrets required

### Stage 2 — Context Restore (B-001)
- `prevActionId` field propagates `notepad_ref` across sequential Actions
- Cross-thread isolation: `resolveNotepadRef()` returns 403 for cross-thread access
- 3 integration tests: `DevOsContextRestoreTest`
- E2E Round 2 verifies `[Notepad context was present]` marker

### Stage 3 — Fault Tolerance (B-002, B-003, B-004)
- Watchdog lease expiry → RETRY_WAIT → re-enqueue
- DEAD_LETTER doom-loop prevention (maxRetryCount)
- `POST /devos/interrupt` transitions RUNNING/QUEUED/RETRY_WAIT/BLOCKED → FAILED; terminal states immutable (409)
- BFS DAG cycle detection rejects cyclic `upstreamActionIds` with 400

### Stage 4 — Page Fault / Repository File Retrieval (B-005)
- `repoPath` + `filePath` in `POST /devos/start`
- `safe_read_repo_file()`: path traversal guard + repository boundary validation
- `[PAGE_IN]` content injected into LLM response; `[page-in:filePath]` recorded in notepad
- Page Fault E2E script: `scripts/run_page_fault_e2e.sh`

### Stage 5 — Workspace Mutex (B-006)
- `writeIntent` + `workspaceKey` in `POST /devos/start`
- `tryAcquireWorkspaceLock()` / `releaseWorkspaceLock()` via Redis SETNX
- Only one writer per `workspaceKey` RUNNING at a time; extras re-queued
- Lock released on: SUCCEEDED, FAILED, DEAD_LETTER, interrupt

### Stage 6 — Production Hardening

#### Tool Manager (B-008)
- `ToolCall` / `ToolResponse` / `ToolManager` dataclasses in `worker.py`
- Hard-coded whitelist: `{repo.read_file}`; unknown tools return `ok=False`
- Page Fault path routes through `TOOL_MANAGER.execute()`
- 7 pytest smoke tests: `test_tool_manager.py`

#### Ownership Guard (B-007)
- `slackThreadId` is the MVP resource scope boundary
- `POST /devos/interrupt` + `prevActionId` require matching `slackThreadId`
- Cross-thread operations return 403 FORBIDDEN
- 4 integration tests: `DevOsAccessControlTest`

#### Production Config Readiness (B-010 — PARTIAL)
- `select_llm_backend()`: DEMO > GLM > OpenAI > RuntimeError (fail-fast)
- `validate_runtime_config()`: startup config summary with redacted secrets
- `redact_secret()`: shows first 4 chars + `***`
- `REQUIRE_SLACK_POST` flag: missing token → skip (default) or RuntimeError
- `.env.example`: complete template, `DEMO_MODE=true` default, no real keys
- `scripts/run_production_config_check.sh`: dry-run validation (exit 0/1)
- 14 pytest cases: `test_runtime_config.py`
- **Live Slack/LLM integration NOT wired in CI** (no real secrets in CI)

---

## Test Counts (All Passing)

| Suite | Count | Tool | Notes |
|---|---|---|---|
| Java integration tests | 108 | Maven / JUnit 5 | H2 in-memory, covers Stage 0–6 |
| Python smoke tests | 7 | pytest | `test_tool_manager.py` — B-008 |
| Python config tests | 14 | pytest | `test_runtime_config.py` — B-010 |
| **Total** | **129** | | **0 failures** |

Run all tests:
```bash
# Java
mvn test -Dspring.profiles.active=local

# Python
cd python-workers/devos_chat_worker
python3 -m pytest test_tool_manager.py test_runtime_config.py -q
```

---

## CI Coverage

| Workflow | Trigger | What it proves |
|---|---|---|
| `ci.yml` | Push / PR to `main` | 108 Java tests, H2, no real deps |
| `devos-demo-e2e.yml` | Push / PR to `main` + `workflow_dispatch` | Full instruction cycle (DEMO_MODE), Context Restore, Page Fault, Tool Manager smoke |

CI never injects real `OPENAI_API_KEY`, `GLM_API_KEY`, or `SLACK_BOT_TOKEN`. All secrets are unset (`env -u`) in the E2E workflow.

---

## Local E2E Scripts

| Script | What it runs | Expected output |
|---|---|---|
| `scripts/run_demo_e2e.sh` | Round 1 (basic), Round 2 (Context Restore) | `[DEMO]` + `[Notepad context was present]` |
| `scripts/run_page_fault_e2e.sh` | Round 3 (Page Fault) | `[PAGE_IN]` + `[page-in:...]` in notepad |
| `scripts/run_production_config_check.sh` | Config dry-run (no API calls) | Exit 0 if DEMO or real key found; Exit 1 if neither |
| `scripts/secret_scan.sh` | Grep for hardcoded secrets | Exit 0 (no real keys found); Exit 1 if found |

---

## Security Invariants

- No real secrets are committed to the repository (enforced by `.gitignore` and `secret_scan.sh`)
- Path traversal is guarded by `safe_read_repo_file()`: resolves to realpath, checks `repoPath` prefix
- Tool calls use a hard-coded whitelist; unknown tools are rejected without exception
- `slackThreadId` is the resource scope boundary; cross-thread operations return 403
- All log output redacts secrets via `redact_secret()` (shows only first 4 chars + `***`)
- `DEMO_MODE=true` is the default in all CI and E2E contexts

See [`docs/SECURITY_CHECKLIST.md`](docs/SECURITY_CHECKLIST.md) for the full audit checklist.

---

## Known Limitations

| Item | Status | Backlog |
|---|---|---|
| Live Slack slash command / Events API | Not wired (config-ready only) | B-010 live |
| Real LLM in CI | Not wired (DEMO_MODE in CI) | B-010 live |
| RAG / vector retrieval | Not implemented | B-009 |
| Multi-model routing | Not implemented | B-011 |
| Async Redis Pub/Sub interrupt | Not implemented | B-012 |
| Cloud deployment | Not implemented | future |
| Drift/Minecraft scenarios | Not in MVP scope | future |

---

## Next Backlog (Post-RC)

| ID | Title | Priority |
|---|---|---|
| B-010-live | Live Slack token + real LLM in production | P4 |
| B-009 | Multi-layer retriever (RAG) | P3 |
| B-011 | Multi-model heterogeneous dispatch | P4 |
| B-012 | Interrupt as async Redis Pub/Sub signal | P3 |

---

## Reproduction

```bash
# Clone
git clone https://github.com/Zxy876/slack-dev-os-mvp
cd slack-dev-os-mvp

# Quick demo (no Docker, no real keys)
mvn test -Dspring.profiles.active=local
cd python-workers/devos_chat_worker
python3 -m pytest test_tool_manager.py test_runtime_config.py -q
```

For the full E2E, start Redis (`docker compose up -d redis`) and then run the scripts above.
