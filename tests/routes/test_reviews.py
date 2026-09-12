from flask import Flask

from models import db, import_workspace_models
from models.CopilotSession import CopilotSession
from models.CopilotTurn import CopilotTurn
from models.InterviewPlan import InterviewPlan
from models.MockInterview import MockInterview
from models.MockInterviewTurn import MockInterviewTurn
from models.User import User
from routes.route_review import review_bp


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
    app.register_blueprint(review_bp, url_prefix="/api")
    with app.app_context():
        db.create_all()
        db.session.add_all([
            User(user_id=1, email="one@example.com", password="x", role="applicant"),
            User(user_id=2, email="two@example.com", password="x", role="applicant"),
        ])
        db.session.add_all([
            InterviewPlan(
                plan_id=21, user_id=1, company_name="星河科技",
                position_name="Python 工程师", job_description="负责 Python 服务",
            ),
            InterviewPlan(
                plan_id=22, user_id=2, company_name="其他公司",
                position_name="Go 工程师", job_description="负责 Go 服务",
            ),
        ])
        db.session.flush()
        copilot = CopilotSession(session_id=31, user_id=1, plan_id=21, status="ended")
        mock = MockInterview(
            mock_interview_id=41, user_id=1, plan_id=21, status="completed",
            current_turn_number=1, question_outline=[{"category": "technical", "question": "解释 Flask"}],
        )
        db.session.add_all([copilot, mock])
        db.session.flush()
        db.session.add(CopilotTurn(
            session_id=31, turn_number=1, transcript="请介绍项目",
            answer_points=["背景"], reference_answer="回答", status="completed",
        ))
        db.session.add(MockInterviewTurn(
            mock_interview_id=41, turn_number=1, question="解释 Flask", answer="回答",
            scores={"job_relevance": 80}, improvements=["补充原理"], status="answered",
        ))
        db.session.commit()
    return app


def login(client, user_id=1):
    with client.session_transaction() as session:
        session["user_id"] = user_id
        session["role"] = "applicant"


def test_generate_is_idempotent_for_both_sources_and_supports_filters():
    app = build_app()
    client = app.test_client()
    login(client)

    first = client.post("/api/reviews/generate", json={"source_type": "copilot", "source_id": 31})
    duplicate = client.post("/api/reviews/generate", json={"source_type": "copilot", "source_id": 31})
    mock = client.post("/api/reviews/generate", json={"source_type": "mock", "source_id": 41})
    filtered = client.get("/api/reviews?plan_id=21&source_type=mock")

    assert first.status_code == 201
    assert duplicate.status_code == 200
    assert duplicate.get_json()["review"]["review_id"] == first.get_json()["review"]["review_id"]
    assert mock.status_code == 201
    assert len(filtered.get_json()["reviews"]) == 1
    assert filtered.get_json()["reviews"][0]["source_type"] == "mock"


def test_review_detail_export_and_ownership():
    app = build_app()
    client = app.test_client()
    login(client)
    review_id = client.post(
        "/api/reviews/generate", json={"source_type": "copilot", "source_id": 31}
    ).get_json()["review"]["review_id"]

    detail = client.get(f"/api/reviews/{review_id}")
    text_export = client.get(f"/api/reviews/{review_id}/export?format=text")
    json_export = client.get(f"/api/reviews/{review_id}/export?format=json")

    assert detail.status_code == 200
    assert text_export.mimetype == "text/plain"
    assert "原始音频" not in text_export.get_data(as_text=True)
    assert json_export.is_json
    login(client, user_id=2)
    assert client.get(f"/api/reviews/{review_id}").status_code == 404
    assert client.post("/api/reviews/generate", json={"source_type": "copilot", "source_id": 31}).status_code == 404
