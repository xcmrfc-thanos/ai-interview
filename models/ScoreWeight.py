from models import db
from datetime import datetime
from sqlalchemy import DECIMAL


class ScoreWeight(db.Model):
    __tablename__ = 'score_weights'
    weight_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    job_id = db.Column(db.Integer, db.ForeignKey('jobs.job_id', ondelete='CASCADE', onupdate='CASCADE'), nullable=False, unique=True)
    technical_weight = db.Column(db.Numeric(precision=5, scale=2), nullable=False, default=0.40)
    learning_weight = db.Column(db.Numeric(precision=5, scale=2), nullable=False, default=0.20)
    team_weight = db.Column(db.Numeric(precision=5, scale=2), nullable=False, default=0.15)
    problem_solving_weight = db.Column(db.Numeric(precision=5, scale=2), nullable=False, default=0.15)
    communication_weight = db.Column(db.Numeric(precision=5, scale=2), nullable=False, default=0.10)
    created_at = db.Column(db.TIMESTAMP, default=datetime.now)
    updated_at = db.Column(db.TIMESTAMP, default=datetime.now, onupdate=datetime.now)


