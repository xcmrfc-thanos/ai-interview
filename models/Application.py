import datetime
from . import db

class Application(db.Model):
    __tablename__ = 'applications'
    application_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    job_id = db.Column(db.Integer, db.ForeignKey('jobs.job_id'), nullable=False)
    applicant_id = db.Column(db.Integer, db.ForeignKey('applicants.applicant_id'), nullable=False)
    resume_id = db.Column(db.Integer, db.ForeignKey('resumes.resume_id'))  # 新增字段
    apply_time = db.Column(db.DateTime, nullable=False, default=datetime.datetime.now)  # 修正默认值
    status = db.Column(db.Enum('已投递', '已查看', '面试中', '待定', '录用', '拒绝'), default='已投递')
    ai_evaluation_score = db.Column(db.Float, comment='AI综合评分')  # 添加注释
    feedback = db.Column(db.Text, comment='企业反馈')  # 添加注释
    created_at = db.Column(db.DateTime, default=datetime.datetime.now)
    updated_at = db.Column(db.DateTime, default=datetime.datetime.now, onupdate=datetime.datetime.now)
