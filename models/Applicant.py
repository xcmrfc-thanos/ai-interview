

from . import db
import datetime
class Applicant(db.Model):
    __tablename__ = 'applicants'
    applicant_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    user_id = db.Column(db.Integer, db.ForeignKey('users.user_id'), nullable=False, unique=True)
    full_name = db.Column(db.String(50), nullable=False)
    phone = db.Column(db.String(20))
    resume_url = db.Column(db.String(255))
    gender = db.Column(db.Enum('male', 'female', 'other'))
    birthdate = db.Column(db.Date)
    education_level = db.Column(db.Enum('高中', '大专', '本科', '硕士', '博士'))
    work_years = db.Column(db.Integer, default=0)
    expected_position = db.Column(db.String(50))
    expected_salary = db.Column(db.Integer)
    created_at = db.Column(db.DateTime, default=datetime.datetime.now)
    updated_at = db.Column(db.DateTime, default=datetime.datetime.now, onupdate=datetime.datetime.now)