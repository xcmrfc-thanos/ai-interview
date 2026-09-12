from . import db, utc_now


class CopilotSession(db.Model):
    __tablename__ = "copilot_sessions"

    session_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    user_id = db.Column(db.Integer, db.ForeignKey("users.user_id"), nullable=False, index=True)
    plan_id = db.Column(db.Integer, db.ForeignKey("interview_plans.plan_id"), nullable=False, index=True)
    resume_id = db.Column(db.Integer, db.ForeignKey("resumes.resume_id"), nullable=True)
    preparation_pack_id = db.Column(db.Integer, db.ForeignKey("preparation_packs.pack_id"), nullable=True)
    status = db.Column(db.String(24), nullable=False, default="created", index=True)
    last_client_sequence = db.Column(db.Integer, nullable=False, default=-1)
    current_turn_number = db.Column(db.Integer, nullable=False, default=0)
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)
    updated_at = db.Column(db.DateTime, nullable=False, default=utc_now, onupdate=utc_now)
    ended_at = db.Column(db.DateTime, nullable=True)

    turns = db.relationship(
        "CopilotTurn",
        back_populates="session",
        cascade="all, delete-orphan",
        order_by="CopilotTurn.turn_number",
    )
    events = db.relationship(
        "CopilotEvent",
        back_populates="session",
        cascade="all, delete-orphan",
        order_by="CopilotEvent.created_at",
    )
