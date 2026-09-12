"""API 契约测试（docs/api-contract.md）。

覆盖：统一错误信封、JSON 登录/注册、契约路径别名、WS v2 音频帧解析、
SPA 托管默认关闭。双后端契约一致性的 Java 侧由 CopilotWsPolicy/CopilotAudioFrame
等 JUnit 测试覆盖。
"""

import secrets

import pytest


@pytest.fixture()
def client():
    import app as application

    application.app.config["TESTING"] = True
    return application.app.test_client()


def _random_password():
    """测试用一次性口令：随机生成，不落任何真实凭据。"""
    return f"Pw-{secrets.token_hex(12)}"


def test_health_and_ready_envelope(client):
    assert client.get("/api/health").status_code == 200


def test_protected_api_returns_401_envelope(client):
    res = client.post("/api/copilot/sessions", json={})
    assert res.status_code == 401
    assert res.get_json()["success"] is False
    for path in ("/api/interview-plans", "/api/knowledge", "/api/reviews"):
        res = client.get(path)
        assert res.status_code == 401, path
        body = res.get_json()
        assert body["success"] is False, path
        assert isinstance(body["message"], str) and body["message"], path


def test_login_json_rejects_bad_credentials(client):
    res = client.post(
        "/api/login",
        json={"email": f"nobody-{secrets.token_hex(4)}@example.com", "password": _random_password()},
        content_type="application/json",
    )
    assert res.status_code == 401
    assert res.get_json()["success"] is False


def test_logout_json_contract(client):
    res = client.post("/api/logout")
    assert res.status_code == 200
    assert res.get_json() == {"success": True}


def test_resumes_alias_requires_auth(client):
    res = client.get("/api/resumes")
    assert res.status_code in (302, 401)


def test_register_json_password_mismatch(client):
    res = client.post(
        "/api/register",
        json={
            "email": f"contract-{secrets.token_hex(4)}@example.com",
            "password": _random_password(),
            "confirm_password": _random_password(),
            "role": "applicant",
        },
        content_type="application/json",
    )
    assert res.status_code == 400
    assert res.get_json()["success"] is False


def test_ws_v2_audio_frame_parse():
    import struct

    from utils.copilot_ws import _parse_audio_frame

    pcm = b"\x01\x00\x02\x00"
    header = b"AI" + bytes([2, 1]) + struct.pack("<I", 7)
    version, source, sequence, parsed_pcm = _parse_audio_frame(header + pcm)
    assert version == 2
    assert source == "microphone"
    assert sequence == 7
    assert parsed_pcm == pcm

    # v1 裸 PCM 兼容
    version, source, sequence, parsed_pcm = _parse_audio_frame(pcm)
    assert version == 1
    assert sequence == -1
    assert parsed_pcm == pcm

    # v2 未知来源码回退 mixed
    header = b"AI" + bytes([2, 9]) + struct.pack("<I", 0)
    version, source, sequence, _ = _parse_audio_frame(header)
    assert source == "mixed"


def test_spa_hosting_disabled_by_default(client):
    """默认不启用 SPA 托管：遗留页面行为（302 鉴权）不被 /assets 中间件抢占。"""
    import app as application

    assert application._SPA_ENABLED is False
    res = client.get("/applicant/dashboard")
    assert res.status_code == 302


def test_contract_doc_exists():
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    assert (root / "docs" / "api-contract.md").is_file()
    assert (root / "docs" / "web-page-inventory.md").is_file()
