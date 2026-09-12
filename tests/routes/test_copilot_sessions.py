from flask import Flask

from models import db, import_workspace_models
from models.Applicant import Applicant
from models.InterviewPlan import InterviewPlan
from models.PreparationPack import PreparationPack
from models.Resume import Resume
from models.User import User
from routes.route_copilot import copilot_bp


def build_app():
    app = Flask(__name__)
    app.secret_key = "test"
    app.config.update(
        TESTING=True,
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
    )
    db.init_app(app)
    import_workspace_models()
    app.register_blueprint(copilot_bp, url_prefix="/api")
    with app.app_context():
        db.create_all()
        first = User(user_id=1, email="first@example.com", password="x", role="applicant")
        second = User(user_id=2, email="second@example.com", password="x", role="applicant")
        db.session.add_all([first, second])
        db.session.flush()
        applicant = Applicant(user_id=1, full_name="甲")
        db.session.add(applicant)
        db.session.flush()
        resume = Resume(
            resume_id=11,
            applicant_id=applicant.applicant_id,
            file_url="resume.pdf",
            filename="resume.pdf",
            parsed_data={"skills": ["Python"], "projects": ["订单服务"]},
        )
        db.session.add(resume)
        db.session.flush()
        plan = InterviewPlan(
            plan_id=21,
            user_id=1,
            resume_id=resume.resume_id,
            company_name="星河科技",
            position_name="Python 工程师",
            job_description="负责 Flask 服务",
            status="active",
            preparation_status="confirmed",
        )
        other_plan = InterviewPlan(
            plan_id=22,
            user_id=2,
            company_name="其他公司",
            position_name="Go 工程师",
            job_description="负责 Go 服务",
        )
        db.session.add_all([plan, other_plan])
        db.session.flush()
        db.session.add(PreparationPack(
            plan_id=plan.plan_id,
            version=1,
            source_fingerprint="a" * 64,
            status="confirmed",
            intro_60="我是 Python 工程师",
        ))
        db.session.commit()
    return app


def login(client, user_id=1):
    with client.session_transaction() as flask_session:
        flask_session["user_id"] = user_id
        flask_session["role"] = "applicant"


def test_session_lifecycle_is_owned_and_idempotent():
    app = build_app()
    client = app.test_client()
    login(client)

    created = client.post("/api/copilot/sessions", json={"plan_id": 21})
    session_id = created.get_json()["session"]["session_id"]
    paused = client.post(f"/api/copilot/sessions/{session_id}/pause")
    paused_again = client.post(f"/api/copilot/sessions/{session_id}/pause")
    resumed = client.post(f"/api/copilot/sessions/{session_id}/resume")
    ended = client.post(f"/api/copilot/sessions/{session_id}/end")
    ended_again = client.post(f"/api/copilot/sessions/{session_id}/end")
    restored = client.get(f"/api/copilot/sessions/{session_id}")

    assert created.status_code == 201
    assert created.get_json()["session"]["preparation_pack_id"] is not None
    assert paused.get_json()["session"]["status"] == "paused"
    assert paused_again.get_json()["session"]["status"] == "paused"
    assert resumed.get_json()["session"]["status"] == "running"
    assert ended.get_json()["session"]["status"] == "ended"
    assert ended_again.get_json()["session"]["status"] == "ended"
    assert restored.get_json()["session"]["status"] == "ended"

    login(client, user_id=2)
    assert client.get(f"/api/copilot/sessions/{session_id}").status_code == 404
    assert client.post("/api/copilot/sessions", json={"plan_id": 21}).status_code == 404


def test_session_creation_reports_outdated_preparation_without_blocking():
    app = build_app()
    with app.app_context():
        plan = db.session.get(InterviewPlan, 21)
        plan.preparation_status = "outdated"
        plan.preparation_packs[0].status = "outdated"
        db.session.commit()
    client = app.test_client()
    login(client)

    response = client.post("/api/copilot/sessions", json={"plan_id": 21})

    assert response.status_code == 201
    assert response.get_json()["warning"]["code"] == "PREPARATION_OUTDATED"


def test_session_creation_requires_login_and_active_plan_with_resume():
    app = build_app()
    client = app.test_client()

    assert client.post("/api/copilot/sessions", json={"plan_id": 21}).status_code == 401
    login(client)
    assert client.post("/api/copilot/sessions", json={}).status_code == 400
