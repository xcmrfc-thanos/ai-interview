"""Strict but recoverable parsing for LLM structured responses."""

import json
import re


PACK_LIST_FIELDS = (
    "highlights",
    "project_followups",
    "risk_points",
    "frequent_questions",
    "star_stories",
    "review_topics",
    "source_evidence",
)


class StructuredOutputError(ValueError):
    pass


def extract_json_object(text):
    content = str(text or "").strip()
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", content, re.DOTALL | re.IGNORECASE)
    candidate = fenced.group(1) if fenced else _balanced_object(content)
    if not candidate:
        raise StructuredOutputError("响应中没有有效 JSON 对象")
    try:
        value = json.loads(candidate)
    except json.JSONDecodeError as error:
        raise StructuredOutputError("响应中的 JSON 无法解析") from error
    if not isinstance(value, dict):
        raise StructuredOutputError("JSON 根节点必须是对象")
    return value


def validate_payload(payload):
    value = dict(payload or {})
    for field in ("intro_30", "intro_60", "intro_90"):
        if not str(value.get(field, "")).strip():
            raise StructuredOutputError(f"缺少必填字段 {field}")
        value[field] = str(value[field]).strip()
    for field in PACK_LIST_FIELDS:
        field_value = value.get(field, [])
        value[field] = field_value if isinstance(field_value, list) else []
    return value


def _balanced_object(content):
    start = content.find("{")
    if start < 0:
        return None
    depth = 0
    in_string = False
    escaped = False
    for index in range(start, len(content)):
        char = content[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return content[start:index + 1]
    return None
