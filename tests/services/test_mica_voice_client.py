import json
import queue
import time

from services.mica_voice_client import MicaVoiceClient


class FakeSocket:
    def __init__(self):
        self.sent = []
        self.messages = queue.Queue()
        self.closed = False

    def send(self, payload, opcode=None):
        self.sent.append((payload, opcode))

    def recv(self):
        message = self.messages.get(timeout=1)
        if isinstance(message, Exception):
            raise message
        return message

    def close(self):
        self.closed = True
        self.messages.put(RuntimeError("closed"))


def test_client_receives_partial_before_finish_and_final_on_stop():
    socket = FakeSocket()
    socket.messages.put(json.dumps({"type": "ready"}))
    socket.messages.put(json.dumps({"type": "partial", "text": "请介绍"}))
    received = []
    client = MicaVoiceClient("ws://test", websocket_factory=lambda *_args, **_kwargs: socket)
    client.set_result_callback(received.append)

    client.start()
    deadline = time.time() + 1
    while not received and time.time() < deadline:
        time.sleep(.01)
    socket.messages.put(json.dumps({"type": "final", "text": "请介绍项目"}))
    results = list(client.finish())

    assert received[0].text == "请介绍"
    assert received[0].is_final is False
    assert results[-1].text == "请介绍项目"
    assert results[-1].is_final is True
    client.close()
