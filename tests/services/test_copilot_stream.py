from services.asr_service import AsrResult
from services.copilot_stream import CopilotStream


class FakeProvider:
    def __init__(self):
        self.started = []
        self.audio = []
        self.paused = []
        self.closed = []

    def start_session(self, session_id):
        self.started.append(session_id)

    def send_audio(self, session_id, sequence, payload):
        self.audio.append((session_id, sequence, payload))
        return True

    def pause(self, session_id):
        self.paused.append(session_id)

    def finish(self, session_id):
        return [AsrResult("请介绍项目", is_final=False), AsrResult("请介绍项目", is_final=True)]

    def close(self, session_id):
        self.closed.append(session_id)


class IncrementalProvider(FakeProvider):
    def __init__(self):
        super().__init__()
        self.subscribers = {}

    def subscribe(self, session_id, callback):
        self.subscribers[session_id] = callback


def test_audio_frame_is_forwarded_and_final_transcript_is_emitted():
    provider = FakeProvider()
    emitted = []
    stream = CopilotStream(provider, lambda event, payload: emitted.append((event, payload)))

    stream.start("s1")
    stream.audio_frame("s1", 0, b"pc")
    stream.stop("s1")

    assert provider.audio == [("s1", 0, b"pc")]
    assert emitted[1:3] == [
        ("transcript_partial", {"session_id": "s1", "text": "请介绍项目", "version": 0}),
        ("transcript_final", {"session_id": "s1", "text": "请介绍项目", "version": 0}),
    ]


def test_invalid_audio_payload_emits_error_without_calling_provider():
    provider = FakeProvider()
    emitted = []
    stream = CopilotStream(provider, lambda event, payload: emitted.append((event, payload)))

    stream.start("s1")
    stream.audio_frame("s1", 0, "not-bytes")

    assert provider.audio == []
    assert emitted[-1] == ("error", {"session_id": "s1", "message": "PCM 音频帧无效"})


def test_incremental_provider_result_is_emitted_before_stop():
    provider = IncrementalProvider()
    emitted = []
    handled = []
    stream = CopilotStream(
        provider,
        lambda event, payload: emitted.append((event, payload)),
        result_handler=lambda session_id, result: handled.append((session_id, result.text)),
    )
    stream.start("s1")

    provider.subscribers["s1"](AsrResult("请介绍项目", is_final=False, version=2))

    assert emitted[-1] == (
        "transcript_partial",
        {"session_id": "s1", "text": "请介绍项目", "version": 2},
    )
    assert handled == [("s1", "请介绍项目")]


def test_speaker_switch_keeps_streaming_and_overrides_transcript_metadata():
    provider = IncrementalProvider()
    emitted = []
    handled = []
    stream = CopilotStream(
        provider,
        lambda event, payload: emitted.append((event, payload)),
        result_handler=lambda session_id, result: handled.append((session_id, result.speaker)),
    )
    stream.start("s1", source="microphone")
    stream.set_speaker("s1", "interviewer")

    provider.subscribers["s1"](AsrResult(
        "请介绍项目",
        is_final=False,
        version=3,
        source="microphone",
        speaker="candidate",
    ))
    stream.audio_frame("s1", 0, b"pc", source="microphone")

    assert emitted[-1] == (
        "transcript_partial",
        {
            "session_id": "s1",
            "text": "请介绍项目",
            "version": 3,
            "source": "microphone",
            "speaker": "interviewer",
            "confidence": None,
        },
    )
    assert handled == [("s1", "interviewer")]
    assert provider.audio == [("s1", 0, b"pc")]
