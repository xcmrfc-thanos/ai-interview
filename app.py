import os
from pathlib import Path

from flask_cors import cross_origin

from flask import Flask, request, redirect, url_for, session, jsonify, send_from_directory, send_file
from flask_sqlalchemy import SQLAlchemy

from models import db, User, Applicant, Resume, import_workspace_models
from config import *
import traceback

from routes import register_blueprints
from utils.socketio_service import initialize_socketio
from utils.llm_config import load_project_env
from utils.auth_security import applicant_required
import json

load_project_env()
app = Flask(__name__)
# 未配置 APP_SECRET_KEY 时使用随机密钥，避免可预测的会话签名导致伪造登录。
# 本地开发仍可运行（进程重启后会话失效）；生产环境必须在 .env 中配置固定密钥。
import sys as _sys

_secret_key = os.getenv("APP_SECRET_KEY")
if not _secret_key:
    import secrets

    _secret_key = secrets.token_hex(32)
    print(
        "警告：未检测到 APP_SECRET_KEY，已使用随机密钥（进程重启后所有会话失效）。"
        "生产环境请在 .env 中配置 APP_SECRET_KEY。",
        file=_sys.stderr,
    )
app.secret_key = _secret_key

# 允许的文件扩展名
ALLOWED_EXTENSIONS = {'docx', 'doc', 'txt', 'pdf'}
app.config["UPLOAD_DIR"] = os.getenv(
    "UPLOAD_DIR",
    str(Path(__file__).resolve().parent / "uploads"),
)
app.config["MAX_CONTENT_LENGTH"] = int(
    os.getenv("MAX_CONTENT_LENGTH_MB", "10")
) * 1024 * 1024
Path(app.config["UPLOAD_DIR"]).mkdir(parents=True, exist_ok=True)

# 默认使用本地 SQLite，避免启动实时 Copilot 时依赖历史 MySQL 凭据。
# 部署或需要既有数据时，设置 AI_INTERVIEW_DATABASE_URL 覆盖该连接。
app.config["SQLALCHEMY_DATABASE_URI"] = os.getenv(
    "AI_INTERVIEW_DATABASE_URL",
    "sqlite:///ai_interview_local.db",
)
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False

# ---- 跨域（前后端分离部署）：CORS_ORIGINS 配置允许的前端来源（逗号分隔）----
# 例：CORS_ORIGINS=http://localhost:5173,http://localhost:4173
# 未配置时不开跨域，前后端同源部署行为不变。跨源会话 Cookie 需浏览器同站（同主机不同端口即可）。
from flask_cors import CORS as flask_cors_ext

_cors_origins = [o.strip() for o in os.getenv("CORS_ORIGINS", "").split(",") if o.strip()]
if _cors_origins:
    flask_cors_ext(app, origins=_cors_origins, supports_credentials=True)

# ---- web/dist SPA 前端（页面统一由前端工程提供，服务端仅保留 API/WS） ----
_WEB_DIST = Path(__file__).resolve().parent / "web" / "dist"
_WEB_INDEX = _WEB_DIST / "index.html"


def _spa_index():
    """已迁移页面回落 web/dist/index.html，由 SPA 客户端路由接管。"""
    if _WEB_INDEX.exists():
        return send_file(_WEB_INDEX, mimetype="text/html")
    return "页面已迁移至前端工程（web/），请先构建：cd web && pnpm build", 404

# 初始化 SQLAlchemy
db.init_app(app)  # 确保db实例绑定到app

with app.app_context():
    import_workspace_models()
    db.create_all()  # 创建所有表

def allowed_file(filename):
    return '.' in filename and \
           filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS



@app.route('/')
def hello_world():
    return _spa_index()

@app.route('/loginView')
def login_view():
    return _spa_index()

@app.route('/registerView')
def register_view():
    return _spa_index()

@app.route('/applicant/dashboard')
@applicant_required
def applicant_dashboard():
    return redirect(url_for('applicant_workspace'))

@app.route('/applicant/workspace')
@applicant_required
def applicant_workspace():
    return _spa_index()

@app.route('/applicant/interview-plans')
@applicant_required
def applicant_interview_plans():
    return _spa_index()

@app.route('/applicant/interview-plans/<int:plan_id>')
@applicant_required
def applicant_interview_plan_detail(plan_id):
    return _spa_index()

@app.route('/uploads/<path:filename>')
@applicant_required
def serve_resume_file(filename):
    applicant = Applicant.query.filter_by(user_id=session['user_id']).first()
    if not applicant:
        return jsonify({"success": False, "message": "简历不存在"}), 404
    allowed = set()
    for resume in db.session.query(Resume).filter_by(applicant_id=applicant.applicant_id).all():
        base = Path(resume.file_url or "")
        if not base.name:
            continue
        allowed.add(base.name)
        if base.suffix:
            for suffix in (".md", ".txt", ".pdf"):
                allowed.add(base.with_suffix(suffix).name)
    if Path(filename).name not in allowed:
        return jsonify({"success": False, "message": "简历不存在"}), 404
    return send_from_directory(app.config["UPLOAD_DIR"], filename)


@app.route('/applicant/sim_interview')
@applicant_required
def candidate_interview_room():
    return redirect(url_for('applicant_mock_interview', plan_id=request.args.get('plan_id', type=int)))

@app.route('/applicant/interview')
@applicant_required
def candidate_interview():
    return redirect(url_for('applicant_interview_plans'))

@app.route('/applicant/personal_center')
@applicant_required
def candidate_profile():
    return _spa_index()

@app.route('/applicant/knowledge')
@applicant_required
def applicant_knowledge():
    return _spa_index()

@app.route('/applicant/resume')
@applicant_required
def candidate_resume():
    return redirect(url_for('applicant_resume_manage'))

@app.route('/applicant/reports')
@applicant_required
def applicant_report():
    return redirect(url_for('applicant_reviews'))

@app.route('/applicant/analyze_resume')
@applicant_required
def company_analyze_resume():
    return redirect(url_for('applicant_resume_manage'))

@app.route('/applicant/resume_report')
@applicant_required
def company_resume_report():
    return redirect(url_for('applicant_resume_manage'))

@app.route('/applicant/upload_resume')
@applicant_required
def company_upload_resume():
    return redirect(url_for('applicant_resume_manage'))

@app.route('/applicant/interview_manage')
@applicant_required
def company_interview_manage():
    return redirect(url_for('applicant_interview_plans'))

@cross_origin(origins=["https://cdn.novadata.top","http://localhost:5000"])
@app.route('/applicant/avatar')
@applicant_required
def applicant_avatar():
    return redirect(url_for('applicant_mock_interview'))

@app.route('/applicant/interviewindex')
@applicant_required
def candidate_interview_index():
    return redirect(url_for('applicant_mock_interview'))

@app.route('/applicant/copilot')
@applicant_required
def applicant_copilot():
    return _spa_index()

@app.route('/applicant/mock-interview')
@applicant_required
def applicant_mock_interview():
    return _spa_index()

@app.route('/applicant/interview-workspace')
@applicant_required
def applicant_interview_workspace():
    mode = request.args.get('mode')
    plan_id = request.args.get('plan_id', type=int)
    endpoint = 'applicant_copilot' if mode == 'copilot' else 'applicant_mock_interview'
    if plan_id is not None:
        return redirect(url_for(endpoint, plan_id=plan_id))
    return redirect(url_for(endpoint))


@app.route('/applicant/reviews')
@applicant_required
def applicant_reviews():
    return _spa_index()

@app.route('/applicant/reviews/<int:review_id>')
@applicant_required
def applicant_review_detail(review_id):
    return _spa_index()

@app.route('/applicant/resume-optimize')
@applicant_required
def applicant_resume_optimize():
    return _spa_index()

@app.route('/applicant/resume_manage')
@applicant_required
def applicant_resume_manage():
    return _spa_index()

@app.route('/applicant/TestConfig')
@applicant_required
def applicant_TestConfig():
    return redirect(url_for('applicant_mock_interview'))

@app.route('/logout')
def logout():
    # 删除会话中的所有数据
    session.clear()
    # 重定向到首页
    return redirect(url_for('hello_world'))

@app.route('/check_role')
def check_role():
    """返回当前登录用户的角色"""
    if 'user_id' not in session:
        return jsonify({'success': False, 'message': '用户未登录', 'role': None}), 401

    return jsonify({
        'success': True,
        'role': session.get('role')
    })

register_blueprints(app)
socketio = initialize_socketio(app)

# 裸 WebSocket Copilot 通道（契约 docs/api-contract.md §6，React 前端使用）
from utils.copilot_ws import register_copilot_ws

register_copilot_ws(app)

# ---- web/dist SPA 托管（设置 SPA_ENABLED=1 启用；默认保持服务端模板渲染） ----
# _WEB_DIST/_WEB_INDEX 已在文件头部定义，此处只读环境开关
_SPA_ENABLED = os.getenv("SPA_ENABLED", "") == "1"
# 走 SPA 入口的页面路径前缀（API/WS/静态资源除外）
_SPA_PREFIXES = ("/applicant", "/login", "/register")


class _SpaFallbackMiddleware:
    """web/dist 存在时：/assets/* 服务构建产物，页面路径回落 index.html。"""

    def __init__(self, wsgi):
        self.wsgi = wsgi

    def __call__(self, environ, start_response):
        path = environ.get("PATH_INFO", "")
        if environ.get("REQUEST_METHOD", "GET") == "GET" and _WEB_INDEX.exists():
            if path.startswith("/assets/"):
                rel = path[len("/assets/"):]
                if rel and ".." not in rel:
                    target = _WEB_DIST / "assets" / rel
                    if target.is_file():
                        from werkzeug.wsgi import wrap_file

                        ext = target.suffix.lower()
                        types = {
                            ".js": "text/javascript", ".css": "text/css",
                            ".html": "text/html", ".svg": "image/svg+xml",
                            ".png": "image/png", ".woff2": "font/woff2",
                            ".woff": "font/woff", ".json": "application/json",
                        }
                        headers = [("Content-Type", types.get(ext, "application/octet-stream")),
                                   ("Cache-Control", "public, max-age=31536000, immutable")]
                        start_response("200 OK", headers)
                        return wrap_file(environ, open(target, "rb"))
                start_response("404 NOT FOUND", [("Content-Type", "text/plain")])
                return [b"not found"]
            if (path == "/" or path.startswith(_SPA_PREFIXES)) and not path.startswith(("/api", "/ws", "/uploads", "/static")):
                from werkzeug.wsgi import wrap_file

                headers = [("Content-Type", "text/html; charset=utf-8"), ("Cache-Control", "no-cache")]
                start_response("200 OK", headers)
                return wrap_file(environ, open(_WEB_INDEX, "rb"))
        return self.wsgi(environ, start_response)


if _SPA_ENABLED and _WEB_INDEX.exists():
    app.wsgi_app = _SpaFallbackMiddleware(app.wsgi_app)
    print(f"SPA 托管已启用：{_WEB_DIST}")
else:
    print("SPA 托管未启用（SPA_ENABLED=1 且 web/dist 存在时启用），页面保持服务端模板渲染。")

if __name__ == '__main__':
    # 统一入口端口：SERVER_PORT 控制（.env 默认 18081，与前端代理/文档一致）
    _port = int(os.getenv("SERVER_PORT", "18081"))
    _host = os.getenv("SERVER_HOST", "127.0.0.1")
    socketio.run(app, host=_host, port=_port, debug=False, allow_unsafe_werkzeug=True)
