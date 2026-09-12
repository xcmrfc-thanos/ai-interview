from . import db
import datetime


class InterviewScores(db.Model):
    __tablename__ = 'interview_scores'

    score_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    interview_id = db.Column(db.Integer, db.ForeignKey('ai_interviews.interview_id'), nullable=False)
    technical_ability = db.Column(db.Float, nullable=False)
    learning_ability = db.Column(db.Float, nullable=False)
    team_collaboration = db.Column(db.Float, nullable=False)
    problem_solving = db.Column(db.Float, nullable=False)
    communication_expression = db.Column(db.Float, nullable=False)
    created_at = db.Column(db.TIMESTAMP, default=lambda: datetime.datetime.now())
    updated_at = db.Column(db.TIMESTAMP, default=lambda: datetime.datetime.now(),
                        onupdate=lambda: datetime.datetime.now())
