from models import db
import datetime


class ResumeScores(db.Model):
    __tablename__ = 'resume_scores'

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)

    report_id=db.Column(db.Integer, db.ForeignKey('report.report_id'), nullable=False)
    total_score = db.Column(db.Numeric(5, 2), nullable=False, default=0.00)
    tech_match = db.Column(db.Numeric(5, 2), nullable=False, default=0.00)
    experience_match = db.Column(db.Numeric(5, 2), nullable=False, default=0.00)
    education_match = db.Column(db.Numeric(5, 2), nullable=False, default=0.00)
    potential_match = db.Column(db.Numeric(5, 2), nullable=False, default=0.00)
