from . import db, utc_now


class Review(db.Model):
    __tablename__ = "workspace_reviews"
    __table_args__ = (
        db.UniqueConstraint("user_id", "source_type", "source_id", name="uq_review_source"),
    )

    review_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    user_id = db.Column(db.Integer, db.ForeignKey("users.user_id"), nullable=False, index=True)
    plan_id = db.Column(db.Integer, db.ForeignKey("interview_plans.plan_id"), nullable=False, index=True)
    source_type = db.Column(db.String(24), nullable=False, index=True)
    source_id = db.Column(db.Integer, nullable=False, index=True)
    summary = db.Column(db.Text, nullable=False, default="")
    question_categories = db.Column(db.JSON, nullable=False, default=list)
    scores = db.Column(db.JSON, nullable=False, default=dict)
    fact_risks = db.Column(db.JSON, nullable=False, default=list)
    expression_issues = db.Column(db.JSON, nullable=False, default=list)
    weak_topics = db.Column(db.JSON, nullable=False, default=list)
    next_actions = db.Column(db.JSON, nullable=False, default=list)
    diagnostics = db.Column(db.JSON, nullable=False, default=dict)
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)
    updated_at = db.Column(db.DateTime, nullable=False, default=utc_now, onupdate=utc_now)
