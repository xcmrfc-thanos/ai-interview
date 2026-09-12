"""LLM 运行时设置：设置页可视化配置提供商/模型/APIKey。

api_key 经 utils.secret_box 加密后落库；加密密钥来自 .env 的
CONFIG_ENCRYPTION_KEY（缺失时由 APP_SECRET_KEY 派生），明文不落库、不回显。
未保存数据库配置时运行时回退 .env 的 LLM_* 变量（见 utils.llm_config）。
"""

from flask import Blueprint, jsonify, request

from models import db, utc_now
from models.LlmConfig import LlmConfig
from utils.auth_security import applicant_required
from utils.llm_config import get_llm_config, invalidate_llm_config_cache
from utils.secret_box import decrypt_secret, encrypt_secret, mask_secret

settings_bp = Blueprint("settings", __name__)

_TEXT_FIELDS = ("provider", "base_url", "model", "copilot_model", "think_model")


def _load_row():
    return db.session.get(LlmConfig, 1)


@settings_bp.route("/settings/llm", methods=["GET"])
@applicant_required
def get_llm_settings():
    row = _load_row()
    runtime = get_llm_config()
    payload = {
        "provider": "",
        "base_url": "",
        "model": "",
        "copilot_model": "",
        "think_model": "",
        "has_api_key": False,
        "api_key_masked": "",
        "api_key_source": "none",
        "updated_at": None,
    }
    if row is not None:
        payload.update(row.to_dict())
        payload["updated_at"] = row.updated_at.isoformat() if row.updated_at else None
    # 密钥展示：数据库密钥优先；未存库（或未存密钥）时回退展示 .env 来源，
    # 与运行时 get_llm_config 的实际取值保持一致
    db_key = decrypt_secret(row.api_key_enc) if row is not None else None
    if db_key:
        payload["has_api_key"] = True
        payload["api_key_masked"] = mask_secret(db_key)
        payload["api_key_source"] = "database"
    else:
        env_key = runtime.get("api_key") or ""
        payload["has_api_key"] = bool(env_key)
        payload["api_key_masked"] = mask_secret(env_key)
        payload["api_key_source"] = "environment" if env_key else "none"
    return jsonify({"success": True, "config": payload})


@settings_bp.route("/settings/llm", methods=["PUT"])
@applicant_required
def update_llm_settings():
    payload = request.get_json(silent=True) or {}
    row = _load_row()
    if row is None:
        row = LlmConfig(id=1)
        db.session.add(row)

    for field in _TEXT_FIELDS:
        if field in payload:
            value = str(payload.get(field) or "").strip()
            if field == "base_url" and value and not value.lower().startswith(("http://", "https://")):
                return jsonify({"success": False, "message": "Base URL 必须以 http(s):// 开头"}), 400
            if field == "provider":
                value = value.lower()
            setattr(row, field, value)

    if payload.get("clear_api_key"):
        row.api_key_enc = None
    api_key = str(payload.get("api_key") or "").strip()
    if api_key:
        token = encrypt_secret(api_key)
        if token is None:
            return jsonify({
                "success": False,
                "message": "未配置加密密钥（.env 需设置 CONFIG_ENCRYPTION_KEY 或 APP_SECRET_KEY），拒绝明文保存 API Key",
            }), 400
        row.api_key_enc = token

    row.updated_at = utc_now()
    db.session.commit()
    invalidate_llm_config_cache()
    return jsonify({"success": True})
