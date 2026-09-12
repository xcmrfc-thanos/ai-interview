"""裸 WebSocket Copilot 通道（契约 docs/api-contract.md §6，供 React 前端使用）。

与 utils/copilot_socketio.py 共享同一服务栈（CopilotStream/AnswerService/UtteranceService/
ContextService），仅传输层不同：JSON 文本控制帧 + 二进制 v1/v2 音频帧。
Socket.IO 通道保留为 legacy；两端事件 schema 对齐契约。
"""

import json
import struct
import threading
import time

from flask import current_app, session

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
# v2 帧来源码：0=mixed，1=microphone，2=system
SOURCE_CODES = {"mixed": 0, "microphone": 1, "system": 2}
CODE_SOURCES = {code: name for name, code in SOURCE_CODES.items()}
# 约 500ms 音频队列上限（16kHz mono PCM16，100ms/帧 ≈ 3200B/帧），由前端背压负责


def _stream_llm(messages):
    stream = chat_complete(messages, kind="copilot", stream=True)
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta.content:
            yield chunk.choices[0].delta.content


def _parse_audio_frame(data):
    """契约 §6：v2 = 'AI' + version + source 码 + uint32 LE sequence + PCM。"""
    if isinstance(data, str) or len(data) < 8 or data[0:2] != b"AI":
        return 1, "mixed", -1, data if not isinstance(data, str) else b""
    source = CODE_SOURCES.get(data[3], "mixed")
    sequence = struct.unpack("<I", data[4:8])[0]
    return 2, source, sequence, data[8:]


def register_copilot_ws(app):
    from flask_sock import Sock

    sock = Sock(app)

    @sock.route("/ws/copilot")
    def copilot(ws):
        # 升级请求自带 Cookie；未登录直接关闭（契约 §0）
        if not session.get("user_id") or session.get("role") != "applicant":
            ws.send(json.dumps({"event": "error", "message": "用户未登录"}))
            return
        user_id = session["user_id"]

        provider_factory = lambda: MicaVoiceProvider(  # noqa: E731
            lambda source="mixed": create_asr_client(source=source)
        )
        answer_service = AnswerService(_stream_llm)
        context_service = ContextService()
        utterance_service = UtteranceService()

        send_lock = threading.Lock()
        state = {
            "stream": None,
            "session": None,
            # 帧路径热状态：会话是否处于 running。状态迁移都在本连接的
            # start/pause/resume/end 处理器里发生，这里缓存后帧路径无需每帧查库；
            # state["session"] 只在 handle_start 通过归属校验后才设置。
            "running": False,
            "sources": set(),
            "source_sequences": {},
            "last_sequence": -1,
            "final_event": threading.Event(),
            "sequence_lock": threading.Lock(),
        }

        def send(event, payload):
            message = json.dumps(dict(payload, event=event), ensure_ascii=False)
            with send_lock:
                try:
                    ws.send(message)
                except Exception:
                    pass

        def get_stream():
            if state["stream"] is None:
                state["stream"] = CopilotStream(
                    provider_factory(),
                    lambda event, payload: _translate_stream_event(event, payload),
                    result_handler=lambda business_id, result: threading.Thread(
                        target=_process_result,
                        args=(business_id, result),
                        daemon=True,
                    ).start(),
                )
            return state["stream"]

        def _translate_stream_event(event, payload):
            if event == "session_state":
                # 生命周期事件由控制帧处理器按契约下发（started/paused/…）
                return
            if event == "error":
                send("error", {"session_id": payload.get("session_id"), "message": payload.get("message", "")})
                return
            send(event, payload)

        def load_record(business_id):
            return CopilotSession.query.filter_by(
                session_id=business_id,
                user_id=user_id,
            ).first()

        def _process_result(business_id, result):
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
                        business_id, result.text, now_ms, speaker=result.speaker,
                    )
                else:
                    decision = utterance_service.accept_partial(
                        business_id, result.text, now_ms, speaker=result.speaker,
                    )
                if decision["action"] != "complete":
                    return

                send("utterance_completed", {
                    "session_id": record.session_id,
                    "text": decision["text"],
                    "speaker": result.speaker or "interviewer",
                    "reason": decision.get("reason", "question_feature"),
                })
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
                turn_id = turn.turn_id
                generation_id = time.monotonic_ns()
                send("answer_queued", {
                    "session_id": record.session_id,
                    "turn_id": turn_id,
                    "generation_id": generation_id,
                })
                threading.Thread(
                    target=generate_answer,
                    args=(record.session_id, turn_id, generation_id, context),
                    daemon=True,
                ).start()

        def generate_answer(business_id, turn_id, generation_id, context):
            with app.app_context():
                completed_payload = None

                def publish(event, payload):
                    nonlocal completed_payload
                    enriched = dict(payload, turn_id=turn_id, generation_id=generation_id)
                    enriched.setdefault("session_id", business_id)
                    if event == "answer_completed":
                        completed_payload = enriched
                        return
                    send(event, enriched)

                try:
                    result = answer_service.generate(business_id, context, publish)
                except Exception as error:  # LLM 链路异常不关闭连接
                    send("error", {"session_id": business_id, "message": f"回答生成失败: {error}"})
                    return
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
                    completed_payload["timing"] = {
                        "ttfb_ms": result.get("ttfb_ms"),
                        "total_ms": result.get("total_ms"),
                    }
                    send("answer_completed", completed_payload)

        def flush_sequences(record):
            """边界路径（pause/end/断连）：把最大 sequence 落库一次。"""
            if record is not None and record.status != "ended":
                if state["last_sequence"] > record.last_client_sequence:
                    record.last_client_sequence = state["last_sequence"]
                    db.session.commit()

        def handle_start(data):
            business_id = data.get("session_id")
            record = load_record(business_id) if isinstance(business_id, int) else None
            if record is None:
                send("error", {"message": "Copilot 会话不存在或无权访问"})
                return
            state["session"] = business_id
            state["running"] = False
            if record.status == "ended":
                send("error", {"session_id": business_id, "message": "会话已结束"})
                return
            try:
                mode = str(data.get("mode") or "auto").lower()
                mode = mode if mode in VALID_MODES else "auto"
                raw_sources = data.get("sources")
                sources = []
                if isinstance(raw_sources, list):
                    for value in raw_sources:
                        token = str(value or "mixed").strip().lower()
                        if token in ("microphone", "local"):
                            token = "microphone"
                        elif token in ("system", "remote", "display"):
                            token = "system"
                        elif token == "mixed":
                            pass
                        else:
                            token = "mixed"
                        if token not in sources:
                            sources.append(token)
                state["sources"] = set(sources or ["mixed"])
                speaker = data.get("speaker")
                speaker = speaker if speaker in VALID_SPEAKERS else None

                was_reconnecting = record.status == "reconnecting"
                stream = get_stream()
                for source in sorted(state["sources"]):
                    resume_sequence = -1
                    source_sequences = data.get("source_sequences")
                    if isinstance(source_sequences, dict):
                        value = source_sequences.get(source, -1)
                        resume_sequence = value if isinstance(value, int) else -1
                    state["source_sequences"][source] = resume_sequence
                    stream.start(record.session_id, resume_sequence, source, speaker=speaker)
                if speaker is None:
                    stream.set_speaker(record.session_id, None)
                db.session.add(CopilotEvent(
                    session_id=record.session_id,
                    event_type="capture_started",
                    payload={"mode": mode, "sources": sorted(state["sources"]), "speaker": speaker or "auto"},
                ))
                record.status = "running"
                db.session.commit()
                state["running"] = True
                send("reconnected" if was_reconnecting else "started", {
                    "session_id": record.session_id,
                    "audio_protocol": 2,
                })
            except (RuntimeError, OSError, ValueError) as error:
                send("error", {"session_id": record.session_id, "message": f"ASR 连接失败: {error}"})

        def handle_set_speaker(data):
            business_id = data.get("session_id")
            stream = state["stream"]
            record = load_record(business_id) if isinstance(business_id, int) else None
            if record is None or stream is None:
                return
            if record.status in {"ended", "ending"}:
                return
            speaker = data.get("speaker")
            speaker = speaker if speaker in VALID_SPEAKERS else None
            try:
                stream.set_speaker(record.session_id, speaker)
                send("speaker_changed", {
                    "session_id": record.session_id,
                    "speaker": speaker or "auto",
                    "source": "all",
                })
            except (ValueError, RuntimeError) as error:
                send("error", {"session_id": record.session_id, "message": f"角色切换失败: {error}"})

        def handle_audio_frame(data):
            version, source, sequence, pcm = _parse_audio_frame(data)
            business_id = state["session"]
            if business_id is None or not state["running"]:
                return
            with state["sequence_lock"]:
                if not pcm or len(pcm) > MAX_PCM_FRAME_BYTES or len(pcm) % 2:
                    send("error", {"session_id": business_id, "message": "PCM 音频帧无效"})
                    return
                last_sequence = state["source_sequences"].get(source, -1)
                if sequence <= last_sequence:
                    send("audio_ack", {
                        "session_id": business_id,
                        "sequence": sequence,
                        "source": source,
                        "accepted": False,
                        "reason": "duplicate",
                    })
                    return
                if get_stream().audio_frame(business_id, sequence, pcm, source):
                    state["source_sequences"][source] = sequence
                    if sequence > state["last_sequence"]:
                        state["last_sequence"] = sequence
                    send("audio_ack", {
                        "session_id": business_id,
                        "sequence": sequence,
                        "source": source,
                        "accepted": True,
                    })

        def handle_pause():
            state["running"] = False
            record = load_record(state["session"]) if state["session"] is not None else None
            if record is None or record.status == "ended":
                return
            get_stream().pause(record.session_id)
            record.status = "paused"
            flush_sequences(record)
            send("paused", {"session_id": record.session_id})

        def handle_resume():
            record = load_record(state["session"]) if state["session"] is not None else None
            if record is None or record.status == "ended":
                return
            try:
                stream = get_stream()
                for source in sorted(state["sources"] or {"mixed"}):
                    resume_sequence = state["source_sequences"].get(source, -1)
                    stream.start(record.session_id, resume_sequence, source)
                record.status = "running"
                db.session.commit()
                state["running"] = True
                send("resumed", {"session_id": record.session_id})
            except (RuntimeError, OSError, ValueError) as error:
                send("error", {"session_id": record.session_id, "message": f"ASR 恢复失败: {error}"})

        def handle_end():
            record = load_record(state["session"]) if state["session"] is not None else None
            if record is None:
                return
            stream = get_stream()
            state["running"] = False
            state["final_event"].clear()
            sid_value = int(record.session_id)
            send("ending", {"session_id": sid_value})
            record.status = "ending"
            flush_sequences(record)
            db.session.commit()

            def drain():
                stream.stop(sid_value)
                processed = state["final_event"].wait(5)
                with app.app_context(), state["sequence_lock"]:
                    current = db.session.get(CopilotSession, sid_value)
                    if not current or current.status != "ending":
                        return
                    if not processed:
                        db.session.add(CopilotEvent(
                            session_id=sid_value,
                            event_type="asr_final_timeout",
                            error_code="ASR_FINAL_TIMEOUT",
                            payload={},
                        ))
                    current.status = "ended"
                    current.ended_at = current.ended_at or utc_now()
                    db.session.commit()
                send("ended", {"session_id": sid_value})

            threading.Thread(target=drain, daemon=True).start()

        send("ready", {})
        try:
            while True:
                data = ws.receive()
                if data is None:
                    break
                if isinstance(data, bytes):
                    handle_audio_frame(data)
                    continue
                try:
                    message = json.loads(data)
                except (TypeError, ValueError):
                    send("error", {"message": "无效的控制帧"})
                    continue
                if not isinstance(message, dict):
                    send("error", {"message": "无效的控制帧"})
                    continue
                event = message.get("event")
                if event == "copilot_start":
                    handle_start(message)
                elif event == "copilot_set_speaker":
                    handle_set_speaker(message)
                elif event == "copilot_pause":
                    handle_pause()
                elif event == "copilot_resume":
                    handle_resume()
                elif event == "copilot_end":
                    handle_end()
                else:
                    send("error", {"message": f"未知事件: {event}"})
        finally:
            # 断连：关闭 ASR 会话，会话置 reconnecting 保留 30s 恢复窗口
            state["running"] = False
            stream = state["stream"]
            if stream is not None:
                for business_id in list(stream.sessions):
                    stream.close(business_id)
            record = load_record(state["session"]) if state["session"] is not None else None
            with app.app_context():
                if record is not None and record.status not in {"ended", "error"}:
                    record.status = "reconnecting"
                    if state["last_sequence"] > record.last_client_sequence:
                        record.last_client_sequence = state["last_sequence"]
                    db.session.commit()
