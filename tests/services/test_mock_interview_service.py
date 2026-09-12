import json
from types import SimpleNamespace

from services.mock_interview_service import MockInterviewService


def test_outline_covers_required_interview_categories():
    response = json.dumps({"questions": [
        {"category": "introduction", "question": "请做自我介绍", "reference_points": ["岗位匹配"]},
        {"category": "resume", "question": "介绍订单项目", "reference_points": ["职责"]},
        {"category": "technical", "question": "解释 Flask 上下文", "reference_points": ["原理"]},
        {"category": "scenario", "question": "如何排查慢查询", "reference_points": ["步骤"]},
        {"category": "behavior", "question": "描述一次冲突", "reference_points": ["STAR"]},
    ]}, ensure_ascii=False)
    service = MockInterviewService(lambda _messages: response)
    plan = SimpleNamespace(position_name="Python 工程师", job_description="需要 Flask", level="中级", tech_tags=["Python"])
    resume = SimpleNamespace(parsed_data={"projects": ["订单服务"]})

    outline = service.generate_outline(plan, resume)

    assert [item["category"] for item in outline] == [
        "introduction", "resume", "technical", "scenario", "behavior"
    ]


def test_evaluation_has_dimensions_and_flags_unverified_facts():
    response = json.dumps({
        "reference_points": ["先说明背景", "再说明行动"],
        "scores": {"fact_consistency": 80, "job_relevance": 85, "completeness": 75, "expression": 90},
        "strengths": ["结构清晰"],
        "improvements": ["补充技术取舍"],
    }, ensure_ascii=False)
    service = MockInterviewService(lambda _messages: response)

    result = service.evaluate_answer(
        "请介绍项目",
        "我把性能提升了 99%",
        {"projects": ["订单服务"]},
        "负责 Python 服务",
    )

    assert set(result["scores"]) == {"fact_consistency", "job_relevance", "completeness", "expression"}
    assert result["fact_risks"] == ["99"]
    assert result["strengths"] == ["结构清晰"]


def test_invalid_evaluation_preserves_answer_for_retry():
    service = MockInterviewService(lambda _messages: "not json")

    result = service.evaluate_answer("问题", "我的原始回答", {}, "JD")

    assert result["answer"] == "我的原始回答"
    assert result["retryable"] is True
    assert result["error_code"] == "EVALUATION_PARSE_FAILED"


def test_evaluation_removes_unverified_numbers_from_generated_feedback():
    response = json.dumps({
        "reference_points": ["说明缓存命中率达到 85%", "解释技术取舍"],
        "scores": {"fact_consistency": 80, "job_relevance": 85, "completeness": 75, "expression": 90},
        "strengths": ["接口性能提升 99%", "排查顺序清楚"],
        "improvements": ["补充优化前 500ms、优化后 200ms", "增加回滚方案"],
    }, ensure_ascii=False)
    service = MockInterviewService(lambda _messages: response)

    result = service.evaluate_answer(
        "请说明如何排查订单服务性能问题",
        "我会先查看延迟和错误率，再结合日志定位瓶颈。",
        {"projects": ["订单服务慢查询排查"]},
        "负责 Python 服务性能排查",
    )

    generated_feedback = json.dumps({
        "reference_points": result["reference_points"],
        "strengths": result["strengths"],
        "improvements": result["improvements"],
    }, ensure_ascii=False)
    assert all(number not in generated_feedback for number in ["85", "99", "500", "200"])
    assert "解释技术取舍" in result["reference_points"]
    assert "排查顺序清楚" in result["strengths"]
    assert "增加回滚方案" in result["improvements"]
    assert any("本人可核查" in item for item in result["improvements"])
    assert result["fact_guard_triggered"] is True
