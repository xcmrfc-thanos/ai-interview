"""Owned Copilot business-session lifecycle API."""

from flask import Blueprint, jsonify, request, session

from models import db, utc_now
from models.CopilotSession import CopilotSession
from models.InterviewPlan import InterviewPlan
from models.PreparationPack import PreparationPack
from utils.auth_security import applicant_required


copilot_bp = Blueprint("copilot", __name__)


@copilot_bp.post("/copilot/sessions")
@applicant_required
def create_session():
    data = request.get_json(silent=True) or {}
    plan_id = data.get("plan_id")
    if not isinstance(plan_id, int):
        return jsonify({"success": False, "message": "缺少有效的面试计划 ID"}), 400
    plan = _owned_plan(plan_id)
    if not plan:
        return _not_found()
    if plan.status != "active" or not plan.resume_id:
        return jsonify({"success": False, "message": "面试计划未启用或未关联简历"}), 409

    pack = PreparationPack.query.filter_by(plan_id=plan.plan_id).order_by(
        PreparationPack.version.desc()
    ).first()
    copilot_session = CopilotSession(
        user_id=session["user_id"],
        plan_id=plan.plan_id,
        resume_id=plan.resume_id,
        preparation_pack_id=pack.pack_id if pack else None,
        status="created",
    )
    plan.last_used_at = utc_now()
    db.session.add(copilot_session)
    db.session.commit()
    payload = {"success": True, "session": _session_dict(copilot_session)}
    if not pack or pack.status != "confirmed" or plan.preparation_status == "outdated":
        payload["warning"] = {
            "code": "PREPARATION_OUTDATED",
            "message": "准备包尚未确认或已过期，可以继续启动，但回答上下文可能不完整。",
        }
    return jsonify(payload), 201


@copilot_bp.get("/copilot/sessions/<int:session_id>")
@applicant_required
def get_session(session_id):
    copilot_session = _owned_session(session_id)
    if not copilot_session:
        return _not_found()
    return jsonify({"success": True, "session": _session_dict(copilot_session, include_turns=True)})


@copilot_bp.post("/copilot/sessions/<int:session_id>/pause")
@applicant_required
def pause_session(session_id):
    copilot_session = _owned_session(session_id)
    if not copilot_session:
        return _not_found()
    if copilot_session.status != "ended":
        copilot_session.status = "paused"
        db.session.commit()
    return jsonify({"success": True, "session": _session_dict(copilot_session)})


@copilot_bp.post("/copilot/sessions/<int:session_id>/resume")
@applicant_required
def resume_session(session_id):
    copilot_session = _owned_session(session_id)
    if not copilot_session:
        return _not_found()
    if copilot_session.status == "ended":
        return jsonify({"success": False, "message": "已结束的会话不能继续"}), 409
    copilot_session.status = "running"
    db.session.commit()
    return jsonify({"success": True, "session": _session_dict(copilot_session)})


@copilot_bp.post("/copilot/sessions/<int:session_id>/end")
@applicant_required
def end_session(session_id):
    copilot_session = _owned_session(session_id)
    if not copilot_session:
        return _not_found()
    if copilot_session.status != "ended":
        copilot_session.status = "ended"
        copilot_session.ended_at = utc_now()
        db.session.commit()
    return jsonify({"success": True, "session": _session_dict(copilot_session)})


def _owned_plan(plan_id):
    return InterviewPlan.query.filter_by(plan_id=plan_id, user_id=session["user_id"]).first()


def _owned_session(session_id):
    return CopilotSession.query.filter_by(session_id=session_id, user_id=session["user_id"]).first()


def _session_dict(copilot_session, include_turns=False):
    value = {
        "session_id": copilot_session.session_id,
        "plan_id": copilot_session.plan_id,
        "resume_id": copilot_session.resume_id,
        "preparation_pack_id": copilot_session.preparation_pack_id,
        "status": copilot_session.status,
        "last_client_sequence": copilot_session.last_client_sequence,
        "current_turn_number": copilot_session.current_turn_number,
        "created_at": copilot_session.created_at.isoformat() if copilot_session.created_at else None,
        "updated_at": copilot_session.updated_at.isoformat() if copilot_session.updated_at else None,
        "ended_at": copilot_session.ended_at.isoformat() if copilot_session.ended_at else None,
    }
    if include_turns:
        value["turns"] = [_turn_dict(turn) for turn in copilot_session.turns]
    return value


def _turn_dict(turn):
    return {
        "turn_id": turn.turn_id,
        "turn_number": turn.turn_number,
        "status": turn.status,
        "partial_transcript": turn.partial_transcript,
        "transcript": turn.transcript,
        "answer_points": turn.answer_points or [],
        "reference_answer": turn.reference_answer,
        "follow_up": turn.follow_up,
        "knowledge_item_ids": turn.knowledge_item_ids or [],
    }


def _not_found():
    return jsonify({"success": False, "message": "Copilot 会话或面试计划不存在"}), 404
