from . import db
import datetime


class Company(db.Model):
    __tablename__ = 'companies'
    company_id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    user_id = db.Column(db.Integer, db.ForeignKey('users.user_id'), nullable=False)
    company_name = db.Column(db.String(100), nullable=False, unique=True)
    industry = db.Column(db.String(50), nullable=False)
    address = db.Column(db.String(255))
    scale = db.Column(db.Enum('1-50人', '51-100人', '101-500人', '500人以上'), nullable=False)
    website = db.Column(db.String(100))
    contact_phone = db.Column(db.String(20), nullable=False)
    logo_url = db.Column(db.String(255))
    description = db.Column(db.Text)
    created_at = db.Column(db.DateTime, default=datetime.datetime.now)
    updated_at = db.Column(db.DateTime, default=datetime.datetime.now, onupdate=datetime.datetime.now)