"""Authenticated Socket.IO bridge for persistent Copilot sessions."""

import threading
import time

from flask import current_app, request, session
from models import db, utc_now
from models.CopilotEvent import CopilotEvent
from models.CopilotSession import CopilotSession
from models.CopilotTurn import CopilotTurn
from services.answer_service import AnswerService
from services.asr_service import MicaVoiceProvider
from services.context_service import ContextService
from services.copilot_stream import CopilotStream
from services.sherpa_asr_client import create_asr_client
from services.utterance_service import UtteranceService
from utils.llm_client import chat_complete


MAX_PCM_FRAME_BYTES = 64 * 1024
VALID_SOURCES = {"microphone", "system", "mixed"}
VALID_MODES = {"auto", "local", "remote"}
VALID_SPEAKERS = {"candidate", "interviewer"}


def register_copilot_handlers(socketio, provider_factory=None, answer_service=None):
    provider_factory = provider_factory or (
        lambda: MicaVoiceProvider(lambda source="mixed": create_asr_client(source=source))
    )
    answer_service = answer_service or AnswerService(_stream_llm)
    context_service = ContextService()
    utterance_service = UtteranceService()
    streams = {}
    sequence_locks = {}
    final_processed_events = {}
    source_sequences = {}
    session_sources = {}
    sequence_locks_guard = threading.Lock()
    ack_batch = _AckBatch(
        socketio,
        flush_interval=0.2,
        max_pending=20,
    )
    pending_sequence_updates = {}

    def _record_event_stub(record, sequence):
        """热路径：只在内存里记最大 sequence，不触碰数据库。"""
        if sequence > pending_sequence_updates.get(record.session_id, -1):
            pending_sequence_updates[record.session_id] = sequence

    def _flush_sequence_updates(record):
        """边界路径（pause/end/disconnect）：把内存里的最大 sequence 落库一次。"""
        sequence = pending_sequence_updates.pop(record.session_id, None)
        if sequence is not None and record.status != "ended":
            record.last_client_sequence = max(record.last_client_sequence, sequence)
            db.session.commit()
        ack_batch.flush()

    def send(client_id, event, payload):
        socketio.emit(event, payload, to=client_id)

    def get_stream(client_id):
        stream = streams.get(client_id)
        if stream is None:
            app = current_app._get_current_object()
            stream = CopilotStream(
                provider_factory(),
                lambda event, payload: send(client_id, event, payload),
                result_handler=lambda business_id, result: socketio.start_background_task(
                    _process_result_after_sequence,
                    sequence_lock(business_id),
                    final_processed_event(business_id) if result.is_final else None,
                    app,
                    client_id,
                    business_id,
                    result,
                    socketio,
                    answer_service,
                    context_service,
                    utterance_service,
                ),
            )
            streams[client_id] = stream
        return stream

    @socketio.on("copilot_start")
    def handle_start(data):
        record = _socket_session(data)
        if not record:
            return
        if record.status == "ended":
            _send_error(record.session_id, "会话已结束")
            return
        try:
            mode = _normalize_mode(data.get("mode") if isinstance(data, dict) else None)
            sources = _requested_sources(data)
            speaker = _normalize_speaker(
                data.get("speaker") if isinstance(data, dict) else None,
                strict=True,
            )
            session_sources[record.session_id] = sources
            for source in sources:
                resume_sequence = record.last_client_sequence
                if isinstance(data, dict) and isinstance(data.get("source_sequences"), dict):
                    resume_sequence = data["source_sequences"].get(source, -1)
                if not isinstance(resume_sequence, int):
                    resume_sequence = -1
                source_sequences[(record.session_id, source)] = resume_sequence
                get_stream(request.sid).start(record.session_id, resume_sequence, source, speaker=speaker)
            if speaker is None:
                get_stream(request.sid).set_speaker(record.session_id, None)
            _record_event(
                record,
                "capture_started",
                record.last_client_sequence,
                {"mode": mode, "sources": sources, "speaker": speaker or "auto"},
            )
            record.status = "running"
            db.session.commit()
        except (RuntimeError, OSError, ValueError) as error:
            _send_error(record.session_id, f"ASR 连接失败: {error}")

    @socketio.on("copilot_set_speaker")
    def handle_set_speaker(data):
        record = _socket_session(data)
        if not record or record.status in {"ended", "ending"}:
            return
        if record.status != "running":
            _send_error(record.session_id, "会话当前未在采集中")
            return
        try:
            speaker = _normalize_speaker(data.get("speaker") if isinstance(data, dict) else None, strict=True)
            source = None
            if isinstance(data, dict) and data.get("source") is not None:
                source = _normalize_source(data.get("source"), strict=True)
            get_stream(request.sid).set_speaker(record.session_id, speaker, source)
            _record_event(
                record,
                "speaker_changed",
                record.last_client_sequence,
                {"speaker": speaker or "auto", "source": source or "all"},
            )
        except (ValueError, RuntimeError) as error:
            _send_error(record.session_id, f"角色切换失败: {error}")

    @socketio.on("copilot_audio_frame")
    def handle_audio(data):
        business_id = _session_id(data)
        with sequence_lock(business_id):
            record = _socket_session(data)
            if not record:
                return
            payload = data.get("pcm") if isinstance(data, dict) else None
            sequence = data.get("client_sequence") if isinstance(data, dict) else None
            try:
                source = _normalize_source(data.get("source") if isinstance(data, dict) else None, strict=True)
            except ValueError as error:
                _send_error(record.session_id, str(error))
                return
            source_sequence = data.get("source_sequence", sequence) if isinstance(data, dict) else sequence
            if record.status != "running":
                _send_error(record.session_id, "会话当前未在采集中")
                return
            if not isinstance(sequence, int) or not isinstance(source_sequence, int) or not _valid_pcm(payload):
                _send_error(record.session_id, "PCM 音频帧或序列号无效")
                return
            key = (record.session_id, source)
            last_source_sequence = source_sequences.get(key, -1)
            if source_sequence <= last_source_sequence:
                socketio.emit(
                    "audio_ack",
                    {"session_id": record.session_id, "client_sequence": sequence, "source": source, "duplicate": True},
                    to=request.sid,
                )
                return
            expected = last_source_sequence + 1
            if source_sequence != expected:
                _record_event(record, "audio_sequence_error", sequence, {"source": source, "expected": expected})
                _send_error(record.session_id, f"{source} 音频序列乱序，期望 {expected}，收到 {source_sequence}")
                return
            if get_stream(request.sid).audio_frame(record.session_id, source_sequence, payload, source):
                source_sequences[key] = source_sequence
                ack_batch.record(record.session_id, sequence, source)
                _record_event_stub(record, sequence)

    @socketio.on("copilot_pause")
    def handle_pause(data):
        record = _socket_session(data)
        if not record or record.status == "ended":
            return
        get_stream(request.sid).pause(record.session_id)
        record.status = "paused"
        _flush_sequence_updates(record)
        db.session.commit()

    @socketio.on("copilot_resume")
    def handle_resume(data):
        record = _socket_session(data)
        if not record or record.status == "ended":
            return
        try:
            for source in session_sources.get(record.session_id, {"mixed"}):
                resume_sequence = source_sequences.get((record.session_id, source), -1)
                get_stream(request.sid).start(record.session_id, resume_sequence, source)
            record.status = "running"
            db.session.commit()
        except (RuntimeError, OSError, ValueError) as error:
            _send_error(record.session_id, f"ASR 恢复失败: {error}")

    @socketio.on("copilot_end")
    def handle_end(data):
        record = _socket_session(data)
        if not record:
            return
        app = current_app._get_current_object()
        final_event = final_processed_event(record.session_id)
        final_event.clear()
        get_stream(request.sid).stop(record.session_id)
        record.status = "ending"
        _flush_sequence_updates(record)
        db.session.commit()
        socketio.start_background_task(
            _finalize_after_stop,
            app,
            record.session_id,
            sequence_lock(record.session_id),
            final_event,
        )

    @socketio.on("disconnect")
    def handle_disconnect():
        stream = streams.pop(request.sid, None)
        if not stream:
            return
        for business_id in list(stream.sessions):
            stream.close(business_id)
            session_sources.pop(business_id, None)
            for key in [key for key in source_sequences if key[0] == business_id]:
                source_sequences.pop(key, None)
            record = db.session.get(CopilotSession, business_id)
            if record and record.status not in {"ended", "error"}:
                record.status = "reconnecting"
                sequence = pending_sequence_updates.pop(business_id, None)
                if sequence is not None:
                    record.last_client_sequence = max(record.last_client_sequence, sequence)
        db.session.commit()
        ack_batch.flush()

    def _socket_session(data):
        business_id = _session_id(data)
        if not session.get("user_id") or session.get("role") != "applicant":
            _send_error(business_id, "请先登录个人账号")
            return None
        record = CopilotSession.query.filter_by(
            session_id=business_id,
            user_id=session["user_id"],
        ).first()
        if not record:
            _send_error(business_id, "Copilot 会话不存在或无权访问")
        return record

    def _send_error(business_id, message):
        socketio.emit("error", {"session_id": business_id, "message": message}, to=request.sid)

    def sequence_lock(business_id):
        with sequence_locks_guard:
            return sequence_locks.setdefault(business_id, threading.Lock())

    def final_processed_event(business_id):
        with sequence_locks_guard:
            return final_processed_events.setdefault(business_id, threading.Event())


def process_result(
    app,
    client_id,
    business_id,
    result,
    socketio,
    answer_service,
    context_service,
    utterance_service,
):
    with app.app_context():
        record = db.session.get(CopilotSession, business_id)
        if not record:
            return
        accepts_delayed_final = record.status in {"ended", "paused"} and result.is_final
        if record.status not in {"running", "ending"} and not accepts_delayed_final:
            return
        now_ms = int(time.monotonic() * 1000)
        if result.is_final:
            decision = utterance_service.accept_final(
                business_id,
                result.text,
                now_ms,
                speaker=result.speaker,
            )
        else:
            decision = utterance_service.accept_partial(
                business_id,
                result.text,
                now_ms,
                speaker=result.speaker,
            )
        if decision["action"] != "complete":
            if result.is_final and decision["action"] == "ignore":
                _record_event(record, "utterance_ignored", None, decision)
                db.session.commit()
            return

        socketio.emit(
            "utterance_completed",
            {"session_id": record.session_id, "text": decision["text"]},
            to=client_id,
        )
        context = context_service.build_answer_context(record, decision["text"])
        record.current_turn_number += 1
        turn = CopilotTurn(
            session_id=record.session_id,
            turn_number=record.current_turn_number,
            status="answering",
            transcript=decision["text"],
        )
        db.session.add(turn)
        db.session.commit()
        socketio.start_background_task(
            _generate_answer,
            app,
            client_id,
            record.session_id,
            turn.turn_id,
            context,
            socketio,
            answer_service,
        )


def _process_result_after_sequence(sequence_lock, processed_event, *args):
    try:
        with sequence_lock:
            process_result(*args)
    finally:
        if processed_event:
            processed_event.set()


def _generate_answer(app, client_id, business_id, turn_id, context, socketio, answer_service):
    with app.app_context():
        completed_payload = None

        def publish(event, payload):
            nonlocal completed_payload
            enriched = dict(payload, turn_id=turn_id)
            if event == "answer_completed":
                completed_payload = enriched
                return
            socketio.emit(event, enriched, to=client_id)

        result = answer_service.generate(business_id, context, publish)
        turn = db.session.get(CopilotTurn, turn_id)
        if not turn or result["cancelled"]:
            return
        turn.answer_points = result["answer_points"]
        turn.reference_answer = result["reference_answer"]
        turn.follow_up = result["follow_up"]
        turn.knowledge_item_ids = result["knowledge_item_ids"]
        turn.status = "partial" if result["error_code"] else "completed"
        if result["error_code"]:
            db.session.add(CopilotEvent(
                session_id=business_id,
                event_type="answer_error",
                error_code=result["error_code"],
                payload={"turn_id": turn_id},
            ))
        db.session.commit()
        db.session.remove()
        record = db.session.get(CopilotSession, business_id)
        if record and record.status == "ending":
            record.status = "ended"
            record.ended_at = record.ended_at or utc_now()
            db.session.commit()
            db.session.remove()
        if completed_payload:
            socketio.emit("answer_completed", completed_payload, to=client_id)


def _finalize_after_stop(app, business_id, sequence_lock, final_event):
    final_processed = final_event.wait(5)
    with app.app_context(), sequence_lock:
        record = db.session.get(CopilotSession, business_id)
        if not record or record.status != "ending":
            return
        if not final_processed:
            db.session.add(CopilotEvent(
                session_id=business_id,
                event_type="asr_final_timeout",
                error_code="ASR_FINAL_TIMEOUT",
                payload={},
            ))
        record.status = "ended"
        record.ended_at = record.ended_at or utc_now()
        db.session.commit()


def _stream_llm(messages):
    # P2：实时 Copilot 走 copilot 模型分级（LLM_COPILOT_MODEL 未配置时同 LLM_MODEL）。
    stream = chat_complete(messages, kind="copilot", stream=True)
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta.content:
            yield chunk.choices[0].delta.content


def _record_event(record, event_type, sequence, payload):
    """事件落库（低频路径专用：start/pause/end/错误等）。"""
    db.session.add(CopilotEvent(
        session_id=record.session_id,
        event_type=event_type,
        client_sequence=sequence,
        payload=payload,
    ))
    db.session.commit()


def _valid_pcm(payload):
    return (
        isinstance(payload, (bytes, bytearray, memoryview))
        and 0 < len(payload) <= MAX_PCM_FRAME_BYTES
        and len(payload) % 2 == 0
    )


def _session_id(data):
    if not isinstance(data, dict):
        return None
    value = data.get("session_id")
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _normalize_source(source, strict=False):
    value = str(source or "mixed").strip().lower()
    if value in VALID_SOURCES:
        return value
    if strict:
        raise ValueError(f"不支持的音频来源: {value}")
    return "mixed"


def _normalize_mode(mode):
    value = str(mode or "auto").strip().lower()
    if value not in VALID_MODES:
        raise ValueError(f"不支持的采集模式: {value}")
    return value


def _normalize_speaker(speaker, strict=False):
    value = str(speaker or "").strip().lower()
    if not value or value == "auto":
        return None
    if value in VALID_SPEAKERS:
        return value
    if strict:
        raise ValueError(f"不支持的说话人角色: {value}")
    return None


def _requested_sources(data):
    if not isinstance(data, dict):
        return ["mixed"]
    raw = data.get("sources")
    if isinstance(raw, list):
        sources = [_normalize_source(value, strict=True) for value in raw]
        unique = list(dict.fromkeys(sources))
        if unique:
            return unique
    return [_normalize_source(data.get("source"), strict=True)]


class _AckBatch:
    """批量 audio_ack：按窗口/数量合并发送，降低每帧事件开销。

    客户端只用 ack 清理 pendingFrames，批量语义兼容：
    每条 ack 仍带 client_sequence，客户端逐条删除即可。
    """

    def __init__(self, socketio, flush_interval=0.2, max_pending=20):
        self._socketio = socketio
        self._flush_interval = flush_interval
        self._max_pending = max_pending
        self._pending = {}
        self._last_flush = time.monotonic()
        self._lock = threading.Lock()

    def record(self, session_id, sequence, source):
        emit = False
        with self._lock:
            self._pending.setdefault((session_id, source), []).append(sequence)
            emit = (
                len(self._pending) >= self._max_pending
                or time.monotonic() - self._last_flush >= self._flush_interval
            )
        if emit:
            self.flush()

    def flush(self):
        with self._lock:
            pending = self._pending
            self._pending = {}
            self._last_flush = time.monotonic()
        for (session_id, source), sequences in pending.items():
            self._socketio.emit(
                "audio_ack",
                {
                    "session_id": session_id,
                    "client_sequences": sequences,
                    "client_sequence": sequences[-1],
                    "source": source,
                    "duplicate": False,
                },
            )
