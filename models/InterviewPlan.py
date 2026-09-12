from . import db, utc_now


class InterviewPlan(db.Model):
    __tablename__ = "interview_plans"

    plan_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    user_id = db.Column(db.Integer, db.ForeignKey("users.user_id"), nullable=False, index=True)
    resume_id = db.Column(db.Integer, db.ForeignKey("resumes.resume_id"), nullable=True, index=True)
    company_name = db.Column(db.String(120), nullable=False)
    position_name = db.Column(db.String(120), nullable=False)
    job_description = db.Column(db.Text, nullable=False)
    extra_requirements = db.Column(db.Text, nullable=True)
    level = db.Column(db.String(40), nullable=True)
    tech_tags = db.Column(db.JSON, nullable=False, default=list)
    status = db.Column(db.String(24), nullable=False, default="active", index=True)
    preparation_status = db.Column(db.String(24), nullable=False, default="pending")
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)
    updated_at = db.Column(db.DateTime, nullable=False, default=utc_now, onupdate=utc_now)
    last_used_at = db.Column(db.DateTime, nullable=True)

    preparation_packs = db.relationship(
        "PreparationPack",
        back_populates="plan",
        cascade="all, delete-orphan",
        order_by="PreparationPack.version",
    )
