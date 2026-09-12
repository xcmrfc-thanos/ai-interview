import secrets

import utils.llm_client as llm_client


def test_llm_client_reuses_connection_pool_for_same_configuration(monkeypatch):
    created = []
    # 测试专用一次性口令：随机生成，不使用任何真实凭据字面量
    api_key = "test-" + secrets.token_hex(8)

    class FakeClient:
        def __init__(self, **kwargs):
            created.append(kwargs)

    monkeypatch.setattr(llm_client, "OpenAI", FakeClient)
    monkeypatch.setenv("LLM_API_KEY", api_key)
    monkeypatch.setenv("LLM_BASE_URL", "https://example.test/v1")
    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "30")
    llm_client.clear_llm_client_cache()

    first = llm_client.get_llm_client()
    second = llm_client.get_llm_client()

    assert first is second
    assert created == [{
        "api_key": api_key,
        "base_url": "https://example.test/v1",
        "timeout": 30.0,
    }]
    llm_client.clear_llm_client_cache()


def test_chat_complete_selects_model_by_kind_and_applies_thinking_switch(monkeypatch):
    calls = []

    class FakeCompletions:
        def create(self, **kwargs):
            calls.append(kwargs)
            return object()

    class FakeChat:
        completions = FakeCompletions()

    class FakeClient:
        def __init__(self, **kwargs):
            self.chat = FakeChat()

    monkeypatch.setattr(llm_client, "OpenAI", FakeClient)
    monkeypatch.setenv("LLM_API_KEY", "test-key")
    monkeypatch.setenv("LLM_BASE_URL", "https://example.test/v1")
    monkeypatch.setenv("LLM_MODEL", "fast-model")
    monkeypatch.setenv("LLM_THINK_MODEL", "smart-model")
    monkeypatch.setenv("LLM_THINKING", "")
    llm_client.clear_llm_client_cache()

    llm_client.chat_complete([{"role": "user", "content": "hi"}])
    assert calls[-1]["model"] == "fast-model"
    assert "extra_body" not in calls[-1]

    llm_client.chat_complete([{"role": "user", "content": "hi"}], kind="think")
    assert calls[-1]["model"] == "smart-model"
    assert "extra_body" not in calls[-1]

    monkeypatch.setenv("LLM_THINKING", "false")
    llm_client.chat_complete([{"role": "user", "content": "hi"}], kind="think")
    assert calls[-1]["extra_body"] == {"enable_thinking": False}

    monkeypatch.setenv("LLM_THINKING", "true")
    llm_client.chat_complete([{"role": "user", "content": "hi"}], kind="think")
    assert calls[-1]["extra_body"] == {"enable_thinking": True}

    llm_client.chat_complete(
        [{"role": "user", "content": "hi"}], kind="think", extra_body={"user": "u1"}
    )
    assert calls[-1]["extra_body"] == {"user": "u1", "enable_thinking": True}
    llm_client.clear_llm_client_cache()
