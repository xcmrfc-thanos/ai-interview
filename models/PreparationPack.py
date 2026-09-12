from . import db, utc_now


class PreparationPack(db.Model):
    __tablename__ = "preparation_packs"
    __table_args__ = (db.UniqueConstraint("plan_id", "version", name="uq_pack_plan_version"),)

    pack_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    plan_id = db.Column(db.Integer, db.ForeignKey("interview_plans.plan_id"), nullable=False, index=True)
    version = db.Column(db.Integer, nullable=False)
    source_fingerprint = db.Column(db.String(64), nullable=False, index=True)
    status = db.Column(db.String(24), nullable=False, default="draft")
    intro_30 = db.Column(db.Text, nullable=False, default="")
    intro_60 = db.Column(db.Text, nullable=False, default="")
    intro_90 = db.Column(db.Text, nullable=False, default="")
    highlights = db.Column(db.JSON, nullable=False, default=list)
    project_followups = db.Column(db.JSON, nullable=False, default=list)
    risk_points = db.Column(db.JSON, nullable=False, default=list)
    frequent_questions = db.Column(db.JSON, nullable=False, default=list)
    star_stories = db.Column(db.JSON, nullable=False, default=list)
    review_topics = db.Column(db.JSON, nullable=False, default=list)
    source_evidence = db.Column(db.JSON, nullable=False, default=list)
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)
    updated_at = db.Column(db.DateTime, nullable=False, default=utc_now, onupdate=utc_now)
    confirmed_at = db.Column(db.DateTime, nullable=True)

    plan = db.relationship("InterviewPlan", back_populates="preparation_packs")
