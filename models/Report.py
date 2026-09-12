from models import db
from datetime import datetime

class Report(db.Model):
    __tablename__ = 'report'
    report_id = db.Column(
        db.Integer,
        primary_key=True,
        autoincrement=True,
        comment='自增主键'
    )
    report = db.Column(
        db.Text,
        nullable=True,
        comment='报告内容'
    )
    resume_id = db.Column(
        db.Integer,
        db.ForeignKey('resumes.resume_id', ondelete='RESTRICT', onupdate='RESTRICT'),
        nullable=False,
        comment='外键关联简历'
    )
    job_id = db.Column(
        db.Integer,
        db.ForeignKey('jobs.job_id', ondelete='RESTRICT', onupdate='RESTRICT'),
        nullable=False,
        comment='外键关联职位'
    )