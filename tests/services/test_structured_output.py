import pytest

from services.structured_output import StructuredOutputError, extract_json_object, validate_payload


def test_extract_json_object_accepts_fenced_json_with_surrounding_text():
    value = extract_json_object("结果如下：```json\n{\"intro_30\": \"你好\"}\n```")

    assert value == {"intro_30": "你好"}


def test_extract_json_object_rejects_empty_or_invalid_content():
    with pytest.raises(StructuredOutputError, match="JSON"):
        extract_json_object("没有结构化结果")


def test_validate_payload_supplies_lists_and_requires_intros():
    value = validate_payload({"intro_30": "30 秒", "intro_60": "60 秒", "intro_90": "90 秒"})

    assert value["highlights"] == []
    assert value["source_evidence"] == []

    with pytest.raises(StructuredOutputError, match="intro_60"):
        validate_payload({"intro_30": "只有一段"})
