"""Structured mock-interview question generation and answer evaluation."""

import json
import re

from services.structured_output import StructuredOutputError, extract_json_object


SCORE_FIELDS = ("fact_consistency", "job_relevance", "completeness", "expression")
NUMBER_PATTERN = re.compile(r"\d+(?:\.\d+)?%?")


class MockInterviewService:
    def __init__(self, llm_complete):
        self.llm_complete = llm_complete

    def generate_outline(self, plan, resume):
        resume_facts = getattr(resume, "parsed_data", {}) or {}
        messages = [
            {
                "role": "system",
                "content": (
                    "你是模拟面试官。只能基于输入的简历事实和 JD 生成题目。"
                    "输出单个 JSON 对象，不使用 Markdown，不虚构候选人经历。"
                ),
            },
            {
                "role": "user",
                "content": (
                    "<task>question_outline</task>\n"
                    f"<position>{plan.position_name}</position>\n"
                    f"<level>{plan.level or '未指定'}</level>\n"
                    f"<job_description>{plan.job_description}</job_description>\n"
                    f"<tech_tags>{json.dumps(plan.tech_tags or [], ensure_ascii=False)}</tech_tags>\n"
                    f"<resume_facts>{json.dumps(resume_facts, ensure_ascii=False)}</resume_facts>\n"
                    "返回 questions 数组，每项包含 category、question、reference_points。"
                    "尽量覆盖 introduction、resume、technical、scenario、behavior。"
                ),
            },
        ]
        payload = extract_json_object(self.llm_complete(messages))
        questions = payload.get("questions")
        if not isinstance(questions, list) or not questions:
            raise StructuredOutputError("模拟面试题纲为空")
        normalized = []
        for item in questions:
            if not isinstance(item, dict) or not str(item.get("question", "")).strip():
                continue
            normalized.append({
                "category": str(item.get("category") or "general").strip(),
                "question": str(item["question"]).strip(),
                "reference_points": _text_list(item.get("reference_points")),
            })
        if not normalized:
            raise StructuredOutputError("模拟面试题纲没有有效题目")
        return normalized

    def evaluate_answer(self, question, answer, resume_facts, job_description):
        original_answer = str(answer or "").strip()
        messages = [
            {
                "role": "system",
                "content": (
                    "你是严谨的模拟面试评估员。依据简历事实和 JD 评价回答，"
                    "输出单个 JSON 对象，禁止把回答中的新说法当成已确认事实。"
                    "不得在参考要点或改进建议中编造示例数字；缺少真实指标时，"
                    "只提示候选人补充本人可核查的数据。"
                ),
            },
            {
                "role": "user",
                "content": (
                    f"<question>{question}</question>\n"
                    f"<answer>{original_answer}</answer>\n"
                    f"<resume_facts>{json.dumps(resume_facts or {}, ensure_ascii=False)}</resume_facts>\n"
                    f"<job_description>{job_description or ''}</job_description>\n"
                    "返回 reference_points、scores、strengths、improvements。scores 必须包含 "
                    "fact_consistency、job_relevance、completeness、expression，范围 0-100。"
                ),
            },
        ]
        try:
            payload = extract_json_object(self.llm_complete(messages))
            scores = _scores(payload.get("scores"))
        except (StructuredOutputError, ValueError, RuntimeError):
            return {
                "answer": original_answer,
                "retryable": True,
                "error_code": "EVALUATION_PARSE_FAILED",
            }
        source_text = "\n".join([
            str(question or ""),
            original_answer,
            json.dumps(resume_facts or {}, ensure_ascii=False),
            str(job_description or ""),
        ])
        feedback, fact_guard_triggered = _guard_generated_feedback(payload, source_text)
        return {
            "answer": original_answer,
            "reference_points": feedback["reference_points"],
            "scores": scores,
            "strengths": feedback["strengths"],
            "improvements": feedback["improvements"],
            "fact_risks": _unverified_numbers(original_answer, resume_facts),
            "fact_guard_triggered": fact_guard_triggered,
            "retryable": False,
            "error_code": None,
        }


def _scores(value):
    if not isinstance(value, dict):
        raise ValueError("评分结构无效")
    result = {}
    for field in SCORE_FIELDS:
        score = float(value.get(field, 0))
        if not 0 <= score <= 100:
            raise ValueError("评分超出范围")
        result[field] = score
    return result


def _text_list(value):
    return [str(item).strip() for item in value if str(item).strip()] if isinstance(value, list) else []


def _unverified_numbers(answer, resume_facts):
    source = json.dumps(resume_facts or {}, ensure_ascii=False)
    return sorted({match.rstrip("%") for match in NUMBER_PATTERN.findall(answer) if match not in source})


def _guard_generated_feedback(payload, source_text):
    allowed = {value.rstrip("%") for value in NUMBER_PATTERN.findall(source_text)}
    result = {}
    triggered = False
    for field in ("reference_points", "strengths", "improvements"):
        safe_items = []
        for item in _text_list(payload.get(field)):
            generated = {value.rstrip("%") for value in NUMBER_PATTERN.findall(item)}
            if generated - allowed:
                triggered = True
                continue
            safe_items.append(item)
        result[field] = safe_items
    if triggered:
        result["improvements"].append(
            "如需量化效果，只补充本人可核查的真实指标；没有数据时说明验证方法。"
        )
    return result, triggered
