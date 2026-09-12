import json

from flask import Flask

from models import db, import_workspace_models
from models.Applicant import Applicant
from models.InterviewPlan import InterviewPlan
from models.Resume import Resume
from models.User import User
from routes.route_interview_plan import interview_plan_bp


def build_app():
    app = Flask(__name__)
    app.secret_key = "test"
    app.config.update(
        TESTING=True,
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
        PREPARATION_LLM_COMPLETE=lambda messages: json.dumps({
            "intro_30": "我有后端项目经验",
            "intro_60": "我使用 Python 和 Flask 构建服务",
            "intro_90": "我负责需求分析、实现和上线复盘",
            "highlights": [{"text": "Python", "source_type": "resume", "source_excerpt": "Python"}],
            "source_evidence": [{"source_type": "resume", "source_excerpt": "Python"}],
        }, ensure_ascii=False),
    )
    db.init_app(app)
    import_workspace_models()
    app.register_blueprint(interview_plan_bp, url_prefix="/api")
    with app.app_context():
        db.create_all()
        first = User(user_id=1, email="first@example.com", password="x", role="applicant")
        second = User(user_id=2, email="second@example.com", password="x", role="applicant")
        db.session.add_all([first, second])
        db.session.flush()
        first_applicant = Applicant(user_id=1, full_name="甲")
        second_applicant = Applicant(user_id=2, full_name="乙")
        db.session.add_all([first_applicant, second_applicant])
        db.session.flush()
        db.session.add_all([
            Resume(resume_id=11, applicant_id=first_applicant.applicant_id, file_url="a.pdf", filename="a.pdf", parsed_data={"skills": ["Python"]}),
            Resume(resume_id=22, applicant_id=second_applicant.applicant_id, file_url="b.pdf", filename="b.pdf", parsed_data={"skills": ["Go"]}),
        ])
        db.session.commit()
    return app


def login(client, user_id=1):
    with client.session_transaction() as session:
        session["user_id"] = user_id
        session["role"] = "applicant"


def plan_payload(resume_id=11):
    payload = {
        "company_name": "星河科技",
        "position_name": "Python 工程师",
        "job_description": "负责 Flask 服务，要求 Python 和 MySQL",
        "extra_requirements": "准备系统设计",
        "level": "中级",
        "tech_tags": ["Python", "Flask"],
    }
    if resume_id is not None:
        payload["resume_id"] = resume_id
    return payload


def test_plan_creation_generates_preparation_when_resume_is_available():
    app = build_app()
    client = app.test_client()
    login(client)

    response = client.post("/api/interview-plans", json=plan_payload())
    payload = response.get_json()

    assert response.status_code == 201
    assert payload["plan"]["preparation_status"] == "draft"
    assert payload["preparation"]["intro_30"]
    assert payload["preparation"]["intro_60"]
    assert payload["preparation"]["intro_90"]
    assert payload["next_action"] == "review_preparation"


def test_plan_can_be_saved_without_resume_and_does_not_invent_preparation():
    app = build_app()
    client = app.test_client()
    login(client)

    response = client.post("/api/interview-plans", json=plan_payload(resume_id=None))
    payload = response.get_json()

    assert response.status_code == 201
    assert payload["plan"]["resume_id"] is None
    assert payload["plan"]["preparation_status"] == "pending"
    assert payload["preparation"] is None
    assert payload["next_action"] == "attach_resume"


def test_preparation_generation_explains_that_resume_must_be_attached_first():
    app = build_app()
    client = app.test_client()
    login(client)
    plan_id = client.post(
        "/api/interview-plans", json=plan_payload(resume_id=None)
    ).get_json()["plan"]["plan_id"]

    response = client.post(f"/api/interview-plans/{plan_id}/preparation")

    assert response.status_code == 400
    assert response.get_json()["message"] == "请先关联一份本人简历，再生成准备包"


def test_existing_plan_can_attach_owned_resume_before_generating_preparation():
    app = build_app()
    client = app.test_client()
    login(client)
    plan_id = client.post(
        "/api/interview-plans", json=plan_payload(resume_id=None)
    ).get_json()["plan"]["plan_id"]

    response = client.patch(
        f"/api/interview-plans/{plan_id}", json={"resume_id": 11}
    )

    assert response.status_code == 200
    assert response.get_json()["plan"]["resume_id"] == 11
    assert response.get_json()["plan"]["preparation_status"] == "outdated"


def test_remote_preparation_generation_returns_immediately_and_queues_job(monkeypatch):
    app = build_app()
    client = app.test_client()
    login(client)
    plan_id = client.post("/api/interview-plans", json=plan_payload(resume_id=None)).get_json()["plan"]["plan_id"]
    client.patch(f"/api/interview-plans/{plan_id}", json={"resume_id": 11})
    app.config.pop("PREPARATION_LLM_COMPLETE")
    queued = []
    monkeypatch.setattr(
        "routes.route_interview_plan._start_preparation_generation",
        lambda _app, queued_plan_id: queued.append(queued_plan_id),
    )

    response = client.post(f"/api/interview-plans/{plan_id}/preparation")

    assert response.status_code == 202
    assert response.get_json()["status"] == "generating"
    assert queued == [plan_id]
    with app.app_context():
        assert db.session.get(InterviewPlan, plan_id).preparation_status == "generating"


def test_plan_persists_when_automatic_preparation_generation_fails():
    app = build_app()
    app.config["PREPARATION_LLM_COMPLETE"] = lambda _messages: (_ for _ in ()).throw(RuntimeError("LLM unavailable"))
    client = app.test_client()
    login(client)

    response = client.post("/api/interview-plans", json=plan_payload())
    payload = response.get_json()

    assert response.status_code == 201
    assert payload["plan"]["preparation_status"] == "failed"
    assert payload["preparation"] is None
    assert payload["warning"]["code"] == "PREPARATION_GENERATION_FAILED"
    with app.app_context():
        assert InterviewPlan.query.count() == 1


def test_plan_crud_archives_instead_of_deleting():
    app = build_app()
    client = app.test_client()
    login(client)

    created = client.post("/api/interview-plans", json=plan_payload())
    plan_id = created.get_json()["plan"]["plan_id"]
    listed = client.get("/api/interview-plans")
    updated = client.patch(f"/api/interview-plans/{plan_id}", json={"position_name": "高级 Python 工程师"})
    archived = client.delete(f"/api/interview-plans/{plan_id}")

    assert created.status_code == 201
    assert listed.get_json()["plans"][0]["company_name"] == "星河科技"
    assert updated.get_json()["plan"]["position_name"] == "高级 Python 工程师"
    assert updated.get_json()["plan"]["preparation_status"] == "outdated"
    assert archived.get_json()["plan"]["status"] == "archived"


def test_plan_rejects_another_users_resume_and_resource_access():
    app = build_app()
    client = app.test_client()
    login(client)

    rejected = client.post("/api/interview-plans", json=plan_payload(resume_id=22))
    created = client.post("/api/interview-plans", json=plan_payload())
    plan_id = created.get_json()["plan"]["plan_id"]
    login(client, user_id=2)
    forbidden = client.get(f"/api/interview-plans/{plan_id}")

    assert rejected.status_code == 400
    assert forbidden.status_code == 404


def test_preparation_generation_versions_edit_and_confirm():
    app = build_app()
    client = app.test_client()
    login(client)
    plan_id = client.post("/api/interview-plans", json=plan_payload()).get_json()["plan"]["plan_id"]

    initial = client.get(f"/api/interview-plans/{plan_id}").get_json()["plan"]["preparations"][0]
    generated = client.post(f"/api/interview-plans/{plan_id}/preparation")
    pack = generated.get_json()["preparation"]
    edited = client.patch(
        f"/api/interview-plans/{plan_id}/preparation/{pack['pack_id']}",
        json={"intro_60": "我确认后的 60 秒自我介绍"},
    )
    confirmed = client.post(f"/api/interview-plans/{plan_id}/preparation/{pack['pack_id']}/confirm")
    regenerated = client.post(f"/api/interview-plans/{plan_id}/preparation")

    assert generated.status_code == 201
    assert initial["version"] == 1
    assert pack["version"] == 2
    assert edited.get_json()["preparation"]["intro_60"] == "我确认后的 60 秒自我介绍"
    assert confirmed.get_json()["preparation"]["status"] == "confirmed"
    assert regenerated.get_json()["preparation"]["version"] == 3


def test_preparation_section_can_be_regenerated_without_creating_new_pack():
    app = build_app()
    client = app.test_client()
    login(client)
    plan_id = client.post("/api/interview-plans", json=plan_payload()).get_json()["plan"]["plan_id"]
    pack = client.get(f"/api/interview-plans/{plan_id}").get_json()["plan"]["preparations"][0]

    response = client.post(
        f"/api/interview-plans/{plan_id}/preparation/{pack['pack_id']}/sections/intro_60/regenerate"
    )

    assert response.status_code == 200
    assert response.get_json()["field"] == "intro_60"
    assert response.get_json()["preparation"]["pack_id"] == pack["pack_id"]
    assert response.get_json()["preparation"]["version"] == pack["version"]


def test_preparation_section_regeneration_requests_only_the_selected_field():
    app = build_app()
    captured = []

    def section_llm(messages):
        captured.append(messages)
        return json.dumps({"intro_60": "按当前岗位重写的自我介绍"}, ensure_ascii=False)

    client = app.test_client()
    login(client)
    plan_id = client.post("/api/interview-plans", json=plan_payload()).get_json()["plan"]["plan_id"]
    pack = client.get(f"/api/interview-plans/{plan_id}").get_json()["plan"]["preparations"][0]
    app.config["PREPARATION_LLM_COMPLETE"] = section_llm

    response = client.post(
        f"/api/interview-plans/{plan_id}/preparation/{pack['pack_id']}/sections/intro_60/regenerate"
    )

    assert response.status_code == 200
    assert response.get_json()["preparation"]["intro_60"] == "按当前岗位重写的自我介绍"
    assert len(captured) == 1
    assert "intro_30" not in captured[0][1]["content"]
