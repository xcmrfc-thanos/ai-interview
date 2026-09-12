from . import db, utc_now


class CopilotEvent(db.Model):
    __tablename__ = "copilot_events"

    event_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    session_id = db.Column(db.Integer, db.ForeignKey("copilot_sessions.session_id"), nullable=False, index=True)
    event_type = db.Column(db.String(80), nullable=False, index=True)
    client_sequence = db.Column(db.Integer, nullable=True)
    latency_ms = db.Column(db.Integer, nullable=True)
    error_code = db.Column(db.String(80), nullable=True)
    payload = db.Column(db.JSON, nullable=False, default=dict)
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)

    session = db.relationship("CopilotSession", back_populates="events")
