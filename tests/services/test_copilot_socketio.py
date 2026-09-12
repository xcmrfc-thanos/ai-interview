import json
import threading
import time

from flask import Flask
from flask_socketio import SocketIO

from models import db, import_workspace_models, utc_now
from models.Applicant import Applicant
from models.CopilotSession import CopilotSession
from models.InterviewPlan import InterviewPlan
from models.Resume import Resume
from models.User import User
from services.answer_service import AnswerService
from services.asr_service import AsrResult
from utils.copilot_socketio import register_copilot_handlers


class FakeProvider:
    def __init__(self):
        self.callback = None
        self.last_sequence = -1
        self.closed = False

    def subscribe(self, _session_id, callback):
        self.callback = callback

    def start_session(self, _session_id):
        return None

    def restore_sequence(self, _session_id, sequence):
        self.last_sequence = sequence

    def send_audio(self, _session_id, sequence, _payload):
        self.last_sequence = sequence
        self.callback(AsrResult("请介绍一下你的 Python 项目？", is_final=True, version=1))
        return True

    def pause(self, _session_id):
        return None

    def finish(self, _session_id):
        return []

    def close(self, _session_id):
        self.closed = True


class SlowProvider(FakeProvider):
    def send_audio(self, _session_id, sequence, _payload):
        time.sleep(0.05)
        self.last_sequence = sequence
        return True


class ShortFinalProvider(FakeProvider):
    def send_audio(self, _session_id, sequence, _payload):
        self.last_sequence = sequence
        self.callback(AsrResult("请介绍一下", is_final=True, version=1))
        return True


class FinalOnFinishProvider(FakeProvider):
    def send_audio(self, _session_id, sequence, _payload):
        self.last_sequence = sequence
        return True

    def finish(self, _session_id):
        def publish_final():
            time.sleep(0.1)
            self.callback(AsrResult("请介绍一下你的 Python 项目？", is_final=True, version=1))

        threading.Thread(target=publish_final, daemon=True).start()
        return []


def build_app(provider=None):
    app = Flask(__name__)
    app.secret_key = "test"
    app.config.update(
        TESTING=True,
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
    )
    db.init_app(app)
    import_workspace_models()
    socketio = SocketIO(app, async_mode="threading")
    provider = provider or FakeProvider()
    answer_service = AnswerService(lambda _messages: iter([
        json.dumps({"type": "answer_points", "answer_points": ["说明背景", "突出行动"]}, ensure_ascii=False) + "\n",
        json.dumps({"type": "answer_delta", "text": "我负责订单服务。"}, ensure_ascii=False) + "\n",
        json.dumps({"type": "completed", "follow_up": "准备性能追问", "knowledge_item_ids": []}, ensure_ascii=False) + "\n",
    ]))
    register_copilot_handlers(
        socketio,
        provider_factory=lambda: provider,
        answer_service=answer_service,
    )
    with app.app_context():
        db.create_all()
        user = User(user_id=1, email="candidate@example.com", password="x", role="applicant")
        db.session.add(user)
        db.session.flush()
        applicant = Applicant(user_id=1, full_name="测试用户")
        db.session.add(applicant)
        db.session.flush()
        resume = Resume(
            resume_id=11,
            applicant_id=applicant.applicant_id,
            file_url="resume.pdf",
            filename="resume.pdf",
            parsed_data={"skills": ["Python"], "projects": ["订单服务"]},
        )
        db.session.add(resume)
        plan = InterviewPlan(
            plan_id=21,
            user_id=1,
            resume_id=11,
            company_name="星河科技",
            position_name="Python 工程师",
            job_description="负责 Python 服务",
        )
        db.session.add(plan)
        db.session.flush()
        db.session.add(CopilotSession(
            session_id=31,
            user_id=1,
            plan_id=21,
            resume_id=11,
            status="created",
        ))
        db.session.commit()
    return app, socketio, provider


def received_names(client, timeout=1):
    events = []
    deadline = time.time() + timeout
    while time.time() < deadline:
        events.extend(client.get_received())
        names = [event["name"] for event in events]
        if "answer_completed" in names:
            return names
        time.sleep(.01)
    return [event["name"] for event in events]


def wait_for_reference_answer(app, session_id, timeout=1):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with app.app_context():
            record = db.session.get(CopilotSession, session_id)
            answer = record.turns[0].reference_answer if record and record.turns else None
        if answer:
            return answer
        time.sleep(.01)
    return None


def test_socketio_uses_owned_business_session_persists_turn_and_acks_sequence():
    app, socketio, _provider = build_app()
    flask_client = app.test_client()
    with flask_client.session_transaction() as session:
        session["user_id"] = 1
        session["role"] = "applicant"
    client = socketio.test_client(app, flask_test_client=flask_client)

    client.emit("copilot_start", {"session_id": 31})
    client.emit("copilot_audio_frame", {"session_id": 31, "client_sequence": 0, "pcm": b"\x00\x00"})
    # 触发边界（pause）把内存中的最大 sequence 落库，并冲刷批量 ack。
    client.emit("copilot_pause", {"session_id": 31})
    names = received_names(client)

    assert "audio_ack" in names
    assert "transcript_final" in names
    assert "utterance_completed" in names
    assert "answer_started" in names
    assert "answer_delta" in names
    assert "answer_completed" in names
    with app.app_context():
        record = db.session.get(CopilotSession, 31)
        assert record.last_client_sequence == 0
        assert record.current_turn_number == 1
        assert record.turns[0].transcript == "请介绍一下你的 Python 项目？"
        assert record.turns[0].reference_answer == "我负责订单服务。"


def test_socketio_does_not_complete_short_asr_endpoint_as_full_utterance():
    app, socketio, _provider = build_app(ShortFinalProvider())
    flask_client = app.test_client()
    with flask_client.session_transaction() as flask_session:
        flask_session["user_id"] = 1
        flask_session["role"] = "applicant"
    client = socketio.test_client(app, flask_test_client=flask_client)

    client.emit("copilot_start", {"session_id": 31})
    client.emit("copilot_audio_frame", {"session_id": 31, "client_sequence": 0, "pcm": b"\x00\x00"})
    names = received_names(client, timeout=0.1)

    assert "transcript_final" in names
    assert "utterance_completed" not in names
    assert "answer_started" not in names


def test_socketio_rejects_unauthenticated_and_out_of_order_frames():
    app, socketio, provider = build_app()
    anonymous = socketio.test_client(app)
    anonymous.emit("copilot_start", {"session_id": 31})
    assert anonymous.get_received()[-1]["name"] == "error"

    flask_client = app.test_client()
    with flask_client.session_transaction() as session:
        session["user_id"] = 1
        session["role"] = "applicant"
    client = socketio.test_client(app, flask_test_client=flask_client)
    client.emit("copilot_start", {"session_id": 31})
    client.get_received()
    client.emit("copilot_audio_frame", {"session_id": 31, "client_sequence": 2, "pcm": b"\x00\x00"})
    events = client.get_received()

    assert events[-1]["name"] == "error"
    assert provider.last_sequence == -1


def test_socketio_speaker_switch_keeps_candidate_transcript_without_answer():
    app, socketio, provider = build_app()
    flask_client = app.test_client()
    with flask_client.session_transaction() as session:
        session["user_id"] = 1
        session["role"] = "applicant"
    client = socketio.test_client(app, flask_test_client=flask_client)

    client.emit("copilot_start", {"session_id": 31, "sources": ["microphone"]})
    client.get_received()
    client.emit("copilot_set_speaker", {
        "session_id": 31,
        "speaker": "candidate",
    })
    client.get_received()
    provider.callback(AsrResult(
        "我来回答这个问题",
        is_final=True,
        source="microphone",
        speaker="interviewer",
    ))
    events = client.get_received()
    names = [event["name"] for event in events]

    assert "transcript_final" in names
    transcript = next(event for event in events if event["name"] == "transcript_final")
    assert transcript["args"][0]["speaker"] == "candidate"
    assert "utterance_completed" not in names


def test_socketio_serializes_concurrent_audio_frames_per_business_session():
    app, socketio, provider = build_app()
    slow_provider = SlowProvider()
    answer_service = AnswerService(lambda _messages: iter([]))
    register_copilot_handlers(
        socketio,
        provider_factory=lambda: slow_provider,
        answer_service=answer_service,
    )
    flask_client = app.test_client()
    with flask_client.session_transaction() as flask_session:
        flask_session["user_id"] = 1
        flask_session["role"] = "applicant"
    client = socketio.test_client(app, flask_test_client=flask_client)
    client.emit("copilot_start", {"session_id": 31})
    client.get_received()

    threads = [
        threading.Thread(
            target=client.emit,
            args=("copilot_audio_frame", {"session_id": 31, "client_sequence": sequence, "pcm": b"\x00\x00"}),
        )
        for sequence in (0, 1)
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    # 触发边界（pause）把内存中的最大 sequence 落库——热路径不再逐帧 commit。
    client.emit("copilot_pause", {"session_id": 31})
    time.sleep(0.15)
    events = client.get_received()

    assert not any(
        event["name"] == "error" and "乱序" in event["args"][0].get("message", "")
        for event in events
    )
    with app.app_context():
        record = db.session.get(CopilotSession, 31)
        assert record.last_client_sequence == 1


def test_socketio_processes_final_result_before_marking_session_ended():
    app, socketio, _provider = build_app(FinalOnFinishProvider())
    flask_client = app.test_client()
    with flask_client.session_transaction() as flask_session:
        flask_session["user_id"] = 1
        flask_session["role"] = "applicant"
    client = socketio.test_client(app, flask_test_client=flask_client)
    client.emit("copilot_start", {"session_id": 31})
    client.emit("copilot_audio_frame", {"session_id": 31, "client_sequence": 0, "pcm": b"\x00\x00"})

    client.emit("copilot_end", {"session_id": 31})
    with app.app_context():
        record = db.session.get(CopilotSession, 31)
        record.status = "ended"
        record.ended_at = utc_now()
        db.session.commit()
    names = received_names(client, timeout=2)

    assert "answer_completed" in names
    assert wait_for_reference_answer(app, 31) == "我负责订单服务。"
    with app.app_context():
        record = db.session.get(CopilotSession, 31)
        assert record.status == "ended"
        assert len(record.turns) == 1
