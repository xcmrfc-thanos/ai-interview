from .route_register import register_bp
from .route_login import login_bp
from .route_resume import resume_bp
from .route_knowledge import knowledge_bp, register_knowledge_cli
from .route_interview_plan import interview_plan_bp
from .route_copilot import copilot_bp
from .route_mock_interview import mock_interview_bp
from .route_review import review_bp
from .route_resume_optimize import resume_optimize_bp
from .route_user import user_bp
from .route_settings import settings_bp
from .route_contract import contract_bp


def register_blueprints(app):
    app.register_blueprint(register_bp, url_prefix='/api')
    app.register_blueprint(login_bp, url_prefix='/api')
    app.register_blueprint(resume_bp, url_prefix='/api')
    app.register_blueprint(knowledge_bp, url_prefix='/api')
    app.register_blueprint(interview_plan_bp, url_prefix='/api')
    app.register_blueprint(copilot_bp, url_prefix='/api')
    app.register_blueprint(mock_interview_bp, url_prefix='/api')
    app.register_blueprint(review_bp, url_prefix='/api')
    app.register_blueprint(resume_optimize_bp, url_prefix='/api')
    app.register_blueprint(user_bp, url_prefix='/api')
    app.register_blueprint(settings_bp, url_prefix='/api')
    app.register_blueprint(contract_bp, url_prefix='/api')
    register_knowledge_cli(app)
