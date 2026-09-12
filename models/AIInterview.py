from models import db
from datetime import datetime

class AIInterview(db.Model):
    __tablename__ = 'ai_interviews'
    
    interview_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    application_id = db.Column(db.Integer, db.ForeignKey('applications.application_id'), nullable=False)
    start_time = db.Column(db.DateTime, nullable=False)
    end_time = db.Column(db.DateTime, nullable=True)
    history = db.Column(db.Text, nullable=True)
    evaluation = db.Column(db.JSON, nullable=True)
    overall_score = db.Column(db.Float, nullable=True)
    created_at = db.Column(db.DateTime, nullable=True, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, nullable=True, default=datetime.utcnow, onupdate=datetime.utcnow)

    # 关联
    application = db.relationship('Application', backref=db.backref('interviews', lazy=True))