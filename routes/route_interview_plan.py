"""Interview plan and preparation-pack API."""

from datetime import datetime, timezone
import os
from threading import Lock, Thread

from flask import Blueprint, current_app, jsonify, request, session
from models import db
from models.Applicant import Applicant
from models.InterviewPlan import InterviewPlan
from models.PreparationPack import PreparationPack
from models.Resume import Resume
from services.preparation_service import PreparationService
from utils.auth_security import applicant_required
from utils.llm_config import get_llm_config, load_project_env
from utils.llm_client import chat_complete


interview_plan_bp = Blueprint("interview_plan", __name__)
_PREPARATION_JOBS = set()
_PREPARATION_JOBS_LOCK = Lock()
PLAN_FIELDS = (
    "resume_id",
    "company_name",
    "position_name",
    "job_description",
    "extra_requirements",
    "level",
    "tech_tags",
)
SOURCE_FIELDS = set(PLAN_FIELDS)
REQUIRED_FIELDS = ("company_name", "position_name", "job_description")
PACK_EDITABLE_FIELDS = (
    "intro_30",
    "intro_60",
    "intro_90",
    "highlights",
    "project_followups",
    "risk_points",
    "frequent_questions",
    "star_stories",
    "review_topics",
    "source_evidence",
)
REGENERATABLE_FIELDS = set(PACK_EDITABLE_FIELDS)


@interview_plan_bp.get("/interview-plans")
@applicant_required
def list_plans():
    plans = InterviewPlan.query.filter_by(user_id=session["user_id"]).order_by(
        InterviewPlan.updated_at.desc()
    ).all()
    return jsonify({"success": True, "plans": [_plan_dict(plan) for plan in plans]})


@interview_plan_bp.post("/interview-plans")
@applicant_required
def create_plan():
    data = request.get_json(silent=True) or {}
    missing = [field for field in REQUIRED_FIELDS if not data.get(field)]
    if missing:
        return jsonify({"success": False, "message": f"缺少字段: {', '.join(missing)}"}), 400
    resume_id = data.get("resume_id")
    resume = _owned_resume(resume_id) if resume_id else None
    if resume_id and not resume:
        return jsonify({"success": False, "message": "简历不存在或无权访问"}), 400
    plan = InterviewPlan(user_id=session["user_id"])
    _apply_plan(plan, data)
    db.session.add(plan)
    db.session.flush()
    if not resume:
        db.session.commit()
        return jsonify({
            "success": True,
            "plan": _plan_dict(plan),
            "preparation": None,
            "next_action": "attach_resume",
        }), 201
    if not current_app.config.get("PREPARATION_LLM_COMPLETE"):
        plan.preparation_status = "generating"
        db.session.commit()
        _start_preparation_generation(current_app._get_current_object(), plan.plan_id)
        return jsonify({
            "success": True,
            "plan": _plan_dict(plan),
            "preparation": None,
            "next_action": "poll_preparation",
            "status": "generating",
        }), 201
    try:
        pack = _build_preparation(plan, resume)
    except (RuntimeError, ValueError) as error:
        plan.preparation_status = "failed"
        db.session.commit()
        return jsonify({
            "success": True,
            "plan": _plan_dict(plan),
            "preparation": None,
            "next_action": "retry_preparation",
            "warning": {"code": "PREPARATION_GENERATION_FAILED", "message": str(error)},
        }), 201
    db.session.commit()
    return jsonify({
        "success": True,
        "plan": _plan_dict(plan),
        "preparation": _pack_dict(pack),
        "next_action": "review_preparation",
    }), 201


@interview_plan_bp.get("/interview-plans/<int:plan_id>")
@applicant_required
def get_plan(plan_id):
    plan = _owned_plan(plan_id)
    if not plan:
        return _not_found()
    return jsonify({"success": True, "plan": _plan_dict(plan, include_packs=True)})


@interview_plan_bp.patch("/interview-plans/<int:plan_id>")
@applicant_required
def update_plan(plan_id):
    plan = _owned_plan(plan_id)
    if not plan:
        return _not_found()
    data = request.get_json(silent=True) or {}
    if "resume_id" in data and data["resume_id"] and not _owned_resume(data["resume_id"]):
        return jsonify({"success": False, "message": "简历不存在或无权访问"}), 400
    changed = any(field in data and getattr(plan, field) != data[field] for field in SOURCE_FIELDS)
    _apply_plan(plan, data)
    if changed:
        plan.preparation_status = "outdated"
        for pack in plan.preparation_packs:
            if pack.status != "outdated":
                pack.status = "outdated"
    db.session.commit()
    return jsonify({"success": True, "plan": _plan_dict(plan)})


@interview_plan_bp.delete("/interview-plans/<int:plan_id>")
@applicant_required
def archive_plan(plan_id):
    plan = _owned_plan(plan_id)
    if not plan:
        return _not_found()
    plan.status = "archived"
    db.session.commit()
    return jsonify({"success": True, "plan": _plan_dict(plan)})


@interview_plan_bp.post("/interview-plans/<int:plan_id>/preparation")
@applicant_required
def generate_preparation(plan_id):
    plan = _owned_plan(plan_id)
    if not plan:
        return _not_found()
    resume = _owned_resume(plan.resume_id)
    if not resume:
        return jsonify({"success": False, "message": "请先关联一份本人简历，再生成准备包"}), 400
    if not current_app.config.get("PREPARATION_LLM_COMPLETE"):
        with _PREPARATION_JOBS_LOCK:
            if plan_id in _PREPARATION_JOBS:
                return jsonify({"success": True, "status": "generating", "message": "准备包正在生成中"}), 202
            _PREPARATION_JOBS.add(plan_id)
        plan.preparation_status = "generating"
        db.session.commit()
        _start_preparation_generation(current_app._get_current_object(), plan_id)
        return jsonify({
            "success": True,
            "status": "generating",
            "message": "准备包已开始生成，请稍候",
            "next_action": "poll_preparation",
        }), 202
    try:
        pack = _build_preparation(plan, resume)
    except (RuntimeError, ValueError) as error:
        plan.preparation_status = "failed"
        db.session.commit()
        return jsonify({"success": False, "message": str(error)}), 503
    db.session.commit()
    return jsonify({"success": True, "preparation": _pack_dict(pack)}), 201


@interview_plan_bp.patch("/interview-plans/<int:plan_id>/preparation/<int:pack_id>")
@applicant_required
def edit_preparation(plan_id, pack_id):
    plan = _owned_plan(plan_id)
    pack = _owned_pack(plan, pack_id)
    if not pack:
        return _not_found()
    data = request.get_json(silent=True) or {}
    for field in PACK_EDITABLE_FIELDS:
        if field in data:
            setattr(pack, field, data[field])
    if pack.status == "confirmed":
        pack.status = "draft"
        pack.confirmed_at = None
    plan.preparation_status = pack.status
    db.session.commit()
    return jsonify({"success": True, "preparation": _pack_dict(pack)})


@interview_plan_bp.post(
    "/interview-plans/<int:plan_id>/preparation/<int:pack_id>/sections/<field>/regenerate"
)
@applicant_required
def regenerate_preparation_section(plan_id, pack_id, field):
    plan = _owned_plan(plan_id)
    pack = _owned_pack(plan, pack_id)
    if not pack:
        return _not_found()
    if field not in REGENERATABLE_FIELDS:
        return jsonify({"success": False, "message": "不支持重新生成该内容"}), 400
    latest = max(plan.preparation_packs, key=lambda item: item.version)
    if latest.pack_id != pack.pack_id or pack.status == "outdated":
        return jsonify({"success": False, "message": "只能重新生成最新有效准备包"}), 409
    resume = _owned_resume(plan.resume_id)
    if not resume:
        return jsonify({"success": False, "message": "请先关联一份本人简历"}), 400
    try:
        result = PreparationService(_llm_complete()).generate_section(plan, resume, field)
        value = result.get(field)
        if value is None or value == "" or value == []:
            raise ValueError("模型未返回该区块内容")
    except (RuntimeError, ValueError) as error:
        return jsonify({"success": False, "message": str(error)}), 503
    setattr(pack, field, value)
    pack.source_fingerprint = result["source_fingerprint"]
    pack.status = "needs_review" if result["needs_review"] else "draft"
    plan.preparation_status = pack.status
    db.session.commit()
    return jsonify({"success": True, "field": field, "preparation": _pack_dict(pack)})


@interview_plan_bp.post("/interview-plans/<int:plan_id>/preparation/<int:pack_id>/confirm")
@applicant_required
def confirm_preparation(plan_id, pack_id):
    plan = _owned_plan(plan_id)
    pack = _owned_pack(plan, pack_id)
    if not pack:
        return _not_found()
    latest = max(plan.preparation_packs, key=lambda item: item.version)
    if latest.pack_id != pack.pack_id or pack.status == "outdated":
        return jsonify({"success": False, "message": "只能确认最新有效准备包"}), 409
    pack.status = "confirmed"
    pack.confirmed_at = datetime.now(timezone.utc)
    plan.preparation_status = "confirmed"
    db.session.commit()
    return jsonify({"success": True, "preparation": _pack_dict(pack)})


def _owned_plan(plan_id):
    return InterviewPlan.query.filter_by(plan_id=plan_id, user_id=session["user_id"]).first()


def _owned_resume(resume_id):
    applicant = Applicant.query.filter_by(user_id=session["user_id"]).first()
    if not applicant:
        return None
    return Resume.query.filter_by(resume_id=resume_id, applicant_id=applicant.applicant_id).first()


def _owned_pack(plan, pack_id):
    if not plan:
        return None
    return PreparationPack.query.filter_by(pack_id=pack_id, plan_id=plan.plan_id).first()


def _apply_plan(plan, data):
    for field in PLAN_FIELDS:
        if field in data:
            value = data[field]
            if field == "tech_tags" and not isinstance(value, list):
                value = []
            setattr(plan, field, value)


def _build_preparation(plan, resume):
    result = PreparationService(_llm_complete()).generate(plan, resume)
    latest_version = max((pack.version for pack in plan.preparation_packs), default=0)
    for existing in plan.preparation_packs:
        if existing.status != "outdated":
            existing.status = "outdated"
    pack = PreparationPack(
        plan_id=plan.plan_id,
        version=latest_version + 1,
        source_fingerprint=result["source_fingerprint"],
        status="needs_review" if result["needs_review"] else "draft",
    )
    for field in PACK_EDITABLE_FIELDS:
        default = "" if field in {"intro_30", "intro_60", "intro_90"} else []
        setattr(pack, field, result.get(field, default))
    plan.preparation_status = pack.status
    plan.last_used_at = datetime.now(timezone.utc)
    db.session.add(pack)
    return pack


def _start_preparation_generation(app, plan_id):
    Thread(target=_run_preparation_generation, args=(app, plan_id), daemon=True).start()


def _run_preparation_generation(app, plan_id):
    with app.app_context():
        try:
            plan = db.session.get(InterviewPlan, plan_id)
            resume = _owned_resume_for_plan(plan)
            if not plan or not resume:
                raise ValueError("关联简历不可用")
            _build_preparation(plan, resume)
            db.session.commit()
        except Exception as error:
            plan = locals().get("plan")
            if plan:
                plan.preparation_status = "failed"
                db.session.commit()
            print(f"Preparation generation failed for plan {plan_id}: {error}")
        finally:
            with _PREPARATION_JOBS_LOCK:
                _PREPARATION_JOBS.discard(plan_id)
            db.session.remove()


def _owned_resume_for_plan(plan):
    if not plan:
        return None
    applicant = Applicant.query.filter_by(user_id=plan.user_id).first()
    if not applicant or not plan.resume_id:
        return None
    return Resume.query.filter_by(
        resume_id=plan.resume_id, applicant_id=applicant.applicant_id
    ).first()


def _plan_dict(plan, include_packs=False):
    linked_resume = db.session.get(Resume, plan.resume_id) if plan.resume_id else None
    value = {
        "plan_id": plan.plan_id,
        "resume_id": plan.resume_id,
        "resume_filename": linked_resume.filename if linked_resume else None,
        "company_name": plan.company_name,
        "position_name": plan.position_name,
        "job_description": plan.job_description,
        "extra_requirements": plan.extra_requirements,
        "level": plan.level,
        "tech_tags": plan.tech_tags or [],
        "status": plan.status,
        "preparation_status": plan.preparation_status,
        "created_at": plan.created_at.isoformat() if plan.created_at else None,
        "updated_at": plan.updated_at.isoformat() if plan.updated_at else None,
    }
    if include_packs:
        value["preparations"] = [_pack_dict(pack) for pack in plan.preparation_packs]
    return value


def _pack_dict(pack):
    value = {
        "pack_id": pack.pack_id,
        "plan_id": pack.plan_id,
        "version": pack.version,
        "status": pack.status,
        "source_fingerprint": pack.source_fingerprint,
        "confirmed_at": pack.confirmed_at.isoformat() if pack.confirmed_at else None,
    }
    for field in PACK_EDITABLE_FIELDS:
        value[field] = getattr(pack, field)
    return value


def _llm_complete():
    configured = current_app.config.get("PREPARATION_LLM_COMPLETE")
    if configured:
        return configured
    load_project_env()
    config = get_llm_config()
    if not config["api_key"]:
        raise RuntimeError("未配置 LLM_API_KEY，无法生成面试准备包")
    try:
        timeout = float(os.getenv("LLM_TIMEOUT_SECONDS", "45"))
    except (TypeError, ValueError):
        timeout = 45.0
    timeout = min(max(timeout, 5.0), 120.0)

    def complete(messages):
        response = chat_complete(messages, kind="think", timeout=timeout)
        return response.choices[0].message.content

    return complete


def _not_found():
    return jsonify({"success": False, "message": "面试计划不存在"}), 404
