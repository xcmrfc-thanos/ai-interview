"""Deterministic question-boundary filtering for Copilot transcripts."""

import re


ACKNOWLEDGEMENTS = {
    "好",
    "好的",
    "嗯",
    "嗯嗯",
    "对",
    "是的",
    "可以",
    "明白",
    "收到",
}
QUESTION_FEATURES = re.compile(
    r"[?？]$|^(请|能否|可以|说说|谈谈|介绍|解释|描述|为什么|怎么|如何|什么|哪些|是否|有没有)|"
    r"(吗|呢|么|为什么|怎么|如何|什么|哪些|是否|区别|原理|场景|经验)$|"
    r"(介绍.*(自己|个人)|自我介绍|说一下|讲一下)"
)
QUESTION_PREFIXES = re.compile(r"^(请|能否|可以|说说|谈谈|介绍|解释|描述|为什么|怎么|如何|什么|哪些|是否|有没有)")
DIRECT_PROMPTS = {"请介绍一下自己", "自我介绍"}
SHORT_PREFIX_LIMIT = 7


class UtteranceService:
    def __init__(self, silence_ms=800):
        self.silence_ms = silence_ms
        self._states = {}

    def accept_partial(self, session_id, text, now_ms, paused=False, speaker="interviewer"):
        normalized = _normalize(text)
        if paused:
            return _result("ignore", "session_paused", normalized)
        if speaker != "interviewer":
            return _result("ignore", "user_speech", normalized)
        if not normalized:
            return _result("ignore", "empty_text", normalized)
        state = self._state(session_id)
        if normalized == state.get("partial"):
            return _result("ignore", "duplicate_partial", normalized)
        state["partial"] = normalized
        state["last_activity_ms"] = now_ms
        return _result("update", "partial_updated", normalized)

    def accept_final(
        self,
        session_id,
        text,
        now_ms,
        paused=False,
        speaker="interviewer",
        last_activity_ms=None,
    ):
        normalized = _normalize(text)
        if paused:
            return _result("ignore", "session_paused", normalized)
        if speaker != "interviewer":
            return _result("ignore", "user_speech", normalized)
        if not normalized:
            return _result("ignore", "empty_text", normalized)
        if normalized.casefold() in ACKNOWLEDGEMENTS:
            return _result("ignore", "short_acknowledgement", normalized)

        state = self._state(session_id)
        pending = state.get("pending_text")
        normalized = _merge_with_partial(state.get("partial"), normalized)
        if pending:
            elapsed = now_ms - state.get("last_activity_ms", now_ms)
            normalized = _join_segments(pending, normalized)
            if elapsed < self.silence_ms and not re.search(r"[?？]$", normalized):
                state["pending_text"] = normalized
                state["partial"] = normalized
                state["last_activity_ms"] = now_ms
                return _result("update", "awaiting_silence", normalized)
            state.pop("pending_text", None)
        if normalized == state.get("final"):
            return _result("ignore", "duplicate_final", normalized)
        if _is_short_question_prefix(normalized):
            state["pending_text"] = normalized
            state["partial"] = normalized
            state["last_activity_ms"] = now_ms
            return _result("update", "awaiting_context", normalized)
        if QUESTION_FEATURES.search(normalized):
            state["final"] = normalized
            state.pop("partial", None)
            return _result("complete", "question_feature", normalized)

        activity = last_activity_ms
        if activity is None:
            activity = state.get("last_activity_ms", now_ms)
        if now_ms - activity >= self.silence_ms:
            state["final"] = normalized
            state.pop("partial", None)
            return _result("complete", "silence_boundary", normalized)

        state["partial"] = normalized
        state["last_activity_ms"] = activity
        return _result("update", "awaiting_silence", normalized)

    def reset(self, session_id):
        self._states.pop(str(session_id), None)

    def _state(self, session_id):
        return self._states.setdefault(str(session_id), {})


def _normalize(text):
    return re.sub(r"\s+", " ", str(text or "")).strip()


def _is_short_question_prefix(text):
    return (
        text not in DIRECT_PROMPTS
        and len(text) <= SHORT_PREFIX_LIMIT
        and QUESTION_PREFIXES.search(text) is not None
        and not re.search(r"[?？]$", text)
    )


def _join_segments(first, second):
    if first and second and first[-1].isascii() and second[0].isascii() and first[-1].isalnum() and second[0].isalnum():
        return f"{first} {second}"
    return f"{first}{second}"


def _merge_with_partial(partial, final):
    partial = _normalize(partial)
    final = _normalize(final)
    if not partial or not final:
        return final or partial
    if partial.endswith(final):
        return partial
    if final.startswith(partial):
        return final
    return final


def _result(action, reason, text):
    return {"action": action, "reason": reason, "text": text}
