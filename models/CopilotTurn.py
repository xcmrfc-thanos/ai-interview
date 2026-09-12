from . import db, utc_now


class CopilotTurn(db.Model):
    __tablename__ = "copilot_turns"
    __table_args__ = (
        db.UniqueConstraint("session_id", "turn_number", name="uq_copilot_session_turn"),
    )

    turn_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    session_id = db.Column(db.Integer, db.ForeignKey("copilot_sessions.session_id"), nullable=False, index=True)
    turn_number = db.Column(db.Integer, nullable=False)
    status = db.Column(db.String(24), nullable=False, default="recording")
    partial_transcript = db.Column(db.Text, nullable=True)
    transcript = db.Column(db.Text, nullable=True)
    answer_points = db.Column(db.JSON, nullable=False, default=list)
    reference_answer = db.Column(db.Text, nullable=True)
    follow_up = db.Column(db.Text, nullable=True)
    knowledge_item_ids = db.Column(db.JSON, nullable=False, default=list)
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)
    updated_at = db.Column(db.DateTime, nullable=False, default=utc_now, onupdate=utc_now)

    session = db.relationship("CopilotSession", back_populates="turns")
