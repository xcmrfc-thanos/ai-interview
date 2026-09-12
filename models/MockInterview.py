from . import db, utc_now


class MockInterview(db.Model):
    __tablename__ = "mock_interviews"

    mock_interview_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    user_id = db.Column(db.Integer, db.ForeignKey("users.user_id"), nullable=False, index=True)
    plan_id = db.Column(db.Integer, db.ForeignKey("interview_plans.plan_id"), nullable=False, index=True)
    status = db.Column(db.String(24), nullable=False, default="created", index=True)
    current_turn_number = db.Column(db.Integer, nullable=False, default=0)
    question_outline = db.Column(db.JSON, nullable=False, default=list)
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)
    updated_at = db.Column(db.DateTime, nullable=False, default=utc_now, onupdate=utc_now)
    ended_at = db.Column(db.DateTime, nullable=True)

    turns = db.relationship(
        "MockInterviewTurn",
        back_populates="interview",
        cascade="all, delete-orphan",
        order_by="MockInterviewTurn.turn_number",
    )
