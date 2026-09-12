"""Provider-neutral streaming ASR boundary for Copilot sessions."""

from dataclasses import dataclass, replace
from typing import Callable, Dict, Iterable


@dataclass(frozen=True)
class AsrResult:
    text: str
    is_final: bool
    version: int = 0
    start_ms: int | None = None
    end_ms: int | None = None
    source: str = "mixed"
    speaker: str = "interviewer"
    confidence: float | None = None


class MicaVoiceProvider:
    """Keeps one long-lived mica-voice client per Copilot session."""

    def __init__(self, client_factory: Callable[[], object]):
        self._client_factory = client_factory
        self._sessions: Dict[tuple[str, str], dict] = {}
        self._subscribers: Dict[str, Callable[[AsrResult], None]] = {}

    def subscribe(self, session_id: str, callback: Callable[[AsrResult], None]) -> None:
        self._subscribers[session_id] = callback

    def start_session(self, session_id: str, source: str = "mixed") -> None:
        self.close(session_id, source)
        client = self._create_client(source)
        if hasattr(client, "set_result_callback"):
            client.set_result_callback(lambda result: self._publish(session_id, result, source))
        client.start()
        self._sessions[self._key(session_id, source)] = {
            "client": client,
            "last_sequence": -1,
            "incremental": hasattr(client, "set_result_callback"),
            "source": source,
        }

    def send_audio(self, session_id: str, sequence: int, payload: bytes, source: str = "mixed") -> bool:
        state = self._session(session_id, source)
        last_sequence = state["last_sequence"]
        if sequence <= last_sequence:
            return False
        if sequence != last_sequence + 1:
            raise ValueError(f"audio sequence out of order: expected {last_sequence + 1}, got {sequence}")
        state["client"].send_audio(payload)
        state["last_sequence"] = sequence
        return True

    def pause(self, session_id: str, source: str | None = None) -> None:
        for state in self._matching_sessions(session_id, source):
            state["client"].pause()

    def restore_sequence(self, session_id: str, sequence: int, source: str = "mixed") -> None:
        self._session(session_id, source)["last_sequence"] = int(sequence)

    def finish(self, session_id: str, source: str = "mixed") -> Iterable[AsrResult]:
        state = self._session(session_id, source)
        results = state["client"].finish()
        return [] if state["incremental"] and session_id in self._subscribers else results

    def close(self, session_id: str, source: str | None = None) -> None:
        for key in list(self._sessions):
            if key[0] != str(session_id) or (source is not None and key[1] != source):
                continue
            state = self._sessions.pop(key)
            state["client"].close()

    def _session(self, session_id: str, source: str = "mixed") -> dict:
        key = self._key(session_id, source)
        if key not in self._sessions:
            raise KeyError(f"ASR session not found: {session_id}")
        return self._sessions[key]

    def _publish(self, session_id: str, result: AsrResult, source: str = "mixed") -> None:
        callback = self._subscribers.get(session_id)
        if callback:
            if source != "mixed":
                speaker = "candidate" if source == "microphone" else "interviewer"
                result = replace(result, source=source, speaker=speaker)
            callback(result)

    def _create_client(self, source: str):
        try:
            return self._client_factory(source)
        except TypeError:
            return self._client_factory()

    def _matching_sessions(self, session_id: str, source: str | None):
        for (current_id, current_source), state in self._sessions.items():
            if current_id == str(session_id) and (source is None or current_source == source):
                yield state

    @staticmethod
    def _key(session_id: str, source: str):
        return str(session_id), source
