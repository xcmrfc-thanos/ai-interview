"""WebSocket client for the mica-voice gateway's PCM streaming protocol."""

import json
import threading
from typing import Callable, Iterable

from .asr_service import AsrResult


class MicaVoiceClient:
    def __init__(
        self,
        url: str,
        websocket_factory: Callable | None = None,
        connect_timeout: float = 5,
        final_timeout: float = 5,
        source: str = "mixed",
    ):
        self.url = url
        self.source = source
        self._factory = websocket_factory or self._default_factory
        self.connect_timeout = connect_timeout
        self.final_timeout = final_timeout
        self._socket = None
        self._version = 0
        self._results = []
        self._result_callback = None
        self._reader = None
        self._final_event = threading.Event()
        self._closing = False
        self._reader_error = None

    def set_result_callback(self, callback) -> None:
        self._result_callback = callback

    def start(self) -> None:
        self._closing = False
        self._reader_error = None
        self._results = []
        self._final_event.clear()
        self._socket = self._factory(self.url, timeout=self.connect_timeout)
        self._reader = threading.Thread(target=self._read_loop, name="mica-voice-reader", daemon=True)
        self._reader.start()
        self._socket.send(json.dumps({"type": "config", "sampleRate": 16000, "format": "pcm16le", "source": self.source}))
        self._socket.send(json.dumps({"type": "start"}))

    def send_audio(self, payload: bytes) -> None:
        if self._socket is None:
            raise RuntimeError("ASR client is not started")
        self._socket.send(payload, opcode=2)

    def pause(self) -> None:
        if self._socket:
            self._socket.send(json.dumps({"type": "stop"}))

    def finish(self) -> Iterable[AsrResult]:
        if self._socket is None:
            return []
        self._socket.send(json.dumps({"type": "stop"}))
        if not self._final_event.wait(self.final_timeout):
            raise RuntimeError("ASR final result timed out")
        if self._reader_error:
            raise RuntimeError(f"ASR receive failed: {self._reader_error}")
        return list(self._results)

    def close(self) -> None:
        socket = self._socket
        if socket is None:
            return
        self._closing = True
        try:
            socket.send(json.dumps({"type": "close"}))
        except Exception:
            pass
        finally:
            socket.close()
            self._socket = None
            if self._reader and self._reader is not threading.current_thread():
                self._reader.join(timeout=1)
            self._reader = None

    def _read_loop(self) -> None:
        while not self._closing and self._socket is not None:
            try:
                message = json.loads(self._socket.recv())
                message_type = message.get("type")
                if message_type in {"partial", "final"}:
                    self._version += 1
                    result = AsrResult(
                        str(message.get("text") or ""),
                        message_type == "final",
                        self._version,
                        source=str(message.get("source") or self.source),
                        speaker=str(message.get("speaker") or "interviewer"),
                        confidence=_confidence(message.get("confidence")),
                    )
                    self._results.append(result)
                    if self._result_callback:
                        self._result_callback(result)
                if message_type == "final":
                    self._final_event.set()
                elif message_type == "error":
                    self._reader_error = message.get("text") or "gateway error"
                    self._final_event.set()
            except Exception as error:
                if not self._closing:
                    self._reader_error = error
                    self._final_event.set()
                return

    @staticmethod
    def _default_factory(url: str, timeout: float):
        import websocket

        return websocket.create_connection(url, timeout=timeout)


def _confidence(value):
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None
