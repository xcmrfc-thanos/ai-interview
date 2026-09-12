from . import db, utc_now


class ResumeOptimization(db.Model):
    __tablename__ = "resume_optimizations"

    optimization_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    user_id = db.Column(db.Integer, db.ForeignKey("users.user_id"), nullable=False, index=True)
    plan_id = db.Column(db.Integer, db.ForeignKey("interview_plans.plan_id"), nullable=False, index=True)
    resume_id = db.Column(db.Integer, db.ForeignKey("resumes.resume_id"), nullable=False, index=True)
    status = db.Column(db.String(24), nullable=False, default="draft")
    result = db.Column(db.JSON, nullable=False, default=dict)
    confirmed_suggestion_ids = db.Column(db.JSON, nullable=False, default=list)
    created_at = db.Column(db.DateTime, nullable=False, default=utc_now)
    updated_at = db.Column(db.DateTime, nullable=False, default=utc_now, onupdate=utc_now)
