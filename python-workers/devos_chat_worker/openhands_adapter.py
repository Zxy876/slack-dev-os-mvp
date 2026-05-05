"""OpenHands Capability Adapter — DevOS Chat Worker（中断驱动版）。

执行流程（中断驱动，无轮询）：

  阶段 1  SSE 流（POST /stream-start）
  ─────────────────────────────────────
  DevOS 发起请求后挂起（阻塞在 iter_lines）；
  OpenHands 在沙箱就绪时推送每一步状态更新；
  收到 status=READY 时 DevOS 被唤醒，拿到 conv_id 和 agent_server_url。

  阶段 2  WebSocket（ws://<agent_server>/sockets/events/<conv_id>）
  ─────────────────────────────────────────────────────────────────
  DevOS 建立 WebSocket 连接后挂起（阻塞在 ws.recv）；
  OpenHands Agent 执行过程中通过 WebSocket 推送每条事件；
  收到终态事件（finished / stopped / awaiting_user_input…）时 DevOS 被唤醒；
  从事件流中提取最后一条 agent 消息作为结果返回。

认证：可选 X-Session-API-Key 头（对应服务端 SESSION_API_KEY 环境变量）。

降级策略：OpenHands 不可达时返回 ok=False，调用方（worker.py）切换至
          native LLM，并在 Slack 回复里标注 [OpenHands unavailable]。
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from typing import Optional

import requests

try:
    from websockets.sync.client import connect as ws_connect
    _WS_AVAILABLE = True
except ImportError:  # pragma: no cover
    _WS_AVAILABLE = False
    ws_connect = None  # type: ignore[assignment]

LOGGER = logging.getLogger(__name__)

# ─── 状态常量 ─────────────────────────────────────────────────
# WebSocket 收到 agent_state 为这些值时代表"本轮对话结束"
_AGENT_TERMINAL_STATES: frozenset[str] = frozenset(
    {
        "finished",
        "stopped",
        "awaiting_user_input",
        "awaiting_user_confirmation",
        "error",
        "rejected",
        "rate_limited",
    }
)


# ─── 结果数据类 ────────────────────────────────────────────────
@dataclass
class AdapterResult:
    """OpenHands Adapter 的调用结果。"""

    response: str
    capability_source: str  # "openhands.core" | "devos.native"
    conversation_id: Optional[str] = None
    ok: bool = True
    error: Optional[str] = None
    metadata: dict = field(default_factory=dict)

    def format_footer(self) -> str:
        """生成可附在 Slack 回复末尾的能力来源标注。"""
        if not self.ok:
            return f"\n\n_[capability_source: {self.capability_source} | status: error]_"
        short_id = (self.conversation_id or "")[:8]
        conv_note = f" | conv:{short_id}" if short_id else ""
        return f"\n\n_[capability_source: {self.capability_source}{conv_note}]_"


# ─── 主适配器类 ────────────────────────────────────────────────
class OpenHandsAdapter:
    """DevOS → OpenHands HTTP Capability Adapter.

    用法：
        adapter = OpenHandsAdapter(base_url="http://localhost:3000")
        if adapter.is_available():
            result = adapter.run_task("帮我分析这段代码")
            if result.ok:
                print(result.response)
    """

    def __init__(
        self,
        base_url: str,
        session_api_key: Optional[str] = None,
        max_wait_secs: float = 300.0,
        connect_timeout: float = 5.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.max_wait_secs = max_wait_secs
        self.connect_timeout = connect_timeout
        self._headers: dict[str, str] = {
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if session_api_key:
            self._headers["X-Session-API-Key"] = session_api_key

    # ── 公开接口 ───────────────────────────────────────────────

    def is_available(self) -> bool:
        """探测 OpenHands 是否可达（调用 /health 端点）。"""
        try:
            resp = requests.get(
                f"{self.base_url}/health",
                timeout=self.connect_timeout,
            )
            return resp.status_code == 200
        except Exception:
            return False

    def run_task(self, text: str) -> AdapterResult:
        """将用户文本提交给 OpenHands，中断驱动等待 agent 完成并返回回复。

        阶段 1：POST /stream-start（SSE）→ DevOS 挂起，等 READY 通知
        阶段 2：WebSocket 连接 agent server → DevOS 挂起，等终态事件
        阶段 3：从事件流提取最后一条 agent 消息
        """
        try:
            # 阶段 1：SSE streaming → 挂起直到 READY
            conv_id, agent_server_url = self._stream_start(text)
            if not conv_id:
                return self._err("OpenHands start stream did not reach READY")

            # 阶段 2：WebSocket → 挂起直到 agent 终态，同时收集 agent 消息
            response = self._ws_wait_and_collect(conv_id, agent_server_url)

            if response is None:
                # WebSocket 不可用（库未安装 / 连接失败）→ 降级到 REST 提取
                LOGGER.warning(
                    "[openhands-adapter] WebSocket unavailable, "
                    "falling back to REST event fetch for conv=%s",
                    conv_id[:8],
                )
                response = self._extract_response_rest(conv_id)

            return AdapterResult(
                response=response,
                capability_source="openhands.core",
                conversation_id=conv_id,
                ok=bool(response),
                error=None if response else "No agent response found in events",
            )

        except Exception as exc:
            LOGGER.warning("[openhands-adapter] run_task unexpected error: %s", exc)
            return self._err(str(exc))

    # ── 内部实现 ───────────────────────────────────────────────

    def _stream_start(self, text: str) -> tuple[Optional[str], Optional[str]]:
        """阶段 1：POST /api/v1/app-conversations/stream-start（SSE 长连接）。

        DevOS 提交任务后挂起，阻塞读取 response.iter_lines()。
        OpenHands 每推送一条状态更新（JSON 对象），DevOS 被唤醒一次，
        处理完后再次挂起。收到 status=READY 时 DevOS 最终被唤醒并返回。

        返回：(conv_id, agent_server_url) 或 (None, None)。
        """
        import json

        payload: dict = {
            "initial_message": {
                "content": [{"type": "text", "text": text}]
            }
        }
        try:
            resp = requests.post(
                f"{self.base_url}/api/v1/app-conversations/stream-start",
                json=payload,
                headers={**self._headers, "Accept": "application/json"},
                stream=True,            # 保持连接不断开
                timeout=self.max_wait_secs,
            )
            if not resp.ok:
                LOGGER.warning(
                    "[openhands-adapter] stream-start HTTP %s", resp.status_code
                )
                return None, None

            # 逐行解析流式 JSON 数组：[ {task1} ,\n {task2} ,\n ... ]
            # model_dump_json() 无 indent → 每个任务为单行紧凑 JSON
            for raw_line in resp.iter_lines():
                if not raw_line:
                    continue
                line = raw_line.strip()
                if line in (b"[", b"]", "[", "]"):
                    continue
                # 去掉行首可能的逗号（流式数组分隔符）
                if isinstance(line, bytes):
                    line = line.lstrip(b",").strip()
                else:
                    line = line.lstrip(",").strip()
                if not line:
                    continue
                try:
                    task = json.loads(line)
                except json.JSONDecodeError:
                    continue

                status = task.get("status", "")
                LOGGER.debug(
                    "[openhands-adapter][SSE] start-task status=%s", status
                )
                if status == "READY":
                    conv_id = task.get("app_conversation_id")
                    agent_server_url = task.get("agent_server_url")
                    LOGGER.info(
                        "[openhands-adapter] SSE READY: conv_id=%s agent=%s",
                        conv_id,
                        agent_server_url,
                    )
                    return conv_id, agent_server_url
                if status == "ERROR":
                    LOGGER.warning(
                        "[openhands-adapter] SSE ERROR: %s", task.get("detail")
                    )
                    return None, None

            LOGGER.warning("[openhands-adapter] SSE stream ended without READY")
            return None, None

        except Exception as exc:
            LOGGER.warning("[openhands-adapter] _stream_start failed: %s", exc)
            return None, None

    def _ws_wait_and_collect(
        self,
        conv_id: str,
        agent_server_url: Optional[str],
    ) -> Optional[str]:
        """阶段 2：WebSocket 中断等待。

        DevOS 连接 agent server WebSocket 后挂起（阻塞在 ws.recv()）。
        OpenHands Agent 每产生一个事件就推送一条消息，唤醒 DevOS 一次：
          - source=agent 的消息：记录为候选回复
          - agent_state 达到终态：DevOS 被最终唤醒，返回最后一条 agent 消息

        WebSocket URL：ws://<agent_server_host>/sockets/events/<conv_id>

        若 websockets 库不可用或连接失败，返回 None（调用方降级到 REST）。
        """
        import json

        if not _WS_AVAILABLE or ws_connect is None:
            return None

        # 构建 WebSocket URL
        ws_url = self._build_ws_url(agent_server_url, conv_id)
        if not ws_url:
            return None

        LOGGER.info("[openhands-adapter] connecting WebSocket: %s", ws_url)
        last_agent_msg: str = ""

        try:
            ws_headers = {}
            if self._headers.get("X-Session-API-Key"):
                ws_headers["X-Session-API-Key"] = self._headers["X-Session-API-Key"]

            with ws_connect(
                ws_url,
                additional_headers=ws_headers,
                open_timeout=self.connect_timeout,
                close_timeout=5,
            ) as ws:
                while True:
                    try:
                        raw = ws.recv(timeout=self.max_wait_secs)
                    except TimeoutError:
                        LOGGER.warning(
                            "[openhands-adapter] WebSocket recv timed out for conv=%s",
                            conv_id[:8],
                        )
                        break

                    if not raw:
                        continue

                    try:
                        msg = json.loads(raw)
                    except json.JSONDecodeError:
                        continue

                    if not isinstance(msg, dict):
                        continue

                    # 提取 agent 消息（多种字段格式兼容）
                    if msg.get("source") == "agent":
                        text = _extract_text_from_event(msg)
                        if text:
                            last_agent_msg = text
                            LOGGER.debug(
                                "[openhands-adapter][WS] agent msg: %s…",
                                text[:60],
                            )

                    # 检查终态（ConversationStateUpdateEvent / AgentStateChanged）
                    agent_state = (
                        msg.get("agent_state")
                        or (msg.get("extras") or {}).get("agent_state")
                        or ""
                    ).lower()

                    if agent_state in _AGENT_TERMINAL_STATES:
                        LOGGER.info(
                            "[openhands-adapter] WebSocket interrupt: agent_state=%s conv=%s",
                            agent_state,
                            conv_id[:8],
                        )
                        break  # ← 中断！DevOS 被唤醒

        except Exception as exc:
            LOGGER.warning(
                "[openhands-adapter] WebSocket error for conv=%s: %s",
                conv_id[:8],
                exc,
            )
            return None  # 触发 REST 降级

        return last_agent_msg or ""

    def _build_ws_url(
        self, agent_server_url: Optional[str], conv_id: str
    ) -> Optional[str]:
        """将 agent_server_url（http://host:port）转换为 WebSocket URL。

        格式：ws://<host:port>/sockets/events/<conv_id>
        """
        if not agent_server_url:
            # 无 agent_server_url 时，从 base_url 推导
            agent_server_url = self.base_url

        try:
            from urllib.parse import urlparse, urlunparse
            parsed = urlparse(agent_server_url)
            scheme = "wss" if parsed.scheme == "https" else "ws"
            ws_url = urlunparse((
                scheme,
                parsed.netloc,
                f"/sockets/events/{conv_id}",
                "",
                "",
                "",
            ))
            return ws_url
        except Exception as exc:
            LOGGER.warning("[openhands-adapter] _build_ws_url failed: %s", exc)
            return None

    def _extract_response_rest(self, conv_id: str) -> str:
        """REST 降级：从 events API 提取最后一条 agent 消息。

        仅在 WebSocket 不可用时调用。
        """
        try:
            resp = requests.get(
                f"{self.base_url}/api/v1/conversation/{conv_id}/events/search",
                params={"sort_order": "TIMESTAMP_DESC", "limit": 20},
                headers=self._headers,
                timeout=15,
            )
            if not resp.ok:
                return ""

            data = resp.json()
            events = data.get("items", data) if isinstance(data, dict) else data

            for event in events:
                if not isinstance(event, dict):
                    continue
                if event.get("source", "") != "agent":
                    continue
                text = _extract_text_from_event(event)
                if text:
                    return text
            return ""
        except Exception as exc:
            LOGGER.warning("[openhands-adapter] _extract_response_rest error: %s", exc)
            return ""

    @staticmethod
    def _err(msg: str) -> AdapterResult:
        return AdapterResult(
            response="",
            capability_source="openhands.core",
            ok=False,
            error=msg,
        )


# ─── 纯函数：从事件 dict 中提取文本 ────────────────────────────
def _extract_text_from_event(event: dict) -> str:
    """从 OpenHands Event dict 中提取文本内容（多字段名兼容）。"""
    content = (
        event.get("message")
        or event.get("text")
        or event.get("content")
    )
    if isinstance(content, list):
        for block in content:
            if isinstance(block, dict) and block.get("type") == "text":
                text = block.get("text", "").strip()
                if text:
                    return text
    if isinstance(content, str):
        return content.strip()
    return ""


# ─── 模块级单例 ────────────────────────────────────────────────
_adapter: Optional[OpenHandsAdapter] = None


def get_openhands_adapter() -> Optional[OpenHandsAdapter]:
    """返回模块级 Adapter 单例；若未配置 OPENHANDS_URL 则返回 None。

    环境变量：
      OPENHANDS_URL              — OpenHands 服务地址，例如 http://localhost:3000
      OPENHANDS_SESSION_API_KEY  — 可选，对应服务端 SESSION_API_KEY
    """
    global _adapter
    if _adapter is not None:
        return _adapter

    url = os.environ.get("OPENHANDS_URL", "").strip()
    if not url:
        return None

    api_key = os.environ.get("OPENHANDS_SESSION_API_KEY", "").strip() or None
    _adapter = OpenHandsAdapter(base_url=url, session_api_key=api_key)
    LOGGER.info("[openhands-adapter] initialized with base_url=%s", url)
    return _adapter


def reset_adapter() -> None:
    """重置单例（供测试使用）。"""
    global _adapter
    _adapter = None
