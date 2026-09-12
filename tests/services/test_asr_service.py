import pytest

from services.asr_service import AsrResult, MicaVoiceProvider


class FakeClient:
    def __init__(self):
        self.sent = []
        self.closed = False
        self.result_callback = None

    def set_result_callback(self, callback):
        self.result_callback = callback

    def start(self):
        return None

    def send_audio(self, payload):
        self.sent.append(payload)

    def pause(self):
        return None

    def finish(self):
        return [AsrResult(text="问题", is_final=True, version=1)]

    def close(self):
        self.closed = True


def test_provider_ignores_duplicate_audio_sequence():
    client = FakeClient()
    provider = MicaVoiceProvider(lambda: client)
    provider.start_session("s1")

    provider.send_audio("s1", 0, b"a")
    provider.send_audio("s1", 0, b"a-duplicate")

    assert client.sent == [b"a"]


def test_provider_rejects_out_of_order_audio_sequence():
    provider = MicaVoiceProvider(FakeClient)
    provider.start_session("s1")
    provider.send_audio("s1", 0, b"a")

    with pytest.raises(ValueError, match="audio sequence"):
        provider.send_audio("s1", 2, b"c")


def test_provider_close_releases_session():
    client = FakeClient()
    provider = MicaVoiceProvider(lambda: client)
    provider.start_session("s1")
    provider.close("s1")

    assert client.closed is True
    with pytest.raises(KeyError):
        provider.send_audio("s1", 1, b"a")


def test_provider_publishes_incremental_result_before_finish():
    client = FakeClient()
    provider = MicaVoiceProvider(lambda: client)
    received = []
    provider.subscribe("s1", received.append)
    provider.start_session("s1")

    client.result_callback(AsrResult(text="正在识别", is_final=False, version=1))

    assert received == [AsrResult(text="正在识别", is_final=False, version=1)]
