from . import db, utc_now


class KnowledgeItem(db.Model):
    __tablename__ = "knowledge_items"
    __table_args__ = (
        db.UniqueConstraint("user_id", "content_hash", name="uq_knowledge_user_hash"),
    )

    item_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    user_id = db.Column(db.Integer, db.ForeignKey("users.user_id"), nullable=False, index=True)
    title = db.Column(db.String(200), nullable=False)
    question = db.Column(db.Text, nullable=False)
    category = db.Column(db.String(80), nullable=True, index=True)
    role_tags = db.Column(db.JSON, nullable=False, default=list)
    tech_tags = db.Column(db.JSON, nullable=False, default=list)
    difficulty = db.Column(db.String(32), nullable=True, index=True)
    core_conclusion = db.Column(db.Text, nullable=True)
    answer_points = db.Column(db.JSON, nullable=False, default=list)
    standard_answer = db.Column(db.Text, nullable=True)
    follow_ups = db.Column(db.JSON, nullable=False, default=list)
    pitfalls = db.Column(db.JSON, nullable=False, default=list)
    source = db.Column(db.String(240), nullable=True)
    source_url = db.Column(db.String(500), nullable=True)
    content_hash = db.Column(db.String(64), nullable=False)
    is_enabled = db.Column(db.Boolean, nullable=False, default=True, index=True)
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)
    updated_at = db.Column(db.DateTime, nullable=False, default=utc_now, onupdate=utc_now)
