"""In-process streaming ASR client backed by sherpa-onnx (replaces the Java gateway WS path).

Duck-types MicaVoiceClient (set_result_callback / start / send_audio / pause /
finish / close) so MicaVoiceProvider and existing tests work unchanged.
"""

from __future__ import annotations

import os
import threading
import time
from typing import Callable

from .asr_service import AsrResult
from .mica_voice_client import MicaVoiceClient  # noqa: F401  (re-exported fallback)


class SherpaOnnxAsrClient:
    """Runs one OnlineRecognizer stream per session inside the Flask process."""

    def __init__(
        self,
        models_dir: str,
        model_dir_name: str = "x-asr-zh-en-chunk-960ms",
        source: str = "mixed",
        num_threads: int = 2,
        sample_rate: int = 16000,
        feature_dim: int = 80,
        chunk_samples: int = 1600,
        finish_timeout: float = 5.0,
    ):
        self.models_dir = models_dir
        self.model_dir_name = model_dir_name
        self.source = source
        self.num_threads = num_threads
        self.sample_rate = sample_rate
        self.feature_dim = feature_dim
        self.chunk_samples = chunk_samples
        self.finish_timeout = finish_timeout
        # 未消费 PCM 队列上限（约 10 秒）：pause 期间 pump 停止消费而 send_audio
        # 持续入队，不设上限会无界增长；超限淘汰最旧，保留最近 10 秒供恢复续解。
        self.max_pending_bytes = sample_rate * 2 * int(
            os.getenv("SHERPA_PENDING_SECONDS", "10")
        )
        # 尾静音端点检测：静音超过该秒数且已识别到语音 → 发 final 并重置解码流
        self.endpoint_silence = float(os.getenv("SHERPA_ENDPOINT_SILENCE", "1.2"))
        self.voice_peak_threshold = float(os.getenv("SHERPA_VOICE_PEAK", "0.01"))
        self._recognizer = _load_recognizer(models_dir, model_dir_name, num_threads, sample_rate, feature_dim)
        self._result_callback: Callable[[AsrResult], None] | None = None
        self._lock = threading.Lock()
        self._stream = None
        self._version = 0
        self._results = []
        self._pending = bytearray()
        self._worker: threading.Thread | None = None
        self._final_event = threading.Event()
        self._closed = False
        self._paused = False
        self._error: Exception | None = None

    @staticmethod
    def is_available() -> bool:
        try:
            import sherpa_onnx  # noqa: F401

            return True
        except ImportError:
            return False

    def set_result_callback(self, callback: Callable[[AsrResult], None]) -> None:
        self._result_callback = callback

    def start(self) -> None:
        with self._lock:
            if self._closed:
                raise RuntimeError("ASR client is closed")
            self._stream = self._recognizer.create_stream()
            self._version = 0
            self._results = []
            self._pending = bytearray()
            self._paused = False
            self._final_event.clear()
            self._worker = threading.Thread(target=self._pump, name="sherpa-asr-pump", daemon=True)
            self._worker.start()

    def send_audio(self, payload: bytes) -> None:
        with self._lock:
            if self._stream is None:
                raise RuntimeError("ASR client is not started")
            self._pending.extend(payload)
            overflow = len(self._pending) - self.max_pending_bytes
            if overflow > 0:
                del self._pending[:overflow]

    def pause(self) -> None:
        # 与 WS 客户端 pause 语义对齐：只停止消费，流保持可继续 send_audio。
        with self._lock:
            self._paused = True

    def finish(self) -> list[AsrResult]:
        with self._lock:
            self._paused = False
        self._flush(final=True)
        if not self._final_event.wait(self.finish_timeout):
            raise RuntimeError("ASR final result timed out")
        if self._error is not None:
            raise RuntimeError(f"ASR decode failed: {self._error}")
        return list(self._results)

    def close(self) -> None:
        with self._lock:
            self._closed = True
            stream = self._stream
            self._stream = None
        if stream is not None:
            try:
                stream.input_finished()
            except Exception:
                pass
        worker = self._worker
        if worker is not None and worker is not threading.current_thread():
            worker.join(timeout=1)
        self._worker = None

    # ---- internals ----

    def _pump(self) -> None:
        """Consume queued PCM chunks; only one pump thread per session."""
        import numpy as np

        last_voice_at = time.monotonic()
        has_voice = False
        while True:
            with self._lock:
                if self._closed or self._stream is None:
                    return
                paused = self._paused
                data = b"" if paused else bytes(self._pending[: self.chunk_samples * 2])
                del self._pending[: len(data)]
                stream = self._stream
            if data:
                samples = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
                with _RECOGNIZER_LOCK:
                    stream.accept_waveform(self.sample_rate, samples)
                    while self._recognizer.is_ready(stream):
                        self._recognizer.decode_stream(stream)
                    text = self._recognizer.get_result(stream)
                if float(np.max(np.abs(samples))) > self.voice_peak_threshold:
                    last_voice_at = time.monotonic()
                    has_voice = True
                if text and text.strip():
                    self._publish(text, is_final=False)
                continue
            # 无排队数据：尾静音达到阈值且已识别到语音 → 发 final 并重置解码流（与网关端点行为对齐）
            if has_voice and (time.monotonic() - last_voice_at) >= self.endpoint_silence:
                with _RECOGNIZER_LOCK:
                    text = self._recognizer.get_result(stream)
                    new_stream = self._recognizer.create_stream()
                if self._closed or self._stream is None:
                    return
                if text and text.strip():
                    self._publish(text, is_final=True)
                with self._lock:
                    if self._stream is not None:
                        self._stream = new_stream
                has_voice = False
                last_voice_at = time.monotonic()
            if not data:
                # Nothing queued (or paused): stop once a final flush completed.
                if self._final_event.is_set():
                    return
                threading.Event().wait(0.02)

    def _flush(self, final: bool) -> None:
        """让 worker 先把已入队的音频全部解码完，再做终止处理。"""
        with self._lock:
            stream = self._stream
        if stream is None:
            self._final_event.set()
            return
        deadline = time.monotonic() + self.finish_timeout
        while time.monotonic() < deadline:
            with self._lock:
                empty = not self._pending
                current_stream = self._stream
            if empty or current_stream is None:
                break
            threading.Event().wait(0.02)
        try:
            with _RECOGNIZER_LOCK:
                while self._recognizer.is_ready(stream):
                    self._recognizer.decode_stream(stream)
                text = self._recognizer.get_result(stream)
            if text and text.strip():
                self._publish(text, is_final=final)
            # 空转写（静音）不发 final —— 与网关行为一致
        except Exception as error:  # noqa: BLE001
            self._error = error
        finally:
            self._final_event.set()

    def _publish(self, text: str, is_final: bool) -> None:
        self._version += 1
        result = AsrResult(
            text,
            is_final,
            self._version,
            source=self.source,
            speaker="interviewer",
        )
        self._results.append(result)
        if self._result_callback is not None:
            try:
                self._result_callback(result)
            except Exception as error:  # noqa: BLE001
                self._error = error


_RECOGNIZER_CACHE: dict[tuple, object] = {}
_RECOGNIZER_LOCK = threading.Lock()


def _load_recognizer(models_dir: str, model_dir_name: str, num_threads: int, sample_rate: int, feature_dim: int):
    """Model files are ~600MB; load once per process and share across sessions."""
    import sherpa_onnx

    key = (models_dir, model_dir_name, num_threads)
    with _RECOGNIZER_LOCK:
        cached = _RECOGNIZER_CACHE.get(key)
        if cached is not None:
            return cached
        model_dir = os.path.join(models_dir, model_dir_name)
        if not os.path.isdir(model_dir):
            raise FileNotFoundError(
                f"ASR model dir not found: {model_dir}. "
                "Set MICA_VOICE_MODELS_DIR or run the model download script."
            )
        recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=os.path.join(model_dir, "tokens.txt"),
            encoder=os.path.join(model_dir, "encoder-960ms.onnx"),
            decoder=os.path.join(model_dir, "decoder-960ms.onnx"),
            joiner=os.path.join(model_dir, "joiner-960ms.onnx"),
            num_threads=num_threads,
            sample_rate=sample_rate,
            feature_dim=feature_dim,
        )
        _RECOGNIZER_CACHE[key] = recognizer
        return recognizer


def create_asr_client(source: str = "mixed") -> SherpaOnnxAsrClient | MicaVoiceClient:
    """按 ASR_BACKEND 选择后端：gateway=强制 WS 网关，builtin=强制进程内 sherpa，auto=内置优先、缺失回退网关。"""
    backend = os.getenv("ASR_BACKEND", "auto").strip().lower()
    asr_url = os.getenv("MICA_VOICE_ASR_URL", "ws://127.0.0.1:18081/mica/voice/ws/online-asr")
    if backend not in ("gateway", "builtin"):
        models_dir = os.getenv("MICA_VOICE_MODELS_DIR", "third_party/mica-voice/models")
        model_dir_name = os.getenv("MICA_VOICE_ONLINE_MODEL", "x-asr-zh-en-chunk-960ms")
        model_dir = os.path.join(models_dir, model_dir_name)
        backend = "builtin" if SherpaOnnxAsrClient.is_available() and os.path.isdir(model_dir) else "gateway"
    if backend == "builtin":
        return SherpaOnnxAsrClient(
            os.getenv("MICA_VOICE_MODELS_DIR", "third_party/mica-voice/models"),
            os.getenv("MICA_VOICE_ONLINE_MODEL", "x-asr-zh-en-chunk-960ms"),
            source=source,
        )
    from .mica_voice_client import MicaVoiceClient

    return MicaVoiceClient(asr_url, source=source)
