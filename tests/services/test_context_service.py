from types import SimpleNamespace

from services.context_service import ContextService


def test_context_order_and_recent_turn_limit_are_stable():
    plan = SimpleNamespace(job_description="需要 Python Flask", tech_tags=["Python", "Flask"], level="中级")
    resume = SimpleNamespace(parsed_data={"skills": ["Python"], "project": "订单服务"})
    turns = [SimpleNamespace(turn_number=index, transcript=f"问题 {index}", reference_answer=f"回答 {index}") for index in range(1, 9)]
    knowledge = [
        {"item_id": 1, "title": "Flask", "answer_points": ["请求上下文"], "source": "原创资料"},
        {"item_id": 2, "title": "Python", "answer_points": ["迭代器"], "source": "原创资料"},
    ]

    context = ContextService.assemble("请介绍 Flask 项目？", plan, resume, turns, knowledge, max_chars=10000)

    assert [section["type"] for section in context["sections"][:3]] == [
        "current_question",
        "job_description",
        "resume_facts",
    ]
    turn_sections = [section for section in context["sections"] if section["type"] == "recent_turn"]
    # P2 瘦身后只保留最近 2 轮（旧断言为 6 轮）。
    assert [section["turn_number"] for section in turn_sections] == [7, 8]
    assert context["knowledge_item_ids"] == [1, 2]
    assert "<current_question>" in context["text"]
    assert "<resume_facts>" in context["text"]


def test_context_budget_removes_old_turns_then_low_priority_knowledge():
    plan = SimpleNamespace(job_description="J" * 80, tech_tags=["Python"], level="中级")
    resume = SimpleNamespace(parsed_data={"facts": "R" * 80})
    turns = [SimpleNamespace(turn_number=index, transcript="Q" * 60, reference_answer="A" * 60) for index in range(1, 4)]
    knowledge = [
        {"item_id": 1, "title": "高相关", "standard_answer": "K" * 70, "source": "source"},
        {"item_id": 2, "title": "低相关", "standard_answer": "L" * 70, "source": "source"},
    ]

    context = ContextService.assemble("当前问题？", plan, resume, turns, knowledge, max_chars=520)

    turn_numbers = [section["turn_number"] for section in context["sections"] if section["type"] == "recent_turn"]
    assert 1 not in turn_numbers
    assert context["knowledge_item_ids"] in ([1], [])
    assert len(context["text"]) <= 520


def test_knowledge_lookup_failure_degrades_to_empty_context():
    class UnavailableKnowledge:
        def search(self, *_args, **_kwargs):
            raise RuntimeError("index unavailable")

    plan = SimpleNamespace(position_name="后端工程师", tech_tags=["Python"], level="中级")
    result = ContextService(UnavailableKnowledge()).search_knowledge(1, "解释 Flask 上下文", plan)

    assert result == {
        "items": [],
        "status": "unavailable",
        "message": "知识增强暂不可用，已使用简历和岗位上下文继续回答。",
    }
