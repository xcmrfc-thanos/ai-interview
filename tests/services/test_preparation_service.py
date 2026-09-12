from types import SimpleNamespace

from services.preparation_service import PreparationService, source_fingerprint


def test_preparation_service_returns_three_intros_and_source_evidence():
    plan = SimpleNamespace(
        company_name="星河科技",
        position_name="Python 工程师",
        job_description="负责 Flask 服务，要求 Python 和 MySQL",
        extra_requirements="重视可观测性",
        level="中级",
        tech_tags=["Python", "Flask"],
    )
    resume = SimpleNamespace(parsed_data={"projects": [{"name": "订单系统", "scale": "日均 10 万单"}]})
    llm = lambda messages: '{"intro_30":"我做过订单系统","intro_60":"我使用 Python 构建服务","intro_90":"我负责从设计到上线","highlights":[{"text":"订单系统经验","source_type":"resume","source_excerpt":"订单系统"}],"source_evidence":[{"source_type":"resume","source_excerpt":"订单系统"}]}'

    result = PreparationService(llm).generate(plan, resume)

    assert result["intro_60"] == "我使用 Python 构建服务"
    assert result["highlights"][0]["source_type"] == "resume"
    assert result["needs_review"] is False


def test_preparation_service_marks_unverified_numbers_for_review():
    plan = SimpleNamespace(
        company_name="示例公司",
        position_name="后端工程师",
        job_description="需要 Python",
        extra_requirements="",
        level="初级",
        tech_tags=["Python"],
    )
    resume = SimpleNamespace(parsed_data={"projects": [{"name": "项目 A"}]})
    llm = lambda messages: '{"intro_30":"我优化了 99% 的接口","intro_60":"我负责后端","intro_90":"我完成上线","highlights":[],"source_evidence":[]}'

    result = PreparationService(llm).generate(plan, resume)

    assert result["needs_review"] is True
    assert "99" in result["unverified_claims"]


def test_source_fingerprint_changes_when_job_or_resume_changes():
    base = source_fingerprint("岗位", {"name": "简历"}, "要求")
    changed = source_fingerprint("岗位", {"name": "新简历"}, "要求")

    assert len(base) == 64
    assert base != changed


def test_preparation_service_generates_only_requested_section():
    plan = SimpleNamespace(
        company_name="星河科技",
        position_name="Python 工程师",
        job_description="负责 Flask 服务",
        extra_requirements="",
        level="中级",
        tech_tags=["Python"],
    )
    resume = SimpleNamespace(parsed_data={"projects": [{"name": "订单系统"}]})
    captured = []

    def llm(messages):
        captured.append(messages)
        return '{"intro_60":"我使用 Python 构建服务"}'

    result = PreparationService(llm).generate_section(plan, resume, "intro_60")

    assert result["intro_60"] == "我使用 Python 构建服务"
    assert result["needs_review"] is False
    assert "intro_30" not in captured[0][1]["content"]
    assert "intro_60" in captured[0][1]["content"]
