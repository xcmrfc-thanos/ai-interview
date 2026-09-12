from flask import request, redirect, url_for, session, flash, jsonify, render_template, Blueprint

from models.Applicant import Applicant
from models.Company import Company
from models.Job import Job
from models.Report import Report
from models.Resume import Resume
from models.Application import Application
from models.AIInterview import AIInterview
from models.InterviewScores import InterviewScores
from models.ResumeScores import ResumeScores
from models.User import User
from models import db
from utils.auth_security import hash_password, password_needs_upgrade, verify_password

login_bp = Blueprint('login', __name__)
import logging

logger = logging.getLogger(__name__)


@login_bp.route('/login', methods=['POST'])
def login():
    if request.method == 'POST':
        wants_json = request.is_json
        #清空会话
        session.clear()
        payload = request.get_json(silent=True) or request.form
        email = (payload.get('email') or '').strip()
        password = payload.get('password') or ''
        user = User.query.filter_by(email=email).first()

        if user and verify_password(user.password, password):
            if password_needs_upgrade(user.password):
                user.password = hash_password(password)
                db.session.commit()
            session['user_id'] = user.user_id

            session['role'] = user.role
            logger.debug("User %s logged in with role %s", user.user_id, user.role)
            if user.role == 'applicant':
                applicant = Applicant.query.filter_by(user_id=user.user_id).first()
                session['full_name'] = applicant.full_name if applicant else ''
                if wants_json:
                    return jsonify({'success': True, 'role': 'applicant'})
                logger.debug("Redirecting to applicant workspace")
                return redirect(url_for('applicant_workspace'))
            elif user.role == 'company':
                # 企业端已归档（docs/archive/legacy-company/）：按契约仅 applicant 可登录
                session.clear()
                if wants_json:
                    return jsonify({'success': False, 'message': '企业端暂未开放，请使用求职者账号登录'}), 403
                flash('企业端暂未开放，请使用求职者账号登录')
                return redirect('/login')
        else:
            if wants_json:
                return jsonify({'success': False, 'message': '邮箱或密码错误'}), 401
            flash('Invalid email or password')
            logger.debug("Invalid email or password")
            return redirect('/login')
    return jsonify({'success': False, 'message': '请使用 POST /api/login'}), 405

@login_bp.route('/latest-scores', methods=['GET'])
def latest_scores():
    user_id = session.get('user_id')
    if not user_id:
        return jsonify({'error': 'User not logged in'}), 401

    # 先获取applicant_id
    applicant = Applicant.query.filter_by(user_id=user_id).first()
    if not applicant:
        return jsonify({'error': 'No applicant found for this user'}), 404
    
    # 使用applicant_id查询resume，而不是user_id
    resumes = Resume.query.filter_by(applicant_id=applicant.applicant_id).all()
    if not resumes:
        return jsonify({'error': 'No resumes found for this user'}), 404

    # 初始化latest_application为None
    latest_application = None

    # 遍历每个resume，找到最新的application
    for resume in resumes:
        application = Application.query.filter(
            (Application.resume_id == resume.resume_id) &
            ((Application.status == '待定') | (Application.status == '录用') | (Application.status == '拒绝'))
        ).order_by(Application.created_at.desc()).first()
        if application:
            if not latest_application or application.created_at > latest_application.created_at:
                latest_application = application

    if not latest_application:
        return jsonify({'error': 'No applications found for this user'}), 404

    # 获取与该application相关的最新的AIInterview
    print(latest_application.application_id)
    ai_interview = AIInterview.query.filter_by(application_id=latest_application.application_id).order_by(AIInterview.end_time.desc()).first()
    if not ai_interview:
        return jsonify({'error': 'No AI interviews found for this application'}), 404

    # 获取与该AIInterview相关的InterviewScores
    interview_scores = InterviewScores.query.filter_by(interview_id=ai_interview.interview_id).first()
    if not interview_scores:
        return jsonify({'error': 'No interview scores found for this AI interview'}), 404

    # 获取与该latest_application相关的resume的ResumeScores
    report = Report.query.filter_by(resume_id=latest_application.resume_id).first()
    if not report:
        return jsonify({'error': 'No report found for this application'}), 404
    resume_scores = ResumeScores.query.filter_by(report_id=report.report_id).first()
    if not resume_scores:
        return jsonify({'error': 'No resume scores found for this resume'}), 404

    # 构建返回的数据
    scores = {
        'technical_ability': interview_scores.technical_ability,
        'learning_ability': interview_scores.learning_ability,
        'team_collaboration': interview_scores.team_collaboration,
        'problem_solving': interview_scores.problem_solving,
        'communication_expression': interview_scores.communication_expression,
        'tech_match': resume_scores.tech_match,
        'experience_match': resume_scores.experience_match,
        'education_match': resume_scores.education_match,
        'potential_match': resume_scores.potential_match
    }
    print(scores)

    return jsonify(scores)


@login_bp.route('/score_trend', methods=['GET'])
def applicant_score_trend():
    user_id = session.get('user_id')
    if not user_id:
        return jsonify({'error': 'User not logged in'}), 401

    # 先获取applicant_id
    applicant = Applicant.query.filter_by(user_id=user_id).first()
    if not applicant:
        return jsonify({'error': 'No applicant found for this user'}), 404
    
    # 使用applicant_id查询resume，而不是user_id
    resumes = Resume.query.filter_by(applicant_id=applicant.applicant_id).all()
    if not resumes:
        return jsonify({'error': 'No resumes found for this user'}), 404

    scores = []

    for resume in resumes:
        applications = Application.query.filter_by(resume_id=resume.resume_id).all()
        for app in applications:
            interviews = AIInterview.query.filter_by(application_id=app.application_id).order_by(AIInterview.end_time.asc()).all()

            for interview in interviews:
                if interview.end_time is not None:
                    date_only = interview.end_time.strftime('%Y-%m-%d')  # 只提取年月日
                    scores.append([date_only, interview.overall_score])


    # scores=[['第一次',71.0],['第二次',40.7],['第三次',55.0],['第四次',70.0],['第五次',90.5],['第六次',70.0]]
    print(scores)

    return jsonify(scores)


@login_bp.route('/achart-data', methods=['GET'])
def achart_data():
    user_id = session.get('user_id')  # 获取当前用户的ID
    company = Company.query.filter_by(user_id=user_id).first()  # 通过user_id获取company信息
    if not company:
        return jsonify([])

    company_id = company.company_id  # 获取公司的ID
    jobs = Job.query.filter_by(company_id=company_id).all()  # 获取所有岗位的信息
    if not jobs:
        return jsonify([])

    applications = []
    for job in jobs:
        applications.extend(Application.query.filter_by(job_id=job.job_id).all())  # 获取所有岗位的所有申请

    # 统计不同学历和工作经验年限的申请数量
    education_years_counts = {
        '高中': [0, 0, 0, 0],
        '大专': [0, 0, 0, 0],
        '本科': [0, 0, 0, 0],
        '硕士': [0, 0, 0, 0],
        '博士': [0, 0, 0, 0]
    }

    for app in applications:
        resume = Resume.query.filter_by(resume_id=app.resume_id).first()  # 通过application_id获取resume
        if resume:
            applicant=Applicant.query.filter_by(applicant_id=resume.applicant_id).first()
            if applicant and applicant.education_level in education_years_counts:
                if applicant.work_years == 0:
                    education_years_counts[applicant.education_level][0] += 1
                elif 0 < applicant.work_years <= 2:
                    education_years_counts[applicant.education_level][1] += 1
                elif 2 < applicant.work_years <= 5:
                    education_years_counts[applicant.education_level][2] += 1
                elif applicant.work_years > 5:
                    education_years_counts[applicant.education_level][3] += 1

    # 按照 ['高中', '大专', '本科', '硕士', '博士'] 的顺序返回数据
    data = [
        education_years_counts['高中'],
        education_years_counts['大专'],
        education_years_counts['本科'],
        education_years_counts['硕士'],
        education_years_counts['博士']
    ]
    print(data)
    return jsonify(data)

@login_bp.route('/job-count-data', methods=['GET'])
def job_count_data():
    user_id = session.get('user_id')  # 获取当前用户的ID
    company = Company.query.filter_by(user_id=user_id).first()  # 通过user_id获取company信息
    if not company:
        return jsonify([])

    company_id = company.company_id  # 获取公司的ID
    jobs = Job.query.filter_by(company_id=company_id).all()  # 获取所有岗位的信息
    if not jobs:
        return jsonify([])

    job_counts = {}
    for job in jobs:
        if job.title in job_counts:
            job_counts[job.title] += 1
        else:
            job_counts[job.title] = 1

    # 转换为需要的格式
    job_counts_list = [{'value': count, 'name': name} for name, count in job_counts.items()]

    print(job_counts_list)
    return jsonify(job_counts_list)

@login_bp.route('/pending_numble', methods=['GET'])
def get_pending_applications():
    # 获取当前登录企业的 user_id
    user_id = session.get('user_id')  # 确保在 session 中存储了企业 ID

    if not user_id:
        return jsonify({'error': '未找到企业信息'}), 400
    company = Company.query.filter_by(user_id=user_id).first()
    if not company:
        return jsonify({'error': '未找到企业信息'}), 400
    # 查询该企业发布的所有职位
    jobs = Job.query.filter_by(company_id=company.company_id).all()

    if not jobs:
        return jsonify({'pending_count': 0})

    # 获取职位 ID 列表
    job_ids = [job.job_id for job in jobs]

    # 查询所有该企业的待处理申请
    pending_count = Application.query.filter(
        Application.job_id.in_(job_ids),
        Application.status == '已投递'
    ).count()
    print(pending_count)

    return jsonify({'pending_count': pending_count})

@login_bp.route('/job_count', methods=['GET'])
def get_job_count():
    # 获取当前登录企业的 user_id
    user_id = session.get('user_id')  # 确保在 session 中存储了企业 ID

    if not user_id:
        return jsonify({'error': '未找到企业信息'}), 400
    company = Company.query.filter_by(user_id=user_id).first()
    if not company:
        return jsonify({'error': '未找到企业信息'}), 400
    # 查询该企业发布的所有职位
    job_count = Job.query.filter_by(company_id=company.company_id).count()
    print(job_count)

    return jsonify({'job_count': job_count})

@login_bp.route('/check_role', methods=['GET'])
def check_role():
    """返回当前登录用户的角色"""
    if 'user_id' not in session:
        return jsonify({'success': False, 'message': '用户未登录', 'role': None}), 401
    
    return jsonify({
        'success': True,
        'role': session.get('role')
    })


