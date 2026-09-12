"""Generate interview preparation packs without inventing candidate facts."""

import hashlib
import json
import re

from .structured_output import extract_json_object, validate_payload


NUMBER_PATTERN = re.compile(r"\d+(?:\.\d+)?%?")
SECTION_FIELDS = (
    "intro_30",
    "intro_60",
    "intro_90",
    "highlights",
    "project_followups",
    "risk_points",
    "frequent_questions",
    "star_stories",
    "review_topics",
    "source_evidence",
)
INTRO_FIELDS = {"intro_30", "intro_60", "intro_90"}


def source_fingerprint(job_description, resume_facts, extra_requirements=""):
    content = json.dumps(
        {
            "job_description": job_description or "",
            "resume_facts": resume_facts or {},
            "extra_requirements": extra_requirements or "",
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


class PreparationService:
    def __init__(self, llm_complete):
        self._llm_complete = llm_complete

    def generate(self, plan, resume):
        resume_facts = _resume_facts(resume)
        messages = self._messages(plan, resume_facts)
        payload = validate_payload(extract_json_object(self._llm_complete(messages)))
        unverified = _unverified_numbers(payload, plan, resume_facts)
        payload["needs_review"] = bool(unverified)
        payload["unverified_claims"] = unverified
        payload["source_fingerprint"] = source_fingerprint(
            plan.job_description,
            resume_facts,
            plan.extra_requirements,
        )
        return payload

    def generate_section(self, plan, resume, field):
        if field not in SECTION_FIELDS:
            raise ValueError("不支持重新生成该内容")
        resume_facts = _resume_facts(resume)
        messages = self._section_messages(plan, resume_facts, field)
        payload = extract_json_object(self._llm_complete(messages))
        value = _section_value(payload, field)
        result = {field: value}
        unverified = _unverified_numbers(result, plan, resume_facts)
        result["needs_review"] = bool(unverified)
        result["unverified_claims"] = unverified
        result["source_fingerprint"] = source_fingerprint(
            plan.job_description,
            resume_facts,
            plan.extra_requirements,
        )
        return result

    @staticmethod
    def _messages(plan, resume_facts):
        system = (
            "你是个人面试准备助手。只能重组输入中的真实事实，禁止虚构项目、公司、职责、"
            "技术使用经历和数字。输出单个 JSON 对象，不输出 Markdown。"
        )
        user = f"""
<resume_facts>{json.dumps(resume_facts, ensure_ascii=False)}</resume_facts>
<job_description>{plan.job_description}</job_description>
<user_requirements>{plan.extra_requirements or ''}</user_requirements>
<position>{plan.company_name} / {plan.position_name} / {plan.level or '未指定'}</position>
<tech_tags>{json.dumps(plan.tech_tags or [], ensure_ascii=False)}</tech_tags>

生成 intro_30、intro_60、intro_90、highlights、project_followups、risk_points、
frequent_questions、star_stories、review_topics、source_evidence。highlights 和
source_evidence 中每项必须包含 source_type 与 source_excerpt。
        """.strip()
        return [{"role": "system", "content": system}, {"role": "user", "content": user}]

    @staticmethod
    def _section_messages(plan, resume_facts, field):
        system = (
            "你是个人面试准备助手。只能重组输入中的真实事实，禁止虚构项目、公司、职责、"
            "技术使用经历和数字。只输出一个 JSON 对象，且只能包含指定区块，不输出 Markdown。"
        )
        format_hint = "字符串" if field in INTRO_FIELDS else "数组"
        evidence_hint = (
            "数组中的每项必须包含 source_type 与 source_excerpt。"
            if field in {"highlights", "source_evidence"}
            else ""
        )
        user = f"""
<resume_facts>{json.dumps(resume_facts, ensure_ascii=False)}</resume_facts>
<job_description>{plan.job_description}</job_description>
<user_requirements>{plan.extra_requirements or ''}</user_requirements>
<position>{plan.company_name} / {plan.position_name} / {plan.level or '未指定'}</position>
<tech_tags>{json.dumps(plan.tech_tags or [], ensure_ascii=False)}</tech_tags>

只生成 {field}，返回格式为 {format_hint}。{evidence_hint}
""".strip()
        return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def _resume_facts(resume):
    value = getattr(resume, "parsed_data", {}) or {}
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return {"raw_text": value}
    return value


def _unverified_numbers(payload, plan, resume_facts):
    source = " ".join(
        [
            json.dumps(resume_facts, ensure_ascii=False),
            str(plan.job_description or ""),
            str(plan.extra_requirements or ""),
        ]
    )
    generated = " ".join(
        str(payload.get(field, "")) for field in ("intro_30", "intro_60", "intro_90")
    )
    return sorted(
        {
            number.rstrip("%")
            for number in NUMBER_PATTERN.findall(generated)
            if number not in source
        }
    )


def _section_value(payload, field):
    value = payload.get(field)
    if field in INTRO_FIELDS:
        value = str(value or "").strip()
        if not value:
            raise ValueError(f"模型未返回该区块内容: {field}")
        return value
    if not isinstance(value, list) or not value:
        raise ValueError(f"模型未返回该区块内容: {field}")
    return value
