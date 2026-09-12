"""Generate JD-specific resume suggestions without changing source facts."""

import json
import re

from services.structured_output import StructuredOutputError, extract_json_object


NUMBER_PATTERN = re.compile(r"\d+(?:\.\d+)?%?")


class ResumeOptimizeService:
    def __init__(self, llm_complete):
        self.llm_complete = llm_complete

    def generate(self, plan, resume):
        resume_facts = _facts(resume)
        messages = [
            {
                "role": "system",
                "content": (
                    "你是简历优化助手。只能改写输入中的真实简历事实，禁止新增项目、职责、"
                    "技术实践、公司经历或数字。输出单个 JSON 对象，不使用 Markdown。"
                ),
            },
            {
                "role": "user",
                "content": (
                    f"<resume_facts>{json.dumps(resume_facts, ensure_ascii=False)}</resume_facts>\n"
                    f"<job_description>{plan.job_description}</job_description>\n"
                    f"<position>{plan.position_name}</position>\n"
                    f"<tech_tags>{json.dumps(plan.tech_tags or [], ensure_ascii=False)}</tech_tags>\n"
                    "返回 matches、gaps、keywords、suggestions。每条 suggestion 包含 "
                    "original_fact、candidate_text、rationale、requires_confirmation。"
                ),
            },
        ]
        payload = extract_json_object(self.llm_complete(messages))
        suggestions = payload.get("suggestions")
        if not isinstance(suggestions, list):
            raise StructuredOutputError("优化建议结构无效")
        source_text = json.dumps(resume_facts, ensure_ascii=False)
        normalized = []
        for index, raw in enumerate(suggestions, start=1):
            if not isinstance(raw, dict):
                continue
            candidate = str(raw.get("candidate_text") or "").strip()
            if not candidate:
                continue
            claims = sorted({
                value.rstrip("%") for value in NUMBER_PATTERN.findall(candidate)
                if value not in source_text
            })
            normalized.append({
                "suggestion_id": f"s{index}",
                "original_fact": str(raw.get("original_fact") or "").strip(),
                "candidate_text": candidate,
                "rationale": str(raw.get("rationale") or "").strip(),
                "requires_confirmation": bool(raw.get("requires_confirmation") or claims),
                "unverified_claims": claims,
            })
        return {
            "matches": _text_list(payload.get("matches")),
            "gaps": _text_list(payload.get("gaps")),
            "keywords": _text_list(payload.get("keywords")),
            "suggestions": normalized,
        }


def _facts(resume):
    value = getattr(resume, "parsed_data", {}) or {}
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return {"raw_text": value}
    return json.loads(json.dumps(value, ensure_ascii=False))


def _text_list(value):
    return [str(item).strip() for item in value if str(item).strip()] if isinstance(value, list) else []
