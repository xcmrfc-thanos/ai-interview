from . import db, utc_now


class MockInterviewTurn(db.Model):
    __tablename__ = "mock_interview_turns"
    __table_args__ = (
        db.UniqueConstraint("mock_interview_id", "turn_number", name="uq_mock_interview_turn"),
    )

    turn_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    mock_interview_id = db.Column(db.Integer, db.ForeignKey("mock_interviews.mock_interview_id"), nullable=False, index=True)
    turn_number = db.Column(db.Integer, nullable=False)
    question = db.Column(db.Text, nullable=False)
    answer = db.Column(db.Text, nullable=True)
    reference_points = db.Column(db.JSON, nullable=False, default=list)
    scores = db.Column(db.JSON, nullable=False, default=dict)
    strengths = db.Column(db.JSON, nullable=False, default=list)
    improvements = db.Column(db.JSON, nullable=False, default=list)
    status = db.Column(db.String(24), nullable=False, default="asked")
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)
    answered_at = db.Column(db.DateTime, nullable=True)

    interview = db.relationship("MockInterview", back_populates="turns")
