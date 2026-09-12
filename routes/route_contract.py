"""契约补充路由（docs/api-contract.md §1–§3）。蓝图挂载时统一带 /api 前缀。

- POST /logout                  JSON 登出（完整路径 /api/logout）
- GET  /resumes                 简历列表（契约路径，与 /api/get_resumes 同处理器）
- GET  /resumes/<id>/status     解析状态轮询别名
"""

from flask import Blueprint, jsonify, session

contract_bp = Blueprint('contract', __name__)


@contract_bp.route('/logout', methods=['POST'])
def logout_json():
    session.clear()
    return jsonify({'success': True})


@contract_bp.route('/health', methods=['GET'])
def health():
    """契约 §7 健康探针（与 Java /api/health 对齐）。"""
    return jsonify({'status': 'ok'})


@contract_bp.route('/resumes', methods=['GET'])
def resumes_list():
    from routes.route_resume import get_resumes

    return get_resumes()


@contract_bp.route('/resumes', methods=['POST'])
def resumes_upload():
    """契约 §3 上传路径（复用 /api/upload_resume 处理器）。"""
    from routes.route_resume import upload_resume

    return upload_resume()


@contract_bp.route('/resumes/<int:resume_id>/status', methods=['GET'])
def resumes_status(resume_id):
    from routes.route_resume import resume_status

    return resume_status(resume_id)
