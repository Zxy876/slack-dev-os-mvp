"""
socket_mode_adapter.py — B-023 Slack Socket Mode Adapter
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Connects to Slack via Socket Mode to receive real message events,
passes them through slack_bridge.handle_slack_event(), and posts
replies back via worker.post_to_slack().

No public endpoint. No OAuth install flow. No slash commands.
Local-only development adapter.

Required env vars:
  SLACK_BOT_TOKEN     xoxb-...  (bot OAuth token; scope: chat:write)
  SLACK_APP_TOKEN     xapp-...  (app-level token; scope: connections:write)

Optional env vars:
  ASYNCAIFLOW_URL           default http://localhost:8080
  DEVOS_BRIDGE_PREFIX       default devos:
  DEVOS_DEFAULT_REPO_PATH   required for preview/test/commit intents
  DEVOS_SOCKET_DRY_RUN      default false; true → log only, no backend call

Slack App prerequisites:
  1. Enable Socket Mode (App Settings → Features → Socket Mode)
  2. Create App-Level Token with scope: connections:write
  3. Subscribe to bot events: message.channels (or message.im / message.groups)
  4. Invite the bot to the target channel
"""
from __future__ import annotations

import logging
import os
import signal
import sys
import time
from typing import Optional

from slack_sdk import WebClient
from slack_sdk.socket_mode import SocketModeClient
from slack_sdk.socket_mode.request import SocketModeRequest
from slack_sdk.socket_mode.response import SocketModeResponse

from slack_bridge import handle_slack_event
from worker import post_to_slack

LOGGER = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────
# Token redaction helper
# ─────────────────────────────────────────────────────────────

def redact_token(value: str) -> str:
    """
    Redact a credential value for safe logging.
    Shows at most the first 8 characters followed by ***.
    """
    if not value:
        return "(not set)"
    visible = min(8, len(value) - 3)
    if visible <= 0:
        return "***"
    return value[:visible] + "***"


# ─────────────────────────────────────────────────────────────
# Config builder
# ─────────────────────────────────────────────────────────────

def build_socket_config_from_env() -> dict:
    """
    Read and validate required environment variables.

    Returns a config dict with keys:
      bot_token, app_token, dry_run, base_url, prefix, default_repo_path

    Raises RuntimeError if SLACK_BOT_TOKEN or SLACK_APP_TOKEN is missing.
    """
    bot_token = os.environ.get("SLACK_BOT_TOKEN", "")
    app_token = os.environ.get("SLACK_APP_TOKEN", "")

    errors: list[str] = []
    if not bot_token:
        errors.append(
            "SLACK_BOT_TOKEN is not set (required: xoxb-...; scope: chat:write)"
        )
    if not app_token:
        errors.append(
            "SLACK_APP_TOKEN is not set "
            "(required: xapp-...; Slack App → Socket Mode → App-Level Token; "
            "scope: connections:write)"
        )
    if errors:
        raise RuntimeError(
            "Socket Mode config invalid:\n" + "\n".join(f"  - {e}" for e in errors)
        )

    dry_run = os.environ.get("DEVOS_SOCKET_DRY_RUN", "false").lower() in (
        "1", "true", "yes"
    )
    repo_path = os.environ.get("DEVOS_DEFAULT_REPO_PATH", "").strip() or None

    return {
        "bot_token": bot_token,
        "app_token": app_token,
        "dry_run": dry_run,
        "base_url": os.environ.get("ASYNCAIFLOW_URL", "http://localhost:8080"),
        "prefix": os.environ.get("DEVOS_BRIDGE_PREFIX", "devos:"),
        "default_repo_path": repo_path,
    }


# ─────────────────────────────────────────────────────────────
# Socket Mode request handler
# ─────────────────────────────────────────────────────────────

def handle_socket_mode_request(
    client: SocketModeClient,
    req: SocketModeRequest,
    config: Optional[dict] = None,
) -> None:
    """
    Process one Socket Mode envelope from Slack.

    Always ACKs the envelope first (Slack requires ACK within 3 seconds).
    Only events_api envelopes containing message events are dispatched to
    slack_bridge. All other envelope types are silently ignored after ACK.

    Exceptions from handle_slack_event or post_to_slack are caught and
    logged; the ACK is never blocked by downstream failures.
    """
    # ── ACK first (always, unconditionally) ──────────────────
    client.send_socket_mode_response(
        SocketModeResponse(envelope_id=req.envelope_id)
    )

    # ── Only process events_api envelopes ────────────────────
    if req.type != "events_api":
        LOGGER.debug("Ignored non-events_api envelope: type=%r", req.type)
        return

    payload = req.payload or {}
    event = payload.get("event", {})

    LOGGER.info(
        "Received events_api: event_type=%s channel=%s",
        event.get("type", ""),
        event.get("channel", ""),
    )

    cfg = config or {}
    bridge_config = {
        "base_url":           cfg.get("base_url",
                                      os.environ.get("ASYNCAIFLOW_URL", "http://localhost:8080")),
        "prefix":             cfg.get("prefix",
                                      os.environ.get("DEVOS_BRIDGE_PREFIX", "devos:")),
        "default_repo_path":  cfg.get("default_repo_path") or
                              os.environ.get("DEVOS_DEFAULT_REPO_PATH", "").strip() or None,
        "dry_run":            cfg.get("dry_run",
                                      os.environ.get("DEVOS_SOCKET_DRY_RUN", "false").lower()
                                      in ("1", "true", "yes")),
    }

    # ── Dispatch to bridge ────────────────────────────────────
    try:
        outcome = handle_slack_event(event, config=bridge_config)
    except Exception as exc:  # pragma: no cover — belt-and-suspenders
        LOGGER.error(
            "handle_slack_event raised unexpectedly for channel=%s: %s",
            event.get("channel", ""),
            exc,
            exc_info=True,
        )
        return

    if not outcome.get("handled"):
        LOGGER.debug("Event not handled: %s", outcome.get("reason", ""))
        return

    reply_text     = outcome.get("replyText", "")
    slack_thread_id = outcome.get("slackThreadId", "")

    if not reply_text or not slack_thread_id:
        LOGGER.debug("No reply or thread ID — skipping Slack post")
        return

    if bridge_config["dry_run"]:
        LOGGER.info(
            "[dry-run] Would post to thread %s: %s",
            slack_thread_id,
            reply_text[:120],
        )
        return

    # ── Post reply to Slack ───────────────────────────────────
    try:
        post_to_slack(slack_thread_id, reply_text)
        LOGGER.info("Reply posted to thread %s", slack_thread_id)
    except Exception as exc:
        LOGGER.error(
            "post_to_slack failed for thread %s: %s",
            slack_thread_id,
            exc,
        )


# ─────────────────────────────────────────────────────────────
# Client lifecycle
# ─────────────────────────────────────────────────────────────

def start_socket_mode_client(config: Optional[dict] = None) -> None:
    """
    Initialise the Slack Socket Mode client and block until interrupted.

    config — optional pre-built config dict (used in tests). If None,
             build_socket_config_from_env() is called.
    """
    if config is None:
        config = build_socket_config_from_env()

    LOGGER.info(
        "Starting Socket Mode adapter | bot=%s app=%s dry_run=%s prefix=%r",
        redact_token(config["bot_token"]),
        redact_token(config["app_token"]),
        config["dry_run"],
        config["prefix"],
    )

    web_client    = WebClient(token=config["bot_token"])
    socket_client = SocketModeClient(
        app_token=config["app_token"],
        web_client=web_client,
    )

    def _listener(client: SocketModeClient, req: SocketModeRequest) -> None:
        handle_socket_mode_request(client, req, config=config)

    socket_client.socket_mode_request_listeners.append(_listener)
    socket_client.connect()

    LOGGER.info(
        "Socket Mode connected. Listening for Slack events (prefix=%r)…",
        config["prefix"],
    )
    LOGGER.info("Press Ctrl+C to stop.")

    def _shutdown(signum: int, frame: object) -> None:
        LOGGER.info("Received signal %d — shutting down Socket Mode adapter…", signum)
        socket_client.close()
        sys.exit(0)

    signal.signal(signal.SIGINT,  _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        LOGGER.info("KeyboardInterrupt — disconnecting…")
        socket_client.close()


# ─────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [socket-adapter] %(levelname)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S",
    )
    start_socket_mode_client()
