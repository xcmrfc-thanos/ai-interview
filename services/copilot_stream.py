"""Translate ASR provider results into Copilot Socket.IO events."""

from dataclasses import replace


VALID_SPEAKERS = {"candidate", "interviewer"}


class CopilotStream:
    def __init__(self, provider, emit, result_handler=None):
        self.provider = provider
        self.emit = emit
        self.result_handler = result_handler
        self.sessions = set()
        self.sources = {}
        self.speaker_overrides = {}

    def start(self, session_id, initial_sequence=-1, source="mixed", speaker=None):
        if hasattr(self.provider, "subscribe"):
            self.provider.subscribe(session_id, lambda result: self._handle_result(session_id, result))
        _call_with_source(self.provider.start_session, session_id, source)
        if hasattr(self.provider, "restore_sequence"):
            _call_with_source(self.provider.restore_sequence, session_id, initial_sequence, source)
        self.sessions.add(session_id)
        self.sources.setdefault(session_id, set()).add(source)
        if speaker is not None:
            self.set_speaker(session_id, speaker, source)
        self.emit("session_state", {"session_id": session_id, "state": "running"})

    def set_speaker(self, session_id, speaker=None, source=None):
        if speaker is not None and speaker not in VALID_SPEAKERS:
            raise ValueError(f"unsupported speaker: {speaker}")
        targets = {source} if source else self.sources.get(session_id, {"mixed"})
        overrides = self.speaker_overrides.setdefault(session_id, {})
        for target in targets:
            if speaker is None:
                overrides.pop(target, None)
            else:
                overrides[target] = speaker
        if not overrides:
            self.speaker_overrides.pop(session_id, None)

    def audio_frame(self, session_id, sequence, payload, source="mixed"):
        if session_id not in self.sessions:
            self._error(session_id, "Copilot 会话未启动")
            return False
        if not isinstance(payload, (bytes, bytearray, memoryview)) or len(payload) % 2:
            self._error(session_id, "PCM 音频帧无效")
            return False
        try:
            return _call_with_source(self.provider.send_audio, session_id, sequence, bytes(payload), source)
        except (KeyError, ValueError, RuntimeError) as error:
            self._error(session_id, str(error))
            return False

    def pause(self, session_id, source=None):
        if session_id in self.sessions:
            if source is None:
                for current_source in self.sources.get(session_id, {"mixed"}):
                    _call_with_source(self.provider.pause, session_id, current_source)
            else:
                _call_with_source(self.provider.pause, session_id, source)
            self.emit("session_state", {"session_id": session_id, "state": "paused"})

    def stop(self, session_id):
        if session_id not in self.sessions:
            return
        try:
            for source in self.sources.get(session_id, {"mixed"}):
                for result in _call_with_source(self.provider.finish, session_id, source):
                    self._handle_result(session_id, result)
            self.emit("session_state", {"session_id": session_id, "state": "ended"})
        except (KeyError, RuntimeError, ValueError) as error:
            self._error(session_id, str(error))
        finally:
            self.provider.close(session_id)
            self.sessions.discard(session_id)
            self.sources.pop(session_id, None)
            self.speaker_overrides.pop(session_id, None)

    def close(self, session_id):
        self.provider.close(session_id)
        self.sessions.discard(session_id)
        self.sources.pop(session_id, None)
        self.speaker_overrides.pop(session_id, None)

    def _error(self, session_id, message):
        self.emit("error", {"session_id": session_id, "message": message})

    def _handle_result(self, session_id, result):
        result = self._apply_speaker_override(session_id, result)
        event = "transcript_final" if result.is_final else "transcript_partial"
        payload = {
            "session_id": session_id,
            "text": result.text,
            "version": result.version,
        }
        if result.source != "mixed" or result.speaker != "interviewer" or result.confidence is not None:
            payload.update({
                "source": result.source,
                "speaker": result.speaker,
                "confidence": result.confidence,
            })
        self.emit(event, payload)
        if self.result_handler:
            self.result_handler(session_id, result)

    def _apply_speaker_override(self, session_id, result):
        overrides = self.speaker_overrides.get(session_id, {})
        source = result.source or "mixed"
        speaker = overrides.get(source, overrides.get("mixed"))
        return replace(result, speaker=speaker) if speaker else result


def _call_with_source(method, *args):
    try:
        return method(*args)
    except TypeError as error:
        if not args or not isinstance(args[-1], str):
            raise
        return method(*args[:-1])
