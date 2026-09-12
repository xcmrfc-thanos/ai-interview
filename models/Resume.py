import datetime
from . import db

# class Resume(db.Model):
#     __tablename__ = 'resumes'
#     resume_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
#     user_id = db.Column(db.Integer, db.ForeignKey('users.user_id'), nullable=False)
#     file_url = db.Column(db.String(255), nullable=False)  # 修改为file_url
#     filename = db.Column(db.String(255), nullable=False)
#     upload_date = db.Column(db.DateTime, default=datetime.datetime.now)
#     parsed_data = db.Column(db.JSON)
#     report = db.Column(db.Text)  # 修改为 TEXT 类型

class Resume(db.Model):
    __tablename__ = 'resumes'
    resume_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    applicant_id = db.Column(db.Integer, db.ForeignKey('applicants.applicant_id'), nullable=False, index=True)
    file_url = db.Column(db.String(255), nullable=False)  # 修改为file_url
    filename = db.Column(db.String(255), nullable=False)
    upload_date = db.Column(db.DateTime, default=datetime.datetime.now)
    parsed_data = db.Column(db.JSON)

