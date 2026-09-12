import io
import json

from flask import Flask

from models import db, import_workspace_models
from models.User import User
from routes.route_knowledge import knowledge_bp


def build_app():
    app = Flask(__name__)
    app.secret_key = "test"
    app.config.update(
        TESTING=True,
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
        MAX_CONTENT_LENGTH=1024 * 1024,
    )
    db.init_app(app)
    import_workspace_models()
    app.register_blueprint(knowledge_bp, url_prefix="/api")
    with app.app_context():
        db.create_all()
        db.session.add_all([
            User(user_id=1, email="owner@example.com", password="x", role="applicant"),
            User(user_id=2, email="other@example.com", password="x", role="applicant"),
        ])
        db.session.commit()
    return app


def login(client, user_id=1):
    with client.session_transaction() as flask_session:
        flask_session["user_id"] = user_id
        flask_session["role"] = "applicant"


def item_payload():
    return {
        "title": "Flask 请求上下文",
        "question": "Flask 如何隔离请求上下文？",
        "category": "Python Web",
        "difficulty": "中级",
        "tech_tags": ["Python", "Flask"],
        "role_tags": ["后端"],
        "core_conclusion": "使用 context local 隔离每个请求的数据。",
        "answer_points": ["应用上下文", "请求上下文"],
        "standard_answer": "先区分应用上下文和请求上下文，再说明生命周期。",
        "source": "项目原创整理",
        "source_url": "https://flask.palletsprojects.com/",
    }


def test_knowledge_crud_status_and_ownership():
    app = build_app()
    client = app.test_client()
    login(client)

    empty = client.get("/api/knowledge")
    created = client.post("/api/knowledge", json=item_payload())
    item_id = created.get_json()["item"]["item_id"]
    updated = client.patch(
        f"/api/knowledge/{item_id}",
        json={"difficulty": "高级", "is_enabled": False},
    )
    listed = client.get("/api/knowledge")

    assert empty.get_json()["knowledge_status"] == {
        "total": 0,
        "enabled": 0,
        "enhancement_enabled": False,
    }
    assert created.status_code == 201
    assert created.get_json()["item"]["source"] == "项目原创整理"
    assert updated.get_json()["item"]["difficulty"] == "高级"
    assert updated.get_json()["item"]["is_enabled"] is False
    assert listed.get_json()["knowledge_status"]["total"] == 1

    login(client, user_id=2)
    assert client.patch(f"/api/knowledge/{item_id}", json={"is_enabled": True}).status_code == 404


def test_knowledge_file_import_and_rebuild_feedback():
    app = build_app()
    client = app.test_client()
    login(client)
    payload = json.dumps([
        {"title": "STAR", "question": "如何使用 STAR？", "source": "项目原创整理"}
    ], ensure_ascii=False).encode("utf-8")

    imported = client.post(
        "/api/knowledge/import",
        data={"file": (io.BytesIO(payload), "knowledge.json")},
        content_type="multipart/form-data",
    )
    rebuilt = client.post("/api/knowledge/rebuild-index")

    assert imported.status_code == 200
    assert imported.get_json()["result"]["created"] == 1
    assert rebuilt.status_code == 200
    assert rebuilt.get_json()["checked"] == 1


def test_knowledge_rejects_invalid_payload_and_file_type():
    app = build_app()
    client = app.test_client()
    login(client)

    invalid_item = client.post("/api/knowledge", json={"title": ""})
    invalid_file = client.post(
        "/api/knowledge/import",
        data={"file": (io.BytesIO(b"bad"), "knowledge.exe")},
        content_type="multipart/form-data",
    )

    assert invalid_item.status_code == 400
    assert invalid_file.status_code == 400
