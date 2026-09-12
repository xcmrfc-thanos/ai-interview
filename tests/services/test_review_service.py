from types import SimpleNamespace

from services.review_service import ReviewService


def test_copilot_review_contains_questions_and_diagnostics():
    source = SimpleNamespace(
        turns=[
            SimpleNamespace(transcript="请介绍项目", answer_points=["背景", "行动"], reference_answer="回答", status="completed"),
            SimpleNamespace(transcript="如何优化性能", answer_points=["定位瓶颈"], reference_answer="回答", status="partial"),
        ],
        events=[
            SimpleNamespace(event_type="answer_error", latency_ms=None, error_code="LLM_TIMEOUT", payload={}),
            SimpleNamespace(event_type="reconnected", latency_ms=320, error_code=None, payload={}),
        ],
    )
    plan = SimpleNamespace(company_name="星河科技", position_name="Python 工程师")

    result = ReviewService.build("copilot", source, plan)

    assert "2 个问题" in result["summary"]
    assert result["question_categories"][0]["name"] == "实时问答"
    assert result["diagnostics"]["answer_errors"] == 1
    assert result["diagnostics"]["reconnects"] == 1
    assert result["next_actions"]


def test_mock_review_averages_scores_and_collects_improvements():
    source = SimpleNamespace(
        question_outline=[{"category": "technical"}, {"category": "behavior"}],
        turns=[
            SimpleNamespace(scores={"job_relevance": 80, "expression": 70}, improvements=["补充技术取舍"], status="answered"),
            SimpleNamespace(scores={"job_relevance": 60, "expression": 90}, improvements=["使用 STAR"], status="answered"),
        ],
    )
    plan = SimpleNamespace(company_name="星河科技", position_name="Python 工程师")

    result = ReviewService.build("mock", source, plan)

    assert result["scores"] == {"job_relevance": 70.0, "expression": 80.0}
    assert {item["name"] for item in result["question_categories"]} == {"technical", "behavior"}
    assert result["expression_issues"] == ["补充技术取舍", "使用 STAR"]
