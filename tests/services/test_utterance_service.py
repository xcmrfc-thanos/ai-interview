from services.utterance_service import UtteranceService


def test_partial_updates_but_duplicate_partial_is_ignored():
    service = UtteranceService(silence_ms=800)

    first = service.accept_partial("s1", "请介绍一下", now_ms=100)
    duplicate = service.accept_partial("s1", "请介绍一下", now_ms=200)

    assert first == {"action": "update", "reason": "partial_updated", "text": "请介绍一下"}
    assert duplicate["action"] == "ignore"
    assert duplicate["reason"] == "duplicate_partial"


def test_final_filters_paused_user_speech_short_ack_and_duplicates():
    service = UtteranceService(silence_ms=800)

    assert service.accept_final("s1", "好的", now_ms=100)["reason"] == "short_acknowledgement"
    assert service.accept_final("s1", "请介绍项目？", now_ms=200, paused=True)["reason"] == "session_paused"
    assert service.accept_final("s1", "我来回答一下", now_ms=300, speaker="user")["reason"] == "user_speech"
    completed = service.accept_final("s1", "请介绍一下你的项目？", now_ms=400)
    duplicate = service.accept_final("s1", "请介绍一下你的项目？", now_ms=500)

    assert completed["action"] == "complete"
    assert completed["reason"] == "question_feature"
    assert duplicate["reason"] == "duplicate_final"


def test_silence_completes_non_question_final_after_boundary():
    service = UtteranceService(silence_ms=800)
    service.accept_partial("s1", "项目经验包括订单系统", now_ms=100)

    early = service.accept_final("s1", "项目经验包括订单系统", now_ms=700)
    complete = service.accept_final("s2", "项目经验包括订单系统", now_ms=900, last_activity_ms=0)

    assert early["action"] == "update"
    assert early["reason"] == "awaiting_silence"
    assert complete["action"] == "complete"
    assert complete["reason"] == "silence_boundary"


def test_introduction_prompt_completes_even_when_asr_drops_question_prefix():
    service = UtteranceService(silence_ms=800)

    result = service.accept_final("s1", "然后今天来介绍点自己", now_ms=100)

    assert result["action"] == "complete"
    assert result["reason"] == "question_feature"


def test_short_question_prefix_waits_and_merges_following_asr_segment():
    service = UtteranceService(silence_ms=800)

    prefix = service.accept_final("s1", "请介绍一下", now_ms=100)
    merged = service.accept_final("s1", "思路里的传播机制事物", now_ms=1000)

    assert prefix["action"] == "update"
    assert prefix["reason"] == "awaiting_context"
    assert merged["action"] == "complete"
    assert merged["text"] == "请介绍一下思路里的传播机制事物"


def test_final_fragments_keep_accumulating_until_question_is_complete():
    service = UtteranceService(silence_ms=1500)

    first = service.accept_final("s1", "请介绍一下", now_ms=100)
    second = service.accept_final("s1", "思路里的传播机制事物", now_ms=900)
    complete = service.accept_final("s1", "和 oracle 的区别是什么？", now_ms=1200)

    assert first["action"] == "update"
    assert second["action"] == "update"
    assert complete["action"] == "complete"
    assert complete["text"] == "请介绍一下思路里的传播机制事物和 oracle 的区别是什么？"


def test_final_tail_does_not_drop_prefix_from_latest_partial():
    service = UtteranceService(silence_ms=1500)
    service.accept_partial("s1", "请介绍一下自己", now_ms=100)

    result = service.accept_final("s1", "下自己", now_ms=500)

    assert result["action"] == "complete"
    assert result["text"] == "请介绍一下自己"
