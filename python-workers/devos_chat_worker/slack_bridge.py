"""
slack_bridge.py — B-021.5 / B-022 Slack Command Intent Router
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Slack Dev OS — Slack event → DevOS syscall adapter with intent routing.

B-021.5 introduced the minimal bridge: devos: <text> → /devos/start
B-022 adds an intent parsing layer so Slack messages can express:

  devos: ask <instruction>          → /devos/start
  devos: <instruction>              → /devos/start  (backward compat)
  devos: preview <file> replace "X" with "Y"
                                    → /devos/start (mode=patch_preview)
  devos: apply <actionId> confirm   → /devos/apply-patch
  devos: test <command>             → /devos/run-test
  devos: fix <filePath>             → NEEDS_CONTEXT (v1 stub)
  devos: commit "<message>" confirm → /devos/git-commit

Safety invariants (B-022):
  - apply / commit require the word "confirm" in the message
  - dangerous intents (apply/commit) set dangerous=True in DevosIntent
  - missing confirm → NEEDS_CONFIRMATION, no backend call
  - missing repoPath for preview/test/commit → CONFIG_ERROR, no backend call
  - fix: always returns NEEDS_CONTEXT in v1
  - no automatic apply/commit without explicit confirm
  - no token printing
  - dry_run=true: return intent + payload, no backend call

Prior B-021.5 invariants preserved:
  - bot/subtype messages ignored
  - empty instruction ignored
  - trust_env=False on all sessions
  - DEVOS_BRIDGE_DRY_RUN env var respected

环境变量：
  ASYNCAIFLOW_URL          default http://localhost:8080
  DEVOS_BRIDGE_PREFIX      default "devos:"
  DEVOS_DEFAULT_REPO_PATH  required for preview/test/commit
  DEVOS_BRIDGE_DRY_RUN     default false; true → 仅打印 payload，不调用后端
"""
from __future__ import annotations

import json
import logging
import os
import re
from dataclasses import dataclass, field
from typing import Optional

import requests

LOGGER = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────
# 配置（全部从环境变量读取，不写 hardcoded 值）
# ─────────────────────────────────────────────────────────────
#
# Intent kind constants (B-022)
# ─────────────────────────────────────────────────────────────
INTENT_ASK               = "ask"
INTENT_PREVIEW           = "preview"
INTENT_APPLY             = "apply"
INTENT_TEST              = "test"
INTENT_FIX               = "fix"
INTENT_COMMIT            = "commit"
INTENT_UNKNOWN           = "unknown"
INTENT_NEEDS_CONFIRMATION = "needs_confirmation"
INTENT_NEEDS_CONTEXT     = "needs_context"
INTENT_CONFIG_ERROR      = "config_error"


@dataclass
class DevosIntent:
    """
    Parsed command intent extracted from a Slack message (B-022).

    kind:              one of the INTENT_* constants
    endpoint:          e.g. "/devos/start", "/devos/apply-patch", or ""
    payload:           dict ready to POST to endpoint
    dangerous:         True for apply / commit (writes git history)
    needs_confirmation: True when "confirm" required but absent
    reason:            human-readable explanation for Slack reply
    instruction:       raw text after "devos:" prefix
    """
    kind: str
    endpoint: str
    payload: dict = field(default_factory=dict)
    dangerous: bool = False
    needs_confirmation: bool = False
    reason: str = ""
    instruction: str = ""


# ─────────────────────────────────────────────────────────────
# Regex patterns (B-022)
# ─────────────────────────────────────────────────────────────

# preview <filePath> replace "<from>" with "<to>"
_RE_PREVIEW = re.compile(
    r'^preview\s+(\S+)\s+replace\s+"([^"]+)"\s+with\s+"([^"]+)"',
    re.IGNORECASE,
)
# apply <actionId> [confirm]
_RE_APPLY = re.compile(
    r'^apply\s+(\d+)(\s+confirm)?\s*$',
    re.IGNORECASE,
)
# test <command…>
_RE_TEST = re.compile(r'^test\s+(.+)$', re.IGNORECASE)
# fix <filePath>
_RE_FIX = re.compile(r'^fix\s+(\S+)', re.IGNORECASE)
# commit "<message>" [confirm]
_RE_COMMIT = re.compile(r'^commit\s+"([^"]+)"(\s+confirm)?\s*$', re.IGNORECASE)
# ask <instruction…>
_RE_ASK = re.compile(r'^ask\s+(.+)$', re.IGNORECASE)

def _base_url() -> str:
    return os.environ.get("ASYNCAIFLOW_URL", "http://localhost:8080").rstrip("/")

def _bridge_prefix() -> str:
    return os.environ.get("DEVOS_BRIDGE_PREFIX", "devos:")

def _default_repo_path() -> Optional[str]:
    val = os.environ.get("DEVOS_DEFAULT_REPO_PATH", "")
    return val.strip() or None

def _dry_run() -> bool:
    return os.environ.get("DEVOS_BRIDGE_DRY_RUN", "false").lower() in ("1", "true", "yes")


# ─────────────────────────────────────────────────────────────
# 核心解析函数
# ─────────────────────────────────────────────────────────────

def parse_devos_command(text: str, prefix: Optional[str] = None) -> Optional[str]:
    """
    检查消息文本是否以 prefix 开头，提取后续 instruction。

    参数：
      text:   Slack 消息文本
      prefix: 命令前缀，默认从 DEVOS_BRIDGE_PREFIX 环境变量读取

    返回：
      str  — 去除前缀和首尾空白后的 instruction（非空）
      None — 文本不以 prefix 开头，或 instruction 为空
    """
    if prefix is None:
        prefix = _bridge_prefix()
    prefix_lower = prefix.lower()
    text_lower = text.strip().lower()
    if not text_lower.startswith(prefix_lower):
        return None
    instruction = text.strip()[len(prefix):].strip()
    return instruction if instruction else None


# ─────────────────────────────────────────────────────────────
# B-022: Intent parser
# ─────────────────────────────────────────────────────────────

def parse_devos_intent(
    instruction: str,
    slack_thread_id: str,
    repo_path: Optional[str] = None,
) -> DevosIntent:
    """
    Parse an instruction (text after "devos:" prefix) into a DevosIntent.

    Command grammar:
        ask <text>                               → ask / /devos/start
        <text>                                   → ask / /devos/start  (default)
        preview <f> replace "<x>" with "<y>"     → preview / /devos/start
        apply <id> confirm                       → apply / /devos/apply-patch
        test <cmd>                               → test / /devos/run-test
        fix <f>                                  → NEEDS_CONTEXT (v1)
        commit "<msg>" confirm                   → commit / /devos/git-commit

    Safety:
        apply/commit without "confirm" → NEEDS_CONFIRMATION (no backend call)
        preview/test/commit without repoPath → CONFIG_ERROR (no backend call)
        fix → always NEEDS_CONTEXT (v1)
    """
    # ── preview ──────────────────────────────────────────────
    m = _RE_PREVIEW.match(instruction)
    if m:
        file_path, replace_from, replace_to = m.group(1), m.group(2), m.group(3)
        if not repo_path:
            return DevosIntent(
                kind=INTENT_CONFIG_ERROR,
                endpoint="",
                reason="preview requires DEVOS_DEFAULT_REPO_PATH to be set",
                instruction=instruction,
            )
        return DevosIntent(
            kind=INTENT_PREVIEW,
            endpoint="/devos/start",
            payload={
                "text": instruction,
                "slackThreadId": slack_thread_id,
                "repoPath": repo_path,
                "filePath": file_path,
                "mode": "patch_preview",
                "replaceFrom": replace_from,
                "replaceTo": replace_to,
                "writeIntent": True,
                "workspaceKey": repo_path,
            },
            dangerous=False,
            instruction=instruction,
        )

    # ── apply ─────────────────────────────────────────────────
    m = _RE_APPLY.match(instruction)
    if m:
        action_id_str = m.group(1)
        has_confirm = bool(m.group(2) and m.group(2).strip().lower() == "confirm")
        try:
            preview_action_id = int(action_id_str)
        except ValueError:
            return DevosIntent(
                kind=INTENT_UNKNOWN,
                endpoint="",
                dangerous=True,
                reason=f"apply: could not parse action ID {action_id_str!r}",
                instruction=instruction,
            )
        if not has_confirm:
            return DevosIntent(
                kind=INTENT_NEEDS_CONFIRMATION,
                endpoint="",
                dangerous=True,
                needs_confirmation=True,
                reason=(
                    f"Dangerous command requires explicit 'confirm'. "
                    f"Re-send: devos: apply {preview_action_id} confirm"
                ),
                instruction=instruction,
            )
        return DevosIntent(
            kind=INTENT_APPLY,
            endpoint="/devos/apply-patch",
            payload={
                "previewActionId": preview_action_id,
                "slackThreadId": slack_thread_id,
                "confirm": True,
            },
            dangerous=True,
            instruction=instruction,
        )

    # ── test ──────────────────────────────────────────────────
    m = _RE_TEST.match(instruction)
    if m:
        command = m.group(1).strip()
        if not repo_path:
            return DevosIntent(
                kind=INTENT_CONFIG_ERROR,
                endpoint="",
                reason="test requires DEVOS_DEFAULT_REPO_PATH to be set",
                instruction=instruction,
            )
        return DevosIntent(
            kind=INTENT_TEST,
            endpoint="/devos/run-test",
            payload={
                "repoPath": repo_path,
                "slackThreadId": slack_thread_id,
                "command": command,
                "timeoutSeconds": 120,
            },
            dangerous=False,
            instruction=instruction,
        )

    # ── fix ───────────────────────────────────────────────────
    m = _RE_FIX.match(instruction)
    if m:
        file_path = m.group(1)
        return DevosIntent(
            kind=INTENT_NEEDS_CONTEXT,
            endpoint="",
            dangerous=False,
            reason=(
                "Fix requires failure context. "
                "Run /devos/run-test first or use the propose-fix API directly "
                f"with testStatus/stdout/stderr. "
                f"(Slack bridge v1 does not auto-forward failure context for {file_path!r})"
            ),
            instruction=instruction,
        )

    # ── commit ────────────────────────────────────────────────
    m = _RE_COMMIT.match(instruction)
    if m:
        commit_msg = m.group(1)
        has_confirm = bool(m.group(2) and m.group(2).strip().lower() == "confirm")
        if not has_confirm:
            return DevosIntent(
                kind=INTENT_NEEDS_CONFIRMATION,
                endpoint="",
                dangerous=True,
                needs_confirmation=True,
                reason=(
                    "Dangerous command requires explicit 'confirm'. "
                    f'Re-send: devos: commit "{commit_msg}" confirm'
                ),
                instruction=instruction,
            )
        if not repo_path:
            return DevosIntent(
                kind=INTENT_CONFIG_ERROR,
                endpoint="",
                dangerous=True,
                reason="commit requires DEVOS_DEFAULT_REPO_PATH to be set",
                instruction=instruction,
            )
        return DevosIntent(
            kind=INTENT_COMMIT,
            endpoint="/devos/git-commit",
            payload={
                "repoPath": repo_path,
                "slackThreadId": slack_thread_id,
                "message": commit_msg,
                "confirm": True,
            },
            dangerous=True,
            instruction=instruction,
        )

    # ── ask (explicit keyword) ─────────────────────────────────
    m = _RE_ASK.match(instruction)
    if m:
        ask_text = m.group(1).strip()
        payload: dict = {"text": ask_text, "slackThreadId": slack_thread_id}
        if repo_path:
            payload["repoPath"] = repo_path
        return DevosIntent(
            kind=INTENT_ASK,
            endpoint="/devos/start",
            payload=payload,
            instruction=instruction,
        )

    # ── default: treat full instruction as ask (backward compat) ─
    payload = {"text": instruction, "slackThreadId": slack_thread_id}
    if repo_path:
        payload["repoPath"] = repo_path
    return DevosIntent(
        kind=INTENT_ASK,
        endpoint="/devos/start",
        payload=payload,
        instruction=instruction,
    )


# ─────────────────────────────────────────────────────────────
# HTTP helpers
# ─────────────────────────────────────────────────────────────

def _make_session() -> requests.Session:
    sess = requests.Session()
    sess.trust_env = False  # 忽略本地代理，与 worker.py 保持一致
    return sess


def call_devos_endpoint(
    endpoint: str,
    payload: dict,
    base_url: Optional[str] = None,
) -> dict:
    """
    POST payload to base_url + endpoint.  Returns parsed response dict.

    Raises RuntimeError on HTTP error or connection failure.
    Does NOT log full payload to avoid leaking repoPath/tokens.
    """
    url = (base_url or _base_url()) + endpoint
    sess = _make_session()
    try:
        resp = sess.post(url, json=payload, timeout=15)
        resp.raise_for_status()
        body = resp.json()
        data = body.get("data") or body
        return {"success": True, **data}
    except requests.HTTPError as exc:
        status_code = exc.response.status_code if exc.response is not None else 0
        try:
            err_body = exc.response.json() if exc.response is not None else {}
        except Exception:
            err_body = {}
        msg = err_body.get("message") or str(exc)
        raise RuntimeError(f"{endpoint} HTTP {status_code}: {msg}") from exc
    except requests.RequestException as exc:
        raise RuntimeError(f"{endpoint} connection error: {exc}") from exc


def call_devos_start(payload: dict, base_url: Optional[str] = None) -> dict:
    """Backward-compatible wrapper — calls /devos/start."""
    return call_devos_endpoint("/devos/start", payload, base_url=base_url)


# ─────────────────────────────────────────────────────────────
# Reply text generator
# ─────────────────────────────────────────────────────────────

def _build_reply_from_intent(intent: DevosIntent, result: Optional[dict] = None) -> str:
    """Build the Slack reply text given an intent and optional backend result."""
    if intent.kind == INTENT_NEEDS_CONFIRMATION:
        return f"⚠️ {intent.reason}"
    if intent.kind == INTENT_NEEDS_CONTEXT:
        return f"ℹ️ {intent.reason}"
    if intent.kind == INTENT_CONFIG_ERROR:
        return f"⚙️ CONFIG_ERROR: {intent.reason}"
    if intent.kind == INTENT_UNKNOWN:
        return f"❓ Unrecognised command. {intent.reason}"
    if result is None:
        return f"[DRY RUN] Would call {intent.endpoint} ({intent.kind})"

    if intent.kind == INTENT_ASK:
        action_id = result.get("actionId") or result.get("id")
        return f"DevOS session started — action {action_id}"
    if intent.kind == INTENT_PREVIEW:
        action_id = result.get("actionId") or result.get("id")
        return f"Patch preview queued — action {action_id}"
    if intent.kind == INTENT_APPLY:
        status = result.get("status", "unknown")
        applied = result.get("applied", False)
        if applied or status == "APPLIED":
            return "Patch apply result — APPLIED ✓"
        return f"Patch apply result — {status}"
    if intent.kind == INTENT_TEST:
        status = result.get("status", "unknown")
        exit_code = result.get("exitCode", "?")
        if status == "PASSED":
            return f"Test result — PASSED ✓ (exit {exit_code})"
        return f"Test result — {status} (exit {exit_code})"
    if intent.kind == INTENT_COMMIT:
        status = result.get("status", "unknown")
        commit_hash = result.get("commitHash", "")
        if status == "COMMITTED":
            short_hash = commit_hash[:8] if commit_hash else "?"
            return f"Commit result — COMMITTED {short_hash}"
        if status == "NO_CHANGES":
            return "Commit result — NO_CHANGES (working tree clean)"
        return f"Commit result — {status}"
    # Generic
    action_id = result.get("actionId") or result.get("id")
    return f"DevOS {intent.kind} dispatched — action {action_id}"


# ─────────────────────────────────────────────────────────────
# Thread ID helpers (unchanged from B-021.5)
# ─────────────────────────────────────────────────────────────

def build_slack_thread_id(event: dict) -> str:
    """
    从 Slack event 构造 slackThreadId。

    格式：channel/thread_ts

    规则：
      - 若 event.thread_ts 存在且非空 → channel/thread_ts
      - 否则 → channel/ts（本条消息自身时间戳，作为 thread 起点）
    """
    channel = event.get("channel", "")
    thread_ts = event.get("thread_ts") or event.get("ts", "")
    return f"{channel}/{thread_ts}"


def build_devos_start_payload(
    event: dict,
    instruction: str,
    default_repo_path: Optional[str] = None,
) -> dict:
    """
    构造 POST /devos/start 请求 payload（backward compat with B-021.5 callers）。
    """
    slack_thread_id = build_slack_thread_id(event)
    payload: dict = {
        "text": instruction,
        "slackThreadId": slack_thread_id,
    }
    repo = default_repo_path if default_repo_path is not None else _default_repo_path()
    if repo:
        payload["repoPath"] = repo
    return payload


# ─────────────────────────────────────────────────────────────
# 主入口
# ─────────────────────────────────────────────────────────────

def handle_slack_event(
    event: dict,
    config: Optional[dict] = None,
) -> dict:
    """
    Handle a single Slack message event, returning a result dict.

    Parameters:
      event:  Slack event dict (message event format)
      config: optional override dict; supports keys:
                base_url, prefix, default_repo_path, dry_run

    Return structure:
      {
        "handled":       bool,
        "reason":        str,
        "slackThreadId": str | None,
        "actionId":      int | None,
        "replyText":     str,
        "intent":        dict,   # serialised DevosIntent (B-022)
      }

    Invariants:
      - bot/subtype messages → handled=False
      - non-devos prefix → handled=False
      - empty instruction → handled=False
      - needs_confirmation / needs_context / config_error → handled=True, no backend
      - dry_run=true → handled=True, no backend, intent in result
      - backend error → handled=True, safe error reply
    """
    cfg = config or {}
    prefix    = cfg.get("prefix", _bridge_prefix())
    base_url  = cfg.get("base_url", _base_url())
    repo_path = cfg.get("default_repo_path", _default_repo_path())
    dry_run   = cfg.get("dry_run", _dry_run())

    # ── 1. Ignore bot / subtype messages (anti-loop guard) ────
    subtype = event.get("subtype", "")
    bot_id  = event.get("bot_id", "")
    if subtype or bot_id:
        return {
            "handled": False,
            "reason": f"ignored: bot/subtype message (subtype={subtype!r}, bot_id={bot_id!r})",
            "slackThreadId": None,
            "actionId": None,
            "replyText": "",
            "intent": {},
        }

    event_type = event.get("type", "")
    if event_type != "message" and event_type != "":
        return {
            "handled": False,
            "reason": f"ignored: event type is not 'message' (got {event_type!r})",
            "slackThreadId": None,
            "actionId": None,
            "replyText": "",
            "intent": {},
        }

    # ── 2. Check devos: prefix ─────────────────────────────────
    text        = event.get("text", "") or ""
    instruction = parse_devos_command(text, prefix=prefix)
    if instruction is None:
        return {
            "handled": False,
            "reason": f"ignored: text does not start with prefix {prefix!r}",
            "slackThreadId": None,
            "actionId": None,
            "replyText": "",
            "intent": {},
        }

    # ── 3. Build slackThreadId ─────────────────────────────────
    slack_thread_id = build_slack_thread_id(event)

    # ── 4. Parse intent (B-022) ────────────────────────────────
    intent = parse_devos_intent(instruction, slack_thread_id, repo_path=repo_path)

    def _serialise(i: DevosIntent) -> dict:
        return {
            "kind": i.kind,
            "endpoint": i.endpoint,
            "payload": i.payload,
            "dangerous": i.dangerous,
            "needs_confirmation": i.needs_confirmation,
            "reason": i.reason,
        }

    # ── 5. Short-circuit for non-callable intents ──────────────
    if intent.kind in (
        INTENT_NEEDS_CONFIRMATION,
        INTENT_NEEDS_CONTEXT,
        INTENT_CONFIG_ERROR,
        INTENT_UNKNOWN,
    ):
        reply = _build_reply_from_intent(intent)
        return {
            "handled": True,
            "reason": intent.kind,
            "slackThreadId": slack_thread_id,
            "actionId": None,
            "replyText": reply,
            "intent": _serialise(intent),
        }

    # ── 6. Dry-run: return intent, no backend call ─────────────
    if dry_run:
        base = (
            f"[DRY RUN] Would POST {intent.endpoint}\n"
            f"• kind: {intent.kind}\n"
            f"• instruction: {instruction}\n"
            f"• slackThreadId: {slack_thread_id}"
        )
        if intent.kind == INTENT_PREVIEW:
            base += (
                f"\n• filePath: {intent.payload.get('filePath')}"
                f"\n• replaceFrom: {intent.payload.get('replaceFrom')!r}"
                f"\n• replaceTo: {intent.payload.get('replaceTo')!r}"
            )
        LOGGER.info("[dry-run] intent=%s endpoint=%s", intent.kind, intent.endpoint)
        return {
            "handled": True,
            "reason": "dry-run mode",
            "slackThreadId": slack_thread_id,
            "actionId": None,
            "replyText": base,
            "intent": _serialise(intent),
        }

    # ── 7. Call backend ────────────────────────────────────────
    try:
        result = call_devos_endpoint(intent.endpoint, intent.payload, base_url=base_url)
        action_id = result.get("actionId") or result.get("id")
        reply = _build_reply_from_intent(intent, result=result)
        LOGGER.info("%s succeeded: actionId=%s slackThreadId=%s",
                    intent.endpoint, action_id, slack_thread_id)
        return {
            "handled": True,
            "reason": f"{intent.kind} dispatched",
            "slackThreadId": slack_thread_id,
            "actionId": action_id,
            "replyText": reply,
            "intent": _serialise(intent),
        }
    except RuntimeError as exc:
        LOGGER.warning("%s failed for thread %s: %s",
                       intent.endpoint, slack_thread_id, exc)
        reply = f"DevOS {intent.kind} failed: {exc}"
        return {
            "handled": True,
            "reason": "backend error",
            "slackThreadId": slack_thread_id,
            "actionId": None,
            "replyText": reply,
            "intent": _serialise(intent),
        }


# ─────────────────────────────────────────────────────────────
# CLI entry point (for scripts/run_slack_bridge_mock.sh)
# ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import sys

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [slack-bridge] %(levelname)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S",
    )

    if len(sys.argv) < 2:
        print("Usage: python slack_bridge.py '<event_json>'", file=sys.stderr)
        sys.exit(1)

    raw = sys.argv[1]
    try:
        ev = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"ERROR: invalid JSON: {e}", file=sys.stderr)
        sys.exit(1)

    outcome = handle_slack_event(ev)
    print(json.dumps(outcome, indent=2))
    if outcome.get("handled") and "failed" in outcome.get("replyText", "").lower():
        sys.exit(1)

