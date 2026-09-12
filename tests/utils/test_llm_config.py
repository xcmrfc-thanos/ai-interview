from utils.llm_config import (
    DEFAULT_LLM_MODEL,
    get_llm_config,
    get_llm_thinking,
    get_llm_timeout,
    load_env_file,
)


def test_load_env_file_keeps_existing_environment_value(tmp_path, monkeypatch):
    env_file = tmp_path / ".env"
    env_file.write_text("LLM_MODEL=Qwen/Qwen3-8B\nLLM_API_KEY=test-key\n", encoding="utf-8")
    monkeypatch.setenv("LLM_MODEL", "THUDM/GLM-Z1-9B-0414")

    load_env_file(env_file)

    assert get_llm_config()["model"] == "THUDM/GLM-Z1-9B-0414"


def test_llm_config_uses_siliconflow_defaults_when_unset(monkeypatch):
    for name in ("LLM_PROVIDER", "LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL", "LLM_THINK_MODEL"):
        monkeypatch.delenv(name, raising=False)

    config = get_llm_config()

    assert config["provider"] == "siliconflow"
    assert config["base_url"] == "https://api.siliconflow.cn/v1"
    assert config["model"] == DEFAULT_LLM_MODEL
    assert config["think_model"] == DEFAULT_LLM_MODEL


def test_think_model_falls_back_to_llm_model(monkeypatch):
    monkeypatch.setenv("LLM_MODEL", "fast-model")
    monkeypatch.delenv("LLM_THINK_MODEL", raising=False)
    assert get_llm_config()["think_model"] == "fast-model"

    monkeypatch.setenv("LLM_THINK_MODEL", "  ")
    assert get_llm_config()["think_model"] == "fast-model"


def test_think_model_can_be_configured_separately(monkeypatch):
    monkeypatch.setenv("LLM_MODEL", "fast-model")
    monkeypatch.setenv("LLM_THINK_MODEL", "smart-model")
    config = get_llm_config()
    assert config["model"] == "fast-model"
    assert config["think_model"] == "smart-model"


def test_llm_thinking_parsing(monkeypatch):
    monkeypatch.delenv("LLM_THINKING", raising=False)
    assert get_llm_thinking() is None

    for value in ("true", "TRUE", "1", "yes", "on"):
        monkeypatch.setenv("LLM_THINKING", value)
        assert get_llm_thinking() is True

    for value in ("false", "FALSE", "0", "no", "off"):
        monkeypatch.setenv("LLM_THINKING", value)
        assert get_llm_thinking() is False


def test_llm_timeout_allows_reasoning_model_startup_latency(monkeypatch):
    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "60")
    assert get_llm_timeout() == 60

    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "not-a-number")
    assert get_llm_timeout() == 45
