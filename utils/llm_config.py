"""Local LLM configuration without committing credentials."""

import os
from pathlib import Path


DEFAULT_LLM_MODEL = "THUDM/GLM-Z1-9B-0414"
DEFAULT_LLM_BASE_URL = "https://api.siliconflow.cn/v1"
DEFAULT_LLM_TIMEOUT_SECONDS = 45

# 火山引擎方舟（LLM_PROVIDER=ark）：OpenAI 兼容，coding 通道低延迟，适合实时 Copilot。
# 统一走 LLM_* 配置：切 ark 只需改 .env 里 LLM_BASE_URL/LLM_API_KEY/LLM_MODEL，
# 不引入额外前缀。Copilot 未显式配 LLM_COPILOT_MODEL 时默认用 deepseek-v4-flash。
ARK_DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/coding/v3"
ARK_DEFAULT_COPILOT_MODEL = "deepseek-v4-flash"


def load_env_file(path: Path) -> None:
    """Load simple KEY=VALUE entries without overwriting process variables."""
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key:
            os.environ.setdefault(key, value.strip().strip('"').strip("'"))


def load_project_env() -> None:
    load_env_file(Path(__file__).resolve().parent.parent / ".env")


# 数据库配置覆盖的短 TTL 缓存：避免每次 LLM 调用都查库；
# 设置保存接口调用 invalidate_llm_config_cache() 立即生效。
_DB_CONFIG_CACHE: dict | None = None
_DB_CONFIG_CACHE_AT = 0.0
_DB_CONFIG_TTL_SECONDS = 5.0


def invalidate_llm_config_cache() -> None:
    global _DB_CONFIG_CACHE, _DB_CONFIG_CACHE_AT
    _DB_CONFIG_CACHE = None
    _DB_CONFIG_CACHE_AT = 0.0


def _db_override() -> dict | None:
    """读取 llm_config 单例行（含解密后的 api_key）；无行/无 app 上下文返回 None。"""
    global _DB_CONFIG_CACHE, _DB_CONFIG_CACHE_AT
    import time

    from flask import has_app_context

    if not has_app_context():
        return None
    now = time.monotonic()
    if _DB_CONFIG_CACHE is not None and now - _DB_CONFIG_CACHE_AT < _DB_CONFIG_TTL_SECONDS:
        return _DB_CONFIG_CACHE
    from models import db
    from models.LlmConfig import LlmConfig
    from .secret_box import decrypt_secret

    row = db.session.get(LlmConfig, 1)
    if row is None:
        _DB_CONFIG_CACHE, _DB_CONFIG_CACHE_AT = {}, now
        return _DB_CONFIG_CACHE
    api_key = decrypt_secret(row.api_key_enc) or ""
    override = {
        key: value
        for key, value in {
            **row.to_dict(),
            "api_key": api_key,
        }.items()
        if value
    }
    _DB_CONFIG_CACHE, _DB_CONFIG_CACHE_AT = override, now
    return override


def get_llm_config() -> dict:
    provider = os.getenv("LLM_PROVIDER", "siliconflow").strip().lower()
    model = os.getenv("LLM_MODEL", DEFAULT_LLM_MODEL)
    if provider == "ark":
        # 火山引擎方舟：与其它 provider 完全同构，统一走 LLM_* 配置；
        # 仅默认 base_url 与 Copilot 快模型不同（deepseek-v4-flash，coding 通道低延迟）。
        base_url = os.getenv("LLM_BASE_URL", ARK_DEFAULT_BASE_URL)
        copilot_model = (
            os.getenv("LLM_COPILOT_MODEL", "").strip()
            or ARK_DEFAULT_COPILOT_MODEL
        )
    else:
        base_url = os.getenv("LLM_BASE_URL", DEFAULT_LLM_BASE_URL)
        copilot_model = os.getenv("LLM_COPILOT_MODEL", "").strip() or model
    config = {
        "provider": provider,
        "api_key": os.getenv("LLM_API_KEY", ""),
        "base_url": base_url,
        "model": model,
        "copilot_model": copilot_model,
        "think_model": os.getenv("LLM_THINK_MODEL", "").strip() or model,
    }
    # 设置页保存的数据库配置优先于 .env；逐字段覆盖，只覆盖非空项
    override = _db_override()
    if override:
        config.update(override)
    config["api_key_source"] = "database" if override else ("environment" if config["api_key"] else "none")
    return config


def get_llm_thinking():
    """LLM_THINKING 思考开关（仅作用于 think 模型）。

    未配置返回 None（不传参数，保持模型默认行为）；
    显式配置时返回 True/False，用于传 enable_thinking。
    """
    value = os.getenv("LLM_THINKING", "").strip()
    if not value:
        return None
    return value.lower() in ("1", "true", "yes", "on")


def get_llm_timeout(default=DEFAULT_LLM_TIMEOUT_SECONDS) -> float:
    """Return a bounded provider timeout; reasoning models need more than 12s."""
    try:
        value = float(os.getenv("LLM_TIMEOUT_SECONDS", str(default)))
    except (TypeError, ValueError):
        value = float(default)
    return max(5.0, min(value, 120.0))
