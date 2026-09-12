"""Persistent mock-interview API driven by an interview plan."""

from flask import Blueprint, current_app, jsonify, request, session
from models import db, utc_now
from models.Applicant import Applicant
from models.InterviewPlan import InterviewPlan
from models.MockInterview import MockInterview
from models.MockInterviewTurn import MockInterviewTurn
from models.Resume import Resume
from services.mock_interview_service import MockInterviewService
from utils.auth_security import applicant_required
from utils.llm_config import get_llm_config, load_project_env
from utils.llm_client import chat_complete


mock_interview_bp = Blueprint("mock_interview", __name__)


@mock_interview_bp.post("/mock-interviews")
@applicant_required
def create_mock_interview():
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
        outline = _service().generate_outline(plan, resume)
    except (RuntimeError, ValueError) as error:
        return jsonify({"success": False, "message": str(error)}), 503
    interview = MockInterview(
        user_id=session["user_id"],
        plan_id=plan.plan_id,
        status="running",
        current_turn_number=1,
        question_outline=outline,
    )
    db.session.add(interview)
    db.session.flush()
    db.session.add(_new_turn(interview, 1))
    plan.last_used_at = utc_now()
    db.session.commit()
    return jsonify({"success": True, "interview": _interview_dict(interview)}), 201


@mock_interview_bp.get("/mock-interviews/<int:interview_id>")
@applicant_required
def get_mock_interview(interview_id):
    interview = _owned_interview(interview_id)
    if not interview:
        return _not_found()
    return jsonify({"success": True, "interview": _interview_dict(interview, include_turns=True)})


@mock_interview_bp.post("/mock-interviews/<int:interview_id>/answers")
@applicant_required
def answer_mock_interview(interview_id):
    interview = _owned_interview(interview_id)
    if not interview:
        return _not_found()
    if interview.status != "running":
        return jsonify({"success": False, "message": "模拟面试已结束"}), 409
    answer = str((request.get_json(silent=True) or {}).get("answer") or "").strip()
    if not answer:
        return jsonify({"success": False, "message": "回答不能为空"}), 400
    turn = MockInterviewTurn.query.filter_by(
        mock_interview_id=interview.mock_interview_id,
        turn_number=interview.current_turn_number,
    ).first()
    plan = _owned_plan(interview.plan_id)
    resume = _owned_resume(plan.resume_id)
    evaluation = _service().evaluate_answer(
        turn.question,
        answer,
        resume.parsed_data or {},
        plan.job_description,
    )
    turn.answer = answer
    if evaluation["retryable"]:
        turn.status = "evaluation_failed"
        db.session.commit()
        return jsonify({"success": False, "evaluation": evaluation, "message": "评分解析失败，可重试"}), 503
    _apply_evaluation(turn, evaluation)
    outline = interview.question_outline or []
    if interview.current_turn_number < len(outline):
        interview.current_turn_number += 1
        db.session.add(_new_turn(interview, interview.current_turn_number))
    else:
        interview.status = "completed"
        interview.ended_at = utc_now()
    db.session.commit()
    return jsonify({
        "success": True,
        "evaluation": evaluation,
        "interview": _interview_dict(interview, include_turns=True),
    })


@mock_interview_bp.post("/mock-interviews/<int:interview_id>/end")
@applicant_required
def end_mock_interview(interview_id):
    interview = _owned_interview(interview_id)
    if not interview:
        return _not_found()
    if interview.status != "ended":
        interview.status = "ended"
        interview.ended_at = interview.ended_at or utc_now()
        db.session.commit()
    return jsonify({"success": True, "interview": _interview_dict(interview, include_turns=True)})


def _new_turn(interview, turn_number):
    item = interview.question_outline[turn_number - 1]
    return MockInterviewTurn(
        mock_interview_id=interview.mock_interview_id,
        turn_number=turn_number,
        question=item["question"],
        reference_points=item.get("reference_points", []),
        status="asked",
    )


def _apply_evaluation(turn, evaluation):
    turn.reference_points = evaluation["reference_points"]
    turn.scores = evaluation["scores"]
    turn.strengths = evaluation["strengths"]
    improvements = list(evaluation["improvements"])
    improvements.extend(f"核实回答中的数字：{risk}" for risk in evaluation["fact_risks"])
    turn.improvements = improvements
    turn.status = "answered"
    turn.answered_at = utc_now()


def _owned_plan(plan_id):
    return InterviewPlan.query.filter_by(plan_id=plan_id, user_id=session["user_id"]).first()


def _owned_resume(resume_id):
    applicant = Applicant.query.filter_by(user_id=session["user_id"]).first()
    if not applicant:
        return None
    return Resume.query.filter_by(resume_id=resume_id, applicant_id=applicant.applicant_id).first()


def _owned_interview(interview_id):
    return MockInterview.query.filter_by(
        mock_interview_id=interview_id,
        user_id=session["user_id"],
    ).first()


def _interview_dict(interview, include_turns=False):
    current = next((turn for turn in interview.turns if turn.status in {"asked", "evaluation_failed"}), None)
    value = {
        "mock_interview_id": interview.mock_interview_id,
        "plan_id": interview.plan_id,
        "status": interview.status,
        "current_turn_number": interview.current_turn_number,
        "question_count": len(interview.question_outline or []),
        "current_question": _turn_dict(current) if current else None,
        "created_at": interview.created_at.isoformat() if interview.created_at else None,
        "ended_at": interview.ended_at.isoformat() if interview.ended_at else None,
    }
    if include_turns:
        value["turns"] = [_turn_dict(turn) for turn in interview.turns]
    return value


def _turn_dict(turn):
    if not turn:
        return None
    return {
        "turn_id": turn.turn_id,
        "turn_number": turn.turn_number,
        "question": turn.question,
        "answer": turn.answer,
        "reference_points": turn.reference_points or [],
        "scores": turn.scores or {},
        "strengths": turn.strengths or [],
        "improvements": turn.improvements or [],
        "status": turn.status,
    }


def _service():
    configured = current_app.config.get("MOCK_INTERVIEW_LLM_COMPLETE")
    if configured:
        return MockInterviewService(configured)
    load_project_env()
    config = get_llm_config()
    if not config["api_key"]:
        raise RuntimeError("未配置 LLM_API_KEY，无法开始模拟面试")

    def complete(messages):
        response = chat_complete(messages)
        return response.choices[0].message.content

    return MockInterviewService(complete)


def _not_found():
    return jsonify({"success": False, "message": "模拟面试或面试计划不存在"}), 404
