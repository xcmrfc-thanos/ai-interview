"""Stream structured Copilot answer events with a plain-text fallback."""

import json
import re
import time


SYSTEM_PROMPT = """你是个人求职者的实时面试回答助手。
只可使用给定简历事实、岗位信息、最近对话和知识片段；不得虚构项目、职责、数字或技术经历。
实时场景，速度优先：第一行必须立即输出要点，不要任何前言或思考。
逐行输出 NDJSON，不要使用 Markdown 代码块：
1. 第一行 {"type":"answer_points","answer_points":["3 至 5 条简短要点"]}
2. 多行 {"type":"answer_delta","text":"参考回答片段"}
3. {"type":"completed","follow_up":"追问准备","knowledge_item_ids":[1,2]}
若简历没有实践，明确使用“理论理解”或“如果由我设计”的口径。"""

NUMBER_PATTERN = re.compile(r"\d+(?:\.\d+)?%?")

DELTA_PIECE_SIZE = 4
DELTA_EMIT_INTERVAL = 0.03


class AnswerService:
    def __init__(self, stream_complete, emit_interval=DELTA_EMIT_INTERVAL, fast_points=True):
        self.stream_complete = stream_complete
        self.emit_interval = emit_interval
        # P2 优化：LLM 首字节前先发占位要点，用户立即看到反馈而不是干等。
        self.fast_points = fast_points
        self._active = {}

    def cancel(self, session_id):
        token = self._active.get(str(session_id))
        if token:
            token["cancelled"] = True

    def generate(self, session_id, context, emit):
        key = str(session_id)
        self.cancel(key)
        token = {"cancelled": False}
        self._active[key] = token
        result = _empty_result()
        raw_chunks = []
        buffer = ""
        structured_seen = False
        completed_seen = False
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": context.get("text", "")},
        ]
        context_text = context.get("text", "")
        ttfb_ms = None
        started_ms = time.monotonic()

        try:
            for chunk in self.stream_complete(messages):
                if token["cancelled"]:
                    result["cancelled"] = True
                    return result
                if ttfb_ms is None:
                    ttfb_ms = int((time.monotonic() - started_ms) * 1000)
                    if self.fast_points and not result["answer_points"]:
                        # 首字节到达：立即给占位反馈，正式要点随后覆盖。
                        emit("answer_started", {"session_id": key, "answer_points": ["正在组织回答…"]})
                text = str(chunk or "")
                raw_chunks.append(text)
                buffer += text
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    event = _parse_event(line)
                    if not event:
                        continue
                    structured_seen = True
                    completed_seen = self._apply_event(
                        key, event, result, emit, context_text, self.emit_interval
                    ) or completed_seen
        except TimeoutError:
            result["error_code"] = "LLM_TIMEOUT"
        except (RuntimeError, ValueError, OSError):
            result["error_code"] = "LLM_STREAM_ERROR"
        except Exception:
            # 第三方 OpenAI 兼容客户端异常不一定继承标准 IO/RuntimeError；
            # 即使供应商异常，也要向前端发送完成事件，避免界面永久停在加载态。
            result["error_code"] = "LLM_STREAM_ERROR"
        finally:
            if self._active.get(key) is token:
                self._active.pop(key, None)

        if token["cancelled"]:
            result["cancelled"] = True
            return result
        if buffer.strip():
            event = _parse_event(buffer)
            if event:
                structured_seen = True
                completed_seen = self._apply_event(
                    key, event, result, emit, context_text, self.emit_interval
                ) or completed_seen
        if not structured_seen:
            result["reference_answer"] = "".join(raw_chunks).strip()
            result["error_code"] = result["error_code"] or "STRUCTURED_OUTPUT_FALLBACK"
        _guard_unverified_numbers(result, context_text)
        _publish_reference(key, result, emit)
        payload = _completed_payload(key, result, partial=bool(result["error_code"]))
        payload["timing"] = {
            "ttfb_ms": ttfb_ms,
            "total_ms": int((time.monotonic() - started_ms) * 1000),
        }
        emit("answer_completed", payload)
        result.pop("_pending_deltas", None)
        return result

    @staticmethod
    def _apply_event(session_id, event, result, emit, context_text, interval):
        event_type = event.get("type")
        if event_type == "answer_points":
            points = event.get("answer_points")
            raw_points = [
                _normalize_point(item) for item in points
                if _normalize_point(item)
            ] if isinstance(points, list) else []
            result["answer_points"] = [
                point for point in raw_points
                if not _unsupported_numbers(point, context_text)
            ]
            emit("answer_started", {"session_id": session_id, "answer_points": result["answer_points"]})
        elif event_type == "answer_delta":
            text = str(event.get("text") or "")
            result["reference_answer"] += text
            result["_pending_deltas"].append(text)
            _emit_delta_pieces(session_id, text, result, emit, context_text, interval)
        elif event_type == "completed":
            result["follow_up"] = str(event.get("follow_up") or "")
            ids = event.get("knowledge_item_ids")
            result["knowledge_item_ids"] = ids if isinstance(ids, list) else []
            return True
        return False


def _parse_event(line):
    content = str(line or "").strip()
    start = content.find("{")
    end = content.rfind("}")
    if start < 0 or end < start:
        return None
    try:
        value = json.loads(content[start:end + 1])
    except (json.JSONDecodeError, AttributeError):
        return None
    return value if isinstance(value, dict) and value.get("type") else None


def _emit_delta_pieces(session_id, text, result, emit, context_text, interval):
    """逐片实时推送参考回答，产生打字机效果。

    每片先做未核查数字预检，保证守卫语义不变（未核查数字不会闪现）；
    若末尾事实守卫触发，answer_completed 仍会用守卫后文本整体覆盖。
    """
    for start in range(0, len(text), DELTA_PIECE_SIZE):
        piece = text[start:start + DELTA_PIECE_SIZE]
        if not piece or _unsupported_numbers(piece, context_text):
            continue
        emit("answer_delta", {"session_id": session_id, "text": piece})
        if interval:
            time.sleep(interval)


def _normalize_point(value):
    return re.sub(r"^\s*\d+\s*[.、)]\s*", "", str(value or "")).strip()


def _empty_result():
    return {
        "answer_points": [],
        "reference_answer": "",
        "follow_up": "",
        "knowledge_item_ids": [],
        "error_code": None,
        "cancelled": False,
        "fact_guard_triggered": False,
        "_pending_deltas": [],
    }


def _completed_payload(session_id, result, partial):
    return {
        "session_id": session_id,
        "answer_points": result["answer_points"],
        "reference_answer": result["reference_answer"],
        "follow_up": result["follow_up"],
        "knowledge_item_ids": result["knowledge_item_ids"],
        "error_code": result["error_code"],
        "fact_guard_triggered": result["fact_guard_triggered"],
        "partial": partial,
    }


def _unsupported_numbers(text, context_text):
    source = {value.rstrip("%") for value in NUMBER_PATTERN.findall(context_text or "")}
    generated = {value.rstrip("%") for value in NUMBER_PATTERN.findall(text or "")}
    return generated - source


def _guard_unverified_numbers(result, context_text):
    if not _unsupported_numbers(result["reference_answer"], context_text):
        return
    points = [
        point for point in result["answer_points"]
        if not _unsupported_numbers(point, context_text)
    ]
    result["answer_points"] = points
    bullets = "\n".join(f"- {point}" for point in points)
    if not bullets:
        bullets = "- 先说明可核查的背景\n- 再解释判断、行动和验证方式"
    result["reference_answer"] = (
        "建议按以下要点组织，并仅补充简历中可核查的事实：\n"
        f"{bullets}\n"
        "如需举例，请使用本人确认的项目、职责和数据。"
    )
    result["error_code"] = "FACT_GUARD_FALLBACK"
    result["fact_guard_triggered"] = True


def _publish_reference(session_id, result, emit):
    pending = result.get("_pending_deltas") or []
    if result["fact_guard_triggered"]:
        if result["reference_answer"]:
            emit("answer_delta", {"session_id": session_id, "text": result["reference_answer"]})
        return
    if not pending and result["reference_answer"]:
        emit("answer_delta", {"session_id": session_id, "text": result["reference_answer"]})
