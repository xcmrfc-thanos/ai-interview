import datetime
from . import db
class InterviewQuestion(db.Model):
    __tablename__ = 'interview_questions'
    question_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    company_id = db.Column(db.Integer, db.ForeignKey('companies.company_id'), nullable=False)
    question_type = db.Column(db.Enum('技术', '行为', '情景模拟', '通用'))
    content = db.Column(db.Text, nullable=False)
    reference_answer = db.Column(db.Text)
    difficulty_level = db.Column(db.Enum('简单', '中等', '困难'))
    created_at = db.Column(db.DateTime, default=datetime.datetime.now)
    updated_at = db.Column(db.DateTime, default=datetime.datetime.now, onupdate=datetime.datetime.now)