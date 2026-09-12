"""JD-specific resume optimization snapshots and confirmation API."""

from flask import Blueprint, current_app, jsonify, request, session
from openai import OpenAIError

from models import db
from models.Applicant import Applicant
from models.InterviewPlan import InterviewPlan
from models.Resume import Resume
from models.ResumeOptimization import ResumeOptimization
from services.resume_optimize_service import ResumeOptimizeService
from utils.auth_security import applicant_required
from utils.llm_config import get_llm_config, get_llm_timeout, load_project_env
from utils.llm_client import chat_complete


resume_optimize_bp = Blueprint("resume_optimize", __name__)


@resume_optimize_bp.post("/resume-optimizations")
@applicant_required
def generate_optimization():
    data = request.get_json(silent=True) or {}
    plan_id = data.get("plan_id")
    if not isinstance(plan_id, int):
        return jsonify({"success": False, "message": "缺少有效的面试计划 ID"}), 400
    plan = _owned_plan(plan_id)
    if not plan:
        return _not_found()
    resume = _owned_resume(plan.resume_id)
    if not resume:
        return jsonify({"success": False, "message": "面试计划未关联可用简历"}), 409
    try:
        result = _service().generate(plan, resume)
    except OpenAIError as error:
        return jsonify({"success": False, "message": f"模型服务暂不可用：{error}"}), 503
    except (RuntimeError, ValueError) as error:
        return jsonify({"success": False, "message": str(error)}), 503
    optimization = ResumeOptimization(
        user_id=session["user_id"],
        plan_id=plan.plan_id,
        resume_id=resume.resume_id,
        status="needs_review",
        result=result,
    )
    db.session.add(optimization)
    db.session.commit()
    return jsonify({"success": True, "optimization": _optimization_dict(optimization)}), 201


@resume_optimize_bp.get("/resume-optimizations/latest")
@applicant_required
def latest_optimization():
    plan_id = request.args.get("plan_id", type=int)
    if not _owned_plan(plan_id):
        return _not_found()
    optimization = ResumeOptimization.query.filter_by(
        plan_id=plan_id, user_id=session["user_id"]
    ).order_by(ResumeOptimization.created_at.desc()).first()
    if not optimization:
        return _not_found()
    return jsonify({"success": True, "optimization": _optimization_dict(optimization)})


@resume_optimize_bp.post(
    "/resume-optimizations/<int:optimization_id>/suggestions/<suggestion_id>/confirm"
)
@applicant_required
def confirm_suggestion(optimization_id, suggestion_id):
    optimization = ResumeOptimization.query.filter_by(
        optimization_id=optimization_id, user_id=session["user_id"]
    ).first()
    if not optimization:
        return _not_found()
    valid_ids = {
        item.get("suggestion_id") for item in (optimization.result or {}).get("suggestions", [])
    }
    if suggestion_id not in valid_ids:
        return jsonify({"success": False, "message": "优化建议不存在"}), 404
    confirmed = list(optimization.confirmed_suggestion_ids or [])
    if suggestion_id not in confirmed:
        confirmed.append(suggestion_id)
        optimization.confirmed_suggestion_ids = confirmed
        optimization.status = "reviewing"
        db.session.commit()
    return jsonify({"success": True, "optimization": _optimization_dict(optimization)})


def _owned_plan(plan_id):
    if plan_id is None:
        return None
    return InterviewPlan.query.filter_by(plan_id=plan_id, user_id=session["user_id"]).first()


def _owned_resume(resume_id):
    applicant = Applicant.query.filter_by(user_id=session["user_id"]).first()
    if not applicant:
        return None
    return Resume.query.filter_by(resume_id=resume_id, applicant_id=applicant.applicant_id).first()


def _optimization_dict(optimization):
    result = dict(optimization.result or {})
    return {
        "optimization_id": optimization.optimization_id,
        "plan_id": optimization.plan_id,
        "resume_id": optimization.resume_id,
        "status": optimization.status,
        "matches": result.get("matches", []),
        "gaps": result.get("gaps", []),
        "keywords": result.get("keywords", []),
        "suggestions": result.get("suggestions", []),
        "confirmed_suggestion_ids": optimization.confirmed_suggestion_ids or [],
        "created_at": optimization.created_at.isoformat() if optimization.created_at else None,
    }


def _service():
    configured = current_app.config.get("RESUME_OPTIMIZE_LLM_COMPLETE")
    if configured:
        return ResumeOptimizeService(configured)
    load_project_env()
    config = get_llm_config()
    if not config["api_key"]:
        raise RuntimeError("未配置 LLM_API_KEY，无法生成简历优化建议")

    def complete(messages):
        response = chat_complete(messages, kind="think", timeout=get_llm_timeout())
        return response.choices[0].message.content

    return ResumeOptimizeService(complete)


def _not_found():
    return jsonify({"success": False, "message": "面试计划或优化结果不存在"}), 404
