import json
from types import SimpleNamespace

from services.resume_optimize_service import ResumeOptimizeService


def test_optimization_returns_comparison_and_marks_unverified_numbers():
    response = json.dumps({
        "matches": ["Python"],
        "gaps": ["缺少 Redis 证据"],
        "keywords": ["Flask", "Redis"],
        "suggestions": [
            {
                "original_fact": "负责订单服务",
                "candidate_text": "负责订单服务，将性能提升 50%",
                "rationale": "突出结果",
                "requires_confirmation": False,
            }
        ],
    }, ensure_ascii=False)
    plan = SimpleNamespace(position_name="Python 工程师", job_description="需要 Flask Redis", tech_tags=["Python"])
    resume = SimpleNamespace(parsed_data={"projects": ["负责订单服务"], "skills": ["Python"]})

    result = ResumeOptimizeService(lambda _messages: response).generate(plan, resume)

    assert result["matches"] == ["Python"]
    assert result["suggestions"][0]["suggestion_id"] == "s1"
    assert result["suggestions"][0]["requires_confirmation"] is True
    assert result["suggestions"][0]["unverified_claims"] == ["50"]


def test_optimization_does_not_mutate_resume_facts():
    response = json.dumps({
        "matches": [], "gaps": [], "keywords": [],
        "suggestions": [{"original_fact": "Python", "candidate_text": "熟悉 Python", "rationale": "匹配岗位"}],
    }, ensure_ascii=False)
    facts = {"skills": ["Python"]}
    resume = SimpleNamespace(parsed_data=facts)
    plan = SimpleNamespace(position_name="后端工程师", job_description="Python", tech_tags=[])

    ResumeOptimizeService(lambda _messages: response).generate(plan, resume)

    assert resume.parsed_data == {"skills": ["Python"]}
