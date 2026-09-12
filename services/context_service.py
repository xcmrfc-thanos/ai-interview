"""Build bounded, ordered Copilot answer context from trusted records."""

import json

from models.CopilotTurn import CopilotTurn
from models.InterviewPlan import InterviewPlan
from models.Resume import Resume
from services.knowledge_service import KnowledgeService


class ContextService:
    def __init__(self, knowledge_service=None):
        self.knowledge_service = knowledge_service or KnowledgeService()

    def build_answer_context(self, copilot_session, question, max_chars=12000):
        plan = InterviewPlan.query.filter_by(
            plan_id=copilot_session.plan_id,
            user_id=copilot_session.user_id,
        ).first()
        resume = Resume.query.filter_by(resume_id=copilot_session.resume_id).first()
        turns = CopilotTurn.query.filter_by(session_id=copilot_session.session_id).order_by(
            CopilotTurn.turn_number.desc()
        ).limit(2).all()[::-1]
        knowledge_result = self.search_knowledge(copilot_session.user_id, question, plan)
        context = self.assemble(
            question, plan, resume, turns, knowledge_result["items"], max_chars=max_chars
        )
        context["knowledge_status"] = knowledge_result["status"]
        context["knowledge_message"] = knowledge_result["message"]
        return context

    def search_knowledge(self, user_id, question, plan):
        try:
            items = self.knowledge_service.search(
                user_id,
                question,
                tech_tags=plan.tech_tags if plan else [],
                role_tags=[plan.position_name] if plan else [],
                difficulty=plan.level if plan else None,
                limit=5,
            )
        except (RuntimeError, OSError, ValueError):
            return {
                "items": [],
                "status": "unavailable",
                "message": "知识增强暂不可用，已使用简历和岗位上下文继续回答。",
            }
        return {
            "items": items,
            "status": "active" if items else "empty",
            "message": "" if items else "未检索到相关知识，已使用简历和岗位上下文继续回答。",
        }

    @staticmethod
    def assemble(question, plan, resume, turns, knowledge_items, max_chars=12000):
        sections = [
            {"type": "current_question", "content": str(question or "").strip()},
            {"type": "job_description", "content": str(getattr(plan, "job_description", "") or "")},
            {
                "type": "resume_facts",
                "content": json.dumps(getattr(resume, "parsed_data", {}) or {}, ensure_ascii=False, sort_keys=True),
            },
        ]
        # P2 prompt 瘦身：实时场景只保留最近 2 轮，旧参考回答截断——
        # 历史价值主要在问题本身，完整旧回答只增加 token 与延迟。
        recent_turns = list(turns or [])[-2:]
        for turn in recent_turns:
            sections.append({
                "type": "recent_turn",
                "turn_number": turn.turn_number,
                "content": json.dumps({
                    "question": turn.transcript or "",
                    "answer": (turn.reference_answer or "")[:400],
                }, ensure_ascii=False),
            })
        for item in knowledge_items or []:
            sections.append({
                "type": "knowledge",
                "item_id": item.get("item_id"),
                "content": json.dumps({
                    "title": item.get("title"),
                    "answer_points": item.get("answer_points") or [],
                    "standard_answer": item.get("standard_answer"),
                    "source": item.get("source"),
                }, ensure_ascii=False),
            })

        sections = _fit_budget(sections, max(max_chars, 160))
        text = _render(sections)
        return {
            "sections": sections,
            "text": text,
            "knowledge_item_ids": [
                section["item_id"] for section in sections
                if section["type"] == "knowledge" and section.get("item_id") is not None
            ],
        }


def _fit_budget(sections, max_chars):
    sections = [dict(section) for section in sections]
    while len(_render(sections)) > max_chars:
        oldest_turn = next((index for index, item in enumerate(sections) if item["type"] == "recent_turn"), None)
        if oldest_turn is not None:
            sections.pop(oldest_turn)
            continue
        lowest_knowledge = next(
            (index for index in range(len(sections) - 1, -1, -1) if sections[index]["type"] == "knowledge"),
            None,
        )
        if lowest_knowledge is not None:
            sections.pop(lowest_knowledge)
            continue
        overflow = len(_render(sections)) - max_chars
        target = max(sections[1:3], key=lambda item: len(item["content"]), default=None)
        if not target or len(target["content"]) <= 24:
            break
        keep = max(24, len(target["content"]) - overflow - 1)
        target["content"] = target["content"][:keep] + "…"
    return sections


def _render(sections):
    chunks = []
    for section in sections:
        kind = section["type"]
        attributes = ""
        if kind == "recent_turn":
            attributes = f' number="{section["turn_number"]}"'
        elif kind == "knowledge":
            attributes = f' item_id="{section.get("item_id", "")}"'
        chunks.append(f"<{kind}{attributes}>\n{section['content']}\n</{kind}>")
    return "\n".join(chunks)
