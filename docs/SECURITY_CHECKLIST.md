# Security Checklist — Slack Dev OS MVP (v0.1.0-rc1)

> Audit date: 2026-04-30
> Scope: all committed files in `main` as of tag `v0.1.0-rc1`
> Auditor: B-014 release candidate review

---

## S-001 — No Real Secrets in Repository

| Check | Status | Evidence |
|---|---|---|
| `OPENAI_API_KEY` not hardcoded in any committed file | ✅ PASS | `scripts/secret_scan.sh` exit 0 |
| `GLM_API_KEY` not hardcoded | ✅ PASS | `scripts/secret_scan.sh` exit 0 |
| `SLACK_BOT_TOKEN` not hardcoded | ✅ PASS | `scripts/secret_scan.sh` exit 0 |
| `.env` file not committed | ✅ PASS | `.env` is in `.gitignore` |
| `.env.example` contains only empty values or DEMO defaults | ✅ PASS | See `python-workers/devos_chat_worker/.env.example` |

**How to verify**:
```bash
bash scripts/secret_scan.sh
```

---

## S-002 — .env Gitignore

| Check | Status | Evidence |
|---|---|---|
| `python-workers/devos_chat_worker/.env` in `.gitignore` | ✅ PASS | `.gitignore` entry: `*.env` or explicit path |
| `.env.example` committed as template only | ✅ PASS | Contains `DEMO_MODE=true`, all keys empty |

---

## S-003 — DEMO_MODE Default in CI / E2E

| Check | Status | Evidence |
|---|---|---|
| `ci.yml` does NOT inject real LLM keys | ✅ PASS | No `OPENAI_API_KEY`/`GLM_API_KEY` in workflow env |
| `devos-demo-e2e.yml` explicitly unsets LLM keys (`env -u`) | ✅ PASS | `env -u OPENAI_API_KEY -u GLM_API_KEY` in E2E step |
| `DEMO_MODE=true` explicitly set in all E2E scripts | ✅ PASS | `run_demo_e2e.sh`, `run_page_fault_e2e.sh` both set `DEMO_MODE=true` |
| Worker exits cleanly if `DEMO_MODE=false` and no key | ✅ PASS | `select_llm_backend()` raises `RuntimeError` → startup fails fast |

---

## S-004 — Secret Redaction in Logs

| Check | Status | Evidence |
|---|---|---|
| `redact_secret(value)` used for all key logging | ✅ PASS | `worker.py` — `validate_runtime_config()` calls `redact_secret()` on all key values |
| Redacted format: first 4 chars + `***` | ✅ PASS | `redact_secret()` implementation |
| 4 pytest cases cover edge cases (short, None, normal) | ✅ PASS | `test_runtime_config.py` — `TestSecretRedaction` |

---

## S-005 — Path Traversal Prevention

| Check | Status | Evidence |
|---|---|---|
| `safe_read_repo_file(repoPath, filePath)` resolves to `realpath` | ✅ PASS | `worker.py` |
| Resulting path is checked against `repoPath` prefix | ✅ PASS | Path prefix check before `open()` |
| `..` traversal returns `ok=False` without exception | ✅ PASS | `test_tool_manager.py` — `test_path_traversal_rejected` |
| Absolute `filePath` is rejected | ✅ PASS | `test_tool_manager.py` — `test_absolute_path_rejected` |

---

## S-006 — Tool Whitelist

| Check | Status | Evidence |
|---|---|---|
| `ToolManager.WHITELIST` is a hard-coded set | ✅ PASS | `WHITELIST = {"repo.read_file"}` in `worker.py` |
| Unknown tool names return `ok=False` (no exception) | ✅ PASS | `test_tool_manager.py` — `test_unknown_tool_rejected` |
| Whitelist cannot be extended at runtime | ✅ PASS | Set is module-level constant, no mutation API |

---

## S-007 — Ownership Guard / Thread Isolation

| Check | Status | Evidence |
|---|---|---|
| `POST /devos/interrupt` validates `slackThreadId` matches Action | ✅ PASS | `DevOsService.interrupt()` — B-007 |
| `POST /devos/start` with `prevActionId` validates same `slackThreadId` | ✅ PASS | `DevOsService.resolveNotepadRef()` — B-007 |
| Cross-thread operations return `403 FORBIDDEN` | ✅ PASS | `DevOsAccessControlTest` — 4 integration tests |
| `ApiException.defaultCode()` maps `FORBIDDEN → ACCESS_DENIED` | ✅ PASS | `ApiException.java` |

---

## S-008 — Workspace Mutex (Concurrency Safety)

| Check | Status | Evidence |
|---|---|---|
| Redis SETNX prevents dual-writer on same `workspaceKey` | ✅ PASS | `ActionQueueService.tryAcquireWorkspaceLock()` — B-006 |
| Lock released on: SUCCEEDED, FAILED, DEAD_LETTER, INTERRUPTED | ✅ PASS | `DevOsService.submitResult()` + `interruptAction()` + `applyFailureWithRetry()` |
| Read-only actions (`writeIntent=false`) bypass mutex | ✅ PASS | 4 integration tests in B-006 suite |

---

## S-009 — CI Secret Policy

| Check | Status | Evidence |
|---|---|---|
| No `secrets.*` used in `ci.yml` | ✅ PASS | `ci.yml` — no `${{ secrets.* }}` references |
| No `secrets.*` used for LLM/Slack keys in `devos-demo-e2e.yml` | ✅ PASS | `devos-demo-e2e.yml` — LLM keys explicitly unset |
| CI uses H2 in-memory (no external DB secrets needed) | ✅ PASS | `application-local.yml` |

---

## S-010 — Dependency Security

| Check | Status | Evidence |
|---|---|---|
| Java dependencies managed by Spring Boot 3.3.6 BOM | ✅ PASS | `pom.xml` — Spring Boot parent |
| Python dep CVE scan (`pip-audit`) | ⚠️ PENDING | `pip-audit` not installed in v0.1.0-rc1 build env; deps pinned in `requirements.txt` but no formal audit run. Run `pip install pip-audit && pip-audit -r requirements.txt` to verify before production use. |

---

## Summary

| ID | Invariant | Status |
|---|---|---|
| S-001 | No real secrets in repo | ✅ PASS |
| S-002 | .env gitignored | ✅ PASS |
| S-003 | DEMO_MODE default in CI/E2E | ✅ PASS |
| S-004 | Secret redaction in logs | ✅ PASS |
| S-005 | Path traversal prevention | ✅ PASS |
| S-006 | Tool whitelist | ✅ PASS |
| S-007 | Thread ownership guard | ✅ PASS |
| S-008 | Workspace mutex | ✅ PASS |
| S-009 | CI secret policy | ✅ PASS |
| S-010 | Dependency security (pip-audit) | ⚠️ PENDING |

**Overall: 9/10 PASS, 1 PENDING** — S-010 pip-audit not run; all other invariants verified for v0.1.0-rc1.
