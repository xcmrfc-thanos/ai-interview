from datetime import datetime

from flask import Flask

from models import db, import_workspace_models
from models.CopilotEvent import CopilotEvent
from models.CopilotSession import CopilotSession
from models.CopilotTurn import CopilotTurn
from models.InterviewPlan import InterviewPlan
from models.KnowledgeItem import KnowledgeItem
from models.MockInterview import MockInterview
from models.MockInterviewTurn import MockInterviewTurn
from models.PreparationPack import PreparationPack
from models.Review import Review


def build_app():
    app = Flask(__name__)
    app.config.update(
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
    )
    db.init_app(app)
    import_workspace_models()
    return app


def test_personal_workspace_models_preserve_ownership_versions_and_turn_order():
    app = build_app()
    with app.app_context():
        db.create_all()
        plan = InterviewPlan(
            user_id=7,
            company_name="示例科技",
            position_name="Python 工程师",
            job_description="负责 Flask 服务与 MySQL",
            tech_tags=["Python", "Flask"],
        )
        db.session.add(plan)
        db.session.flush()
        pack = PreparationPack(
            plan_id=plan.plan_id,
            version=1,
            source_fingerprint="fingerprint-v1",
            intro_30="30 秒介绍",
            intro_60="60 秒介绍",
            intro_90="90 秒介绍",
        )
        session = CopilotSession(
            user_id=7,
            plan_id=plan.plan_id,
            preparation_pack_id=None,
            status="created",
        )
        mock = MockInterview(user_id=7, plan_id=plan.plan_id, status="created")
        db.session.add_all([pack, session, mock])
        db.session.flush()
        session.preparation_pack_id = pack.pack_id
        turn = CopilotTurn(
            session_id=session.session_id,
            turn_number=1,
            transcript="请介绍一下你自己",
            status="ready",
        )
        mock_turn = MockInterviewTurn(
            mock_interview_id=mock.mock_interview_id,
            turn_number=1,
            question="介绍一个项目",
        )
        event = CopilotEvent(
            session_id=session.session_id,
            event_type="asr_first_token",
            latency_ms=480,
        )
        review = Review(
            user_id=7,
            plan_id=plan.plan_id,
            source_type="copilot",
            source_id=session.session_id,
            summary="整体表达清晰",
        )
        db.session.add_all([turn, mock_turn, event, review])
        db.session.commit()

        assert plan.user_id == 7
        assert plan.tech_tags == ["Python", "Flask"]
        assert plan.preparation_packs[0].version == 1
        assert session.turns[0].turn_number == 1
        assert mock.turns[0].question == "介绍一个项目"
        assert review.source_id == session.session_id
        assert isinstance(plan.created_at, datetime)


def test_json_defaults_are_not_shared_and_knowledge_hash_is_unique_per_user():
    app = build_app()
    with app.app_context():
        db.create_all()
        first = InterviewPlan(
            user_id=1,
            company_name="A",
            position_name="后端",
            job_description="Python",
        )
        second = InterviewPlan(
            user_id=1,
            company_name="B",
            position_name="平台",
            job_description="Go",
        )
        db.session.add_all([first, second])
        db.session.flush()
        first.tech_tags.append("Python")
        assert second.tech_tags == []

        db.session.add_all(
            [
                KnowledgeItem(
                    user_id=1,
                    title="缓存击穿",
                    question="什么是缓存击穿？",
                    content_hash="same-hash",
                ),
                KnowledgeItem(
                    user_id=2,
                    title="缓存击穿",
                    question="什么是缓存击穿？",
                    content_hash="same-hash",
                ),
            ]
        )
        db.session.commit()

        assert KnowledgeItem.query.count() == 2
