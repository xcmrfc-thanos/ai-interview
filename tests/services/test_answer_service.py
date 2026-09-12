from services.answer_service import AnswerService


def test_answer_stream_emits_points_before_deltas_and_completion():
    chunks = [
        '{"type":"answer_points","answer_points":["先说明背景","再讲行动"]}\n',
        '{"type":"answer_delta","text":"我会先说明项目背景，"}\n',
        '{"type":"answer_delta","text":"再解释关键行动。"}\n',
        '{"type":"completed","follow_up":"准备性能追问","knowledge_item_ids":[3]}\n',
    ]
    events = []
    service = AnswerService(lambda messages: iter(chunks), emit_interval=0)

    result = service.generate("s1", {"text": "<current_question>问题</current_question>"}, lambda event, payload: events.append((event, payload)))

    deltas = [payload.get("text", "") for event, payload in events if event == "answer_delta"]
    assert events[0][0] == "answer_started"
    assert "".join(deltas) == "我会先说明项目背景，再解释关键行动。"
    assert len(deltas) > 2
    assert events[-1][0] == "answer_completed"
    assert result["answer_points"] == ["先说明背景", "再讲行动"]
    assert result["reference_answer"] == "我会先说明项目背景，再解释关键行动。"
    assert result["follow_up"] == "准备性能追问"
    assert result["knowledge_item_ids"] == [3]


def test_answer_stream_timeout_keeps_generated_content():
    def stream(_messages):
        yield '{"type":"answer_points","answer_points":["先给结论"]}\n'
        yield '{"type":"answer_delta","text":"已经生成的部分"}\n'
        raise TimeoutError("model timeout")

    events = []
    result = AnswerService(stream, emit_interval=0).generate("s1", {"text": "问题"}, lambda event, payload: events.append((event, payload)))

    assert result["reference_answer"] == "已经生成的部分"
    assert result["error_code"] == "LLM_TIMEOUT"
    assert events[-1][0] == "answer_completed"
    assert events[-1][1]["partial"] is True


def test_unexpected_provider_error_still_emits_completion_feedback():
    def stream(_messages):
        raise Exception("provider unavailable")
        yield "never"

    events = []
    result = AnswerService(stream, emit_interval=0).generate("s1", {"text": "问题"}, lambda event, payload: events.append((event, payload)))

    assert result["error_code"] == "LLM_STREAM_ERROR"
    assert events[-1][0] == "answer_completed"
    assert events[-1][1]["partial"] is True


def test_invalid_stream_falls_back_to_plain_text():
    events = []
    result = AnswerService(lambda _messages: iter(["先解释原理，", "再结合项目。"]), emit_interval=0).generate(
        "s1",
        {"text": "问题"},
        lambda event, payload: events.append((event, payload)),
    )

    assert result["answer_points"] == []
    assert result["reference_answer"] == "先解释原理，再结合项目。"
    assert result["error_code"] == "STRUCTURED_OUTPUT_FALLBACK"
    assert events[-1][0] == "answer_completed"


def test_cancel_stops_old_generation_without_completed_event():
    events = []
    service = None

    def stream(_messages):
        yield '{"type":"answer_points","answer_points":["旧问题"]}\n'
        service.cancel("s1")
        yield '{"type":"answer_delta","text":"不应出现"}\n'

    service = AnswerService(stream)
    result = service.generate("s1", {"text": "旧问题"}, lambda event, payload: events.append((event, payload)))

    assert result["cancelled"] is True
    # P2 fast_points：首字节先发占位 answer_started，正式要点事件是第二个。
    assert [event for event, _ in events] == ["answer_started", "answer_started"]


def test_numbered_ndjson_lines_are_parsed_as_structured_events():
    chunks = [
        '1. {"type":"answer_points","answer_points":["检查执行计划"]}\n',
        '2. {"type":"answer_delta","text":"先查看慢查询日志。"}\n',
        '3. {"type":"completed","follow_up":"索引如何设计","knowledge_item_ids":[]}\n',
    ]
    events = []

    result = AnswerService(lambda _messages: iter(chunks), emit_interval=0).generate(
        "s1", {"text": "<current_question>如何排查慢查询？</current_question>"},
        lambda event, payload: events.append((event, payload)),
    )

    assert result["answer_points"] == ["检查执行计划"]
    assert result["reference_answer"] == "先查看慢查询日志。"
    assert result["error_code"] is None
    deltas = [payload.get("text", "") for event, payload in events if event == "answer_delta"]
    assert "".join(deltas) == "先查看慢查询日志。"
    assert events[0][0] == "answer_started"
    assert events[-1][0] == "answer_completed"


def test_unverified_numbers_never_reach_delta_events_or_persisted_answer():
    chunks = [
        '{"type":"answer_points","answer_points":["先看监控","再查执行计划"]}\n',
        '{"type":"answer_delta","text":"我把延迟从 2 秒优化到 300ms，"}\n',
        '{"type":"answer_delta","text":"TPS 提升了 40%。"}\n',
        '{"type":"completed","follow_up":"如何验证","knowledge_item_ids":[]}\n',
    ]
    events = []

    result = AnswerService(lambda _messages: iter(chunks), emit_interval=0).generate(
        "s1",
        {"text": "<resume_facts>{\"project\":\"订单服务慢查询排查\"}</resume_facts>"},
        lambda event, payload: events.append((event, payload)),
    )

    displayed = "".join(payload.get("text", "") for event, payload in events if event == "answer_delta")
    assert "2 秒" not in displayed
    assert "300ms" not in displayed
    assert "40%" not in displayed
    assert "建议按以下要点组织" in result["reference_answer"]
    assert result["error_code"] == "FACT_GUARD_FALLBACK"
    assert result["fact_guard_triggered"] is True
    assert events[-1][0] == "answer_completed"


def test_answer_points_strip_model_list_ordinals_before_fact_check():
    chunks = [
        '{"type":"answer_points","answer_points":["4. 检查应用日志","5、查看数据库执行计划"]}\n',
        '{"type":"answer_delta","text":"先定位问题，再验证改动。"}\n',
        '{"type":"completed","follow_up":"如何回滚","knowledge_item_ids":[]}\n',
    ]

    result = AnswerService(lambda _messages: iter(chunks)).generate(
        "s1",
        {"text": "<current_question>如何排查性能问题？</current_question>"},
        lambda _event, _payload: None,
    )

    assert result["answer_points"] == ["检查应用日志", "查看数据库执行计划"]
    assert result["fact_guard_triggered"] is False
