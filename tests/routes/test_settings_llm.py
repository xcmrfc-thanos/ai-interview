"""模型配置设置接口：加密存储、掩码回显、留空保留、运行时读库优先。

测试内 API Key 一律使用 dummy 值（unittest-dummy-* 前缀），非真实凭据。
"""
import pytest

import app as application
from utils import llm_config
from utils.secret_box import decrypt_secret


DUMMY_KEY = "unittest-dummy-key-1234567890"


def login(client):
    with client.session_transaction() as session:
        session["user_id"] = 1
        session["role"] = "applicant"


def _client():
    client = application.app.test_client()
    login(client)
    return client


@pytest.fixture
def client():
    """已登录 applicant 会话的 Flask test client。"""
    return _client()


def _cleanup():
    from models import db
    from models.LlmConfig import LlmConfig

    with application.app.app_context():
        row = db.session.get(LlmConfig, 1)
        if row is not None:
            db.session.delete(row)
            db.session.commit()
    llm_config.invalidate_llm_config_cache()


def test_secret_box_roundtrip_and_failure_modes(monkeypatch):
    from utils.secret_box import encrypt_secret, mask_secret

    monkeypatch.setenv("CONFIG_ENCRYPTION_KEY", "unit-test-key")
    token = encrypt_secret(DUMMY_KEY)
    assert token and token != DUMMY_KEY
    assert decrypt_secret(token) == DUMMY_KEY

    # 密钥轮换后解密失败 → None（按未配置处理，不抛异常）
    monkeypatch.setenv("CONFIG_ENCRYPTION_KEY", "rotated-key")
    assert decrypt_secret(token) is None

    # 无加密密钥：拒绝加密而非明文落库
    monkeypatch.delenv("CONFIG_ENCRYPTION_KEY", raising=False)
    monkeypatch.delenv("APP_SECRET_KEY", raising=False)
    assert encrypt_secret(DUMMY_KEY) is None
    assert mask_secret(DUMMY_KEY) == "****7890"


def test_settings_llm_roundtrip_mask_and_keep_empty(client, monkeypatch):
    _cleanup()
    # 环境基线确定性：无 .env 的 LLM_API_KEY 时初始态为 none；有则回退展示 environment
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    client = _client()

    # 初始：未配置数据库 → 掩码为空、来源 none
    res = client.get("/api/settings/llm")
    assert res.status_code == 200
    config = res.get_json()["config"]
    assert config["api_key_source"] == "none"
    assert config["has_api_key"] is False

    # .env 存在 LLM_API_KEY 时：初始态回退展示 environment 来源（掩码，不回显明文）
    monkeypatch.setenv("LLM_API_KEY", "unittest-dummy-env-key-1234567890")
    config = client.get("/api/settings/llm").get_json()["config"]
    assert config["api_key_source"] == "environment"
    assert config["api_key_masked"] == "****7890"
    assert config["has_api_key"] is True
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    config = client.get("/api/settings/llm").get_json()["config"]
    assert config["api_key_source"] == "none"

    # 保存：字段 + 密钥
    res = client.put(
        "/api/settings/llm",
        json={
            "provider": "SiliconFlow",
            "base_url": "https://api.example.com/v1",
            "model": "test-model",
            "api_key": DUMMY_KEY,
        },
    )
    assert res.status_code == 200, res.get_json()

    # 回读：掩码只露末 4 位；明文与密文均不出现在响应
    res = client.get("/api/settings/llm")
    config = res.get_json()["config"]
    assert config["provider"] == "siliconflow"
    assert config["model"] == "test-model"
    assert config["has_api_key"] is True
    assert config["api_key_masked"] == "****7890"
    assert config["api_key_source"] == "database"
    body = res.get_data(as_text=True)
    assert DUMMY_KEY not in body

    # 落库的是密文：可解回原文
    from models import db
    from models.LlmConfig import LlmConfig

    with application.app.app_context():
        row = db.session.get(LlmConfig, 1)
        assert row.api_key_enc and row.api_key_enc != DUMMY_KEY
        assert decrypt_secret(row.api_key_enc) == DUMMY_KEY

    # 再次保存但 api_key 留空 → 保留原密钥；clear_api_key=True → 清除
    res = client.put("/api/settings/llm", json={"model": "new-model"})
    assert res.status_code == 200
    res = client.put("/api/settings/llm", json={"clear_api_key": True})
    assert res.status_code == 200
    config = client.get("/api/settings/llm").get_json()["config"]
    assert config["model"] == "new-model"
    assert config["has_api_key"] is False

    _cleanup()


def test_db_config_overrides_env_at_runtime(client):
    _cleanup()
    client = _client()

    res = client.put(
        "/api/settings/llm",
        json={"provider": "ark", "model": "db-model", "api_key": "unittest-dummy-runtime-9999"},
    )
    assert res.status_code == 200

    # 保存后立即生效（缓存失效）：读库值覆盖 .env
    with application.app.app_context():
        config = llm_config.get_llm_config()
    assert config["provider"] == "ark"
    assert config["model"] == "db-model"
    assert config["api_key"] == "unittest-dummy-runtime-9999"
    assert config["api_key_source"] == "database"

    _cleanup()
    with application.app.app_context():
        config = llm_config.get_llm_config()
    assert config["api_key_source"] != "database"


def test_settings_llm_requires_login():
    client = application.app.test_client()
    assert client.get("/api/settings/llm").status_code == 401
    assert client.put("/api/settings/llm", json={}).status_code == 401
