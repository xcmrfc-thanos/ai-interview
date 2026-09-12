import json

from flask import Flask

from models import db, import_workspace_models
from models.Applicant import Applicant
from models.InterviewPlan import InterviewPlan
from models.Resume import Resume
from models.User import User
from routes.route_mock_interview import mock_interview_bp


def fake_llm(messages):
    prompt = messages[-1]["content"]
    if "question_outline" in prompt:
        return json.dumps({"questions": [
            {"category": "introduction", "question": "请做 60 秒自我介绍", "reference_points": ["岗位匹配"]},
            {"category": "technical", "question": "解释 Flask 上下文", "reference_points": ["原理"]},
        ]}, ensure_ascii=False)
    return json.dumps({
        "reference_points": ["背景", "行动", "结果"],
        "scores": {"fact_consistency": 90, "job_relevance": 85, "completeness": 80, "expression": 88},
        "strengths": ["表达清晰"],
        "improvements": ["补充取舍"],
    }, ensure_ascii=False)


def build_app():
    app = Flask(__name__)
    app.secret_key = "test"
    app.config.update(
        TESTING=True,
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
        MOCK_INTERVIEW_LLM_COMPLETE=fake_llm,
    )
    db.init_app(app)
    import_workspace_models()
    app.register_blueprint(mock_interview_bp, url_prefix="/api")
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
            resume_id=11,
            applicant_id=applicant.applicant_id,
            file_url="resume.pdf",
            filename="resume.pdf",
            parsed_data={"skills": ["Python"], "projects": ["订单服务"]},
        ))
        db.session.add_all([
            InterviewPlan(
                plan_id=21, user_id=1, resume_id=11, company_name="星河科技",
                position_name="Python 工程师", job_description="负责 Flask 服务",
            ),
            InterviewPlan(
                plan_id=22, user_id=2, company_name="其他公司",
                position_name="Go 工程师", job_description="负责 Go 服务",
            ),
        ])
        db.session.commit()
    return app


def login(client, user_id=1):
    with client.session_transaction() as session:
        session["user_id"] = user_id
        session["role"] = "applicant"


def test_mock_interview_create_answer_next_restore_and_end():
    app = build_app()
    client = app.test_client()
    login(client)

    created = client.post("/api/mock-interviews", json={"plan_id": 21})
    interview_id = created.get_json()["interview"]["mock_interview_id"]
    answered = client.post(f"/api/mock-interviews/{interview_id}/answers", json={"answer": "我有 Python 项目经验"})
    restored = client.get(f"/api/mock-interviews/{interview_id}")
    ended = client.post(f"/api/mock-interviews/{interview_id}/end")

    assert created.status_code == 201
    assert created.get_json()["interview"]["current_question"]["turn_number"] == 1
    assert answered.get_json()["evaluation"]["scores"]["job_relevance"] == 85
    assert answered.get_json()["interview"]["current_question"]["turn_number"] == 2
    assert len(restored.get_json()["interview"]["turns"]) == 2
    assert ended.get_json()["interview"]["status"] == "ended"


def test_mock_interview_rejects_other_user_and_empty_answer():
    app = build_app()
    client = app.test_client()
    login(client)
    assert client.post("/api/mock-interviews", json={"plan_id": 22}).status_code == 404
    interview_id = client.post("/api/mock-interviews", json={"plan_id": 21}).get_json()["interview"]["mock_interview_id"]
    assert client.post(f"/api/mock-interviews/{interview_id}/answers", json={"answer": ""}).status_code == 400
    login(client, user_id=2)
    assert client.get(f"/api/mock-interviews/{interview_id}").status_code == 404
