from . import db
import datetime
from models.Company import Company  # 添加导入

class Job(db.Model):
    __tablename__ = 'jobs'
    job_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    company_id = db.Column(db.Integer, db.ForeignKey('companies.company_id'), nullable=False)
    title = db.Column(db.String(100), nullable=False)
    job_type = db.Column(db.Enum('全职', '兼职', '实习'), nullable=False)
    description = db.Column(db.Text, nullable=False)
    requirements = db.Column(db.Text, nullable=False)
    min_salary = db.Column(db.Integer)
    max_salary = db.Column(db.Integer)
    location = db.Column(db.String(100))
    is_active = db.Column(db.Boolean, default=True)
    post_date = db.Column(db.Date, nullable=False)
    expiration_date = db.Column(db.Date)
    created_at = db.Column(db.DateTime, default=datetime.datetime.now)
    updated_at = db.Column(db.DateTime, default=datetime.datetime.now, onupdate=datetime.datetime.now)

    company = db.relationship('Company', backref=db.backref('jobs', lazy=True))  # 添加关系字段