"""对称加密盒：用于落库前加密 API Key 等敏感配置。

密钥解析优先级：CONFIG_ENCRYPTION_KEY > APP_SECRET_KEY；二者都缺失时
（如未配置密钥的本地进程）返回 None，调用方跳过加密保存而不是明文落库。
Fernet（AES-CBC + HMAC）由 cryptography 库提供，密钥经 SHA-256 派生为 32 字节。
"""

from __future__ import annotations

import base64
import hashlib
import os


def _fernet():
    secret = os.getenv("CONFIG_ENCRYPTION_KEY", "").strip() or os.getenv("APP_SECRET_KEY", "").strip()
    if not secret:
        return None
    from cryptography.fernet import Fernet

    key = base64.urlsafe_b64encode(hashlib.sha256(secret.encode("utf-8")).digest())
    return Fernet(key)


def encrypt_secret(plain: str) -> str | None:
    """加密明文；无法取得加密密钥时返回 None。"""
    box = _fernet()
    if box is None or not plain:
        return None
    return box.encrypt(plain.encode("utf-8")).decode("ascii")


def decrypt_secret(token: str | None) -> str | None:
    """解密；token 为空、密钥缺失或不匹配（如加密密钥被轮换）时返回 None。"""
    box = _fernet()
    if box is None or not token:
        return None
    try:
        return box.decrypt(token.encode("ascii")).decode("utf-8")
    except Exception:  # noqa: BLE001 密钥轮换/数据损坏：按未配置处理
        return None


def mask_secret(plain: str | None) -> str:
    """掩码展示：保留末 4 位，供前端确认已配置的密钥。"""
    if not plain:
        return ""
    tail = plain[-4:] if len(plain) >= 8 else "****"
    return f"****{tail}"
