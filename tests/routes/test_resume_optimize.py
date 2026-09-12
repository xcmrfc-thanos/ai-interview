import json

from flask import Flask

from models import db, import_workspace_models
from models.Applicant import Applicant
from models.InterviewPlan import InterviewPlan
from models.Resume import Resume
from models.User import User
from routes.route_resume_optimize import resume_optimize_bp


def fake_llm(_messages):
    return json.dumps({
        "matches": ["Python 项目"],
        "gaps": ["缺少 Redis 实践证据"],
        "keywords": ["Python", "Redis"],
        "suggestions": [{
            "original_fact": "负责订单服务",
            "candidate_text": "负责 Python 订单服务",
            "rationale": "贴近 JD 关键词",
            "requires_confirmation": False,
        }],
    }, ensure_ascii=False)


def build_app():
    app = Flask(__name__)
    app.secret_key = "test"
    app.config.update(
        TESTING=True,
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
        RESUME_OPTIMIZE_LLM_COMPLETE=fake_llm,
    )
    db.init_app(app)
    import_workspace_models()
    app.register_blueprint(resume_optimize_bp, url_prefix="/api")
    with app.app_context():
        db.create_all()
        db.session.add_all([
            User(user_id=1, email="one@example.com", password="x", role="applicant"),
            User(user_id=2, email="two@example.com", password="x", role="applicant"),
        ])
        db.session.flush()
        applicant = Applicant(user_id=1, full_name="甲")
        db.session.add(applicant)
        db.session.flush()
        db.session.add(Resume(
            resume_id=11, applicant_id=applicant.applicant_id,
            file_url="resume.pdf", filename="resume.pdf",
            parsed_data={"projects": ["负责订单服务"], "skills": ["Python"]},
        ))
        db.session.add_all([
            InterviewPlan(
                plan_id=21, user_id=1, resume_id=11, company_name="星河科技",
                position_name="Python 工程师", job_description="需要 Python Redis",
            ),
            InterviewPlan(
                plan_id=22, user_id=2, company_name="其他公司",
                position_name="Go 工程师", job_description="需要 Go",
            ),
        ])
        db.session.commit()
    return app


def login(client, user_id=1):
    with client.session_transaction() as session:
        session["user_id"] = user_id
        session["role"] = "applicant"


def test_generate_read_latest_and_confirm_suggestion_without_mutating_resume():
    app = build_app()
    client = app.test_client()
    login(client)

    generated = client.post("/api/resume-optimizations", json={"plan_id": 21})
    optimization = generated.get_json()["optimization"]
    latest = client.get("/api/resume-optimizations/latest?plan_id=21")
    confirmed = client.post(
        f"/api/resume-optimizations/{optimization['optimization_id']}/suggestions/s1/confirm"
    )

    assert generated.status_code == 201
    assert latest.get_json()["optimization"]["optimization_id"] == optimization["optimization_id"]
    assert confirmed.get_json()["optimization"]["confirmed_suggestion_ids"] == ["s1"]
    with app.app_context():
        assert db.session.get(Resume, 11).parsed_data == {
            "projects": ["负责订单服务"], "skills": ["Python"]
        }


def test_optimization_enforces_plan_and_result_ownership():
    app = build_app()
    client = app.test_client()
    login(client)
    assert client.post("/api/resume-optimizations", json={"plan_id": 22}).status_code == 404
    optimization_id = client.post(
        "/api/resume-optimizations", json={"plan_id": 21}
    ).get_json()["optimization"]["optimization_id"]
    login(client, user_id=2)
    assert client.get("/api/resume-optimizations/latest?plan_id=21").status_code == 404
    assert client.post(
        f"/api/resume-optimizations/{optimization_id}/suggestions/s1/confirm"
    ).status_code == 404
