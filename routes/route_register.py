from datetime import date

from flask import Blueprint, request, redirect, url_for, flash, jsonify

from models.Applicant import Applicant
from models.Company import Company
from models.User import *  # 确保从models导入db实例和模型类
from utils.auth_security import hash_password

# 创建子蓝图
register_bp = Blueprint('register', __name__)

@register_bp.route('/register', methods=[ 'POST'])
def register():
    payload = request.get_json(silent=True) or request.form
    wants_json = request.is_json
    email = (payload.get('email') or '').strip()
    password = payload.get('password') or ''
    confirm_password = payload.get('confirm_password') or ''
    role = payload.get('role') or 'applicant'

    if password != confirm_password:
        if wants_json:
            return jsonify({'success': False, 'message': '两次输入的密码不一致'}), 400
        flash('密码不一致，请重新输入。')
        return redirect(url_for('register'))

    if User.query.filter_by(email=email).first():
        if wants_json:
            return jsonify({'success': False, 'message': '该邮箱已被注册，请使用其他邮箱'}), 409
        flash('该邮箱已被注册，请使用其他邮箱。')
        return redirect(url_for('register'))

    if role == 'company':
        company_name = request.form['company_name']
        industry = request.form['industry']
        address = request.form['address']
        scale = request.form['scale']
        website = request.form['website']
        contact_phone = request.form['contact_phone']
        description = request.form['description']

        new_user = User(email=email, password=hash_password(password), role=role)
        db.session.add(new_user)
        db.session.flush()
        new_company = Company(
            company_name=company_name,
            industry=industry,
            address=address,
            scale=scale,
            website=website,
            contact_phone=contact_phone,
            description=description,
            user_id=new_user.user_id
        )
        db.session.add(new_company)

    elif role == 'applicant':
        full_name = payload.get('full_name') or ''
        # Enum 列缺省必须写 NULL 而非空串（空串会导致行加载时 KeyError）
        gender = (payload.get('gender') or '').strip() or None
        birthdate_value = str(payload.get('birthdate') or '').strip()
        education_level = (payload.get('education_level') or '').strip() or None
        work_years = payload.get('work_years') or ''
        expected_position = payload.get('expected_position') or ''
        expected_salary = payload.get('expected_salary') or ''

        try:
            birthdate = date.fromisoformat(birthdate_value) if birthdate_value else None
        except ValueError:
            if wants_json:
                return jsonify({'success': False, 'message': '出生日期格式无效，请使用 YYYY-MM-DD'}), 400
            flash('出生日期格式无效，请使用 YYYY-MM-DD。')
            return redirect(url_for('register'))

        new_user = User(email=email, password=hash_password(password), role=role)
        db.session.add(new_user)
        db.session.flush()
        new_applicant = Applicant(
            full_name=full_name,
            gender=gender,
            birthdate=birthdate,
            education_level=education_level,
            work_years=work_years,
            expected_position=expected_position,
            expected_salary=expected_salary,
            user_id=new_user.user_id
        )
        db.session.add(new_applicant)

    else:
        if wants_json:
            return jsonify({'success': False, 'message': '无效的角色'}), 400
        flash('无效的角色，请重新选择。')
        return redirect(url_for('register'))

    try:
        db.session.commit()
        if wants_json:
            return jsonify({'success': True}), 201
        flash('注册成功！')
        return redirect(url_for('login'))
    except Exception as e:
        db.session.rollback()
        if wants_json:
            return jsonify({'success': False, 'message': f'注册失败：{str(e)}'}), 400
        flash(f'注册失败：{str(e)}')
        return redirect(url_for('register'))


