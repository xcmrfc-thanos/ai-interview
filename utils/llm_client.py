"""Process-local OpenAI-compatible client with connection-pool reuse."""

from functools import lru_cache

from openai import OpenAI

from .llm_config import get_llm_config, get_llm_thinking, get_llm_timeout, load_project_env


def get_llm_client(timeout=None):
    """Return a cached client for the active provider configuration."""
    load_project_env()
    config = get_llm_config()
    if not config["api_key"]:
        raise RuntimeError("未配置 LLM_API_KEY")
    effective_timeout = float(timeout if timeout is not None else get_llm_timeout())
    return _cached_client(
        config["api_key"],
        config["base_url"],
        effective_timeout,
    )


def chat_complete(messages, kind="default", timeout=None, temperature=0.2, **kwargs):
    """Unified completion entry: kind="think" uses the strong model.

    kind="copilot" 使用 LLM_COPILOT_MODEL（实时回答的快模型，未配置时回落 LLM_MODEL）；
    LLM_THINKING 显式配置时，think 调用透传 enable_thinking（qwen 系列参数）；
    未配置则保持模型默认思考行为。
    """
    load_project_env()
    config = get_llm_config()
    if not config["api_key"]:
        raise RuntimeError("未配置 LLM_API_KEY")
    thinking = get_llm_thinking()
    if kind == "think" and thinking is not None:
        extra = dict(kwargs.get("extra_body") or {})
        extra["enable_thinking"] = thinking
        kwargs["extra_body"] = extra
    if kind == "think":
        model = config["think_model"]
    elif kind == "copilot":
        model = config["copilot_model"]
    else:
        model = config["model"]
    client = get_llm_client(timeout=timeout)
    return client.chat.completions.create(
        model=model, messages=messages, temperature=temperature, **kwargs
    )


@lru_cache(maxsize=8)
def _cached_client(api_key, base_url, timeout):
    return OpenAI(api_key=api_key, base_url=base_url, timeout=timeout)


def clear_llm_client_cache():
    """Clear cached clients, primarily for configuration changes and tests."""
    _cached_client.cache_clear()
