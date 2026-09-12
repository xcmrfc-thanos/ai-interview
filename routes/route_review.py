"""Unified review API for Copilot and mock-interview sessions."""

from datetime import datetime

from flask import Blueprint, Response, jsonify, request, session

from models import db
from models.CopilotSession import CopilotSession
from models.InterviewPlan import InterviewPlan
from models.MockInterview import MockInterview
from models.Review import Review
from services.review_service import ReviewService
from utils.auth_security import applicant_required


review_bp = Blueprint("review", __name__)
REVIEW_FIELDS = (
    "summary",
    "question_categories",
    "scores",
    "fact_risks",
    "expression_issues",
    "weak_topics",
    "next_actions",
    "diagnostics",
)


@review_bp.post("/reviews/generate")
@applicant_required
def generate_review():
    data = request.get_json(silent=True) or {}
    source_type = data.get("source_type")
    source_id = data.get("source_id")
    if source_type not in {"copilot", "mock"} or not isinstance(source_id, int):
        return jsonify({"success": False, "message": "复盘来源无效"}), 400
    existing = Review.query.filter_by(
        user_id=session["user_id"], source_type=source_type, source_id=source_id
    ).first()
    if existing:
        return jsonify({"success": True, "review": _review_dict(existing)})
    source = _owned_source(source_type, source_id)
    if not source:
        return _not_found()
    plan = InterviewPlan.query.filter_by(
        plan_id=source.plan_id, user_id=session["user_id"]
    ).first()
    result = ReviewService.build(source_type, source, plan)
    review = Review(
        user_id=session["user_id"],
        plan_id=plan.plan_id,
        source_type=source_type,
        source_id=source_id,
    )
    for field in REVIEW_FIELDS:
        setattr(review, field, result[field])
    db.session.add(review)
    db.session.commit()
    return jsonify({"success": True, "review": _review_dict(review)}), 201


@review_bp.get("/reviews")
@applicant_required
def list_reviews():
    query = Review.query.filter_by(user_id=session["user_id"])
    plan_id = request.args.get("plan_id", type=int)
    source_type = request.args.get("source_type")
    if plan_id:
        query = query.filter_by(plan_id=plan_id)
    if source_type in {"copilot", "mock"}:
        query = query.filter_by(source_type=source_type)
    date_from = _parse_date(request.args.get("date_from"))
    date_to = _parse_date(request.args.get("date_to"), end_of_day=True)
    if date_from:
        query = query.filter(Review.created_at >= date_from)
    if date_to:
        query = query.filter(Review.created_at <= date_to)
    reviews = query.order_by(Review.created_at.desc()).all()
    return jsonify({"success": True, "reviews": [_review_dict(item) for item in reviews]})


@review_bp.get("/reviews/<int:review_id>")
@applicant_required
def get_review(review_id):
    review = _owned_review(review_id)
    if not review:
        return _not_found()
    return jsonify({"success": True, "review": _review_dict(review)})


@review_bp.get("/reviews/<int:review_id>/export")
@applicant_required
def export_review(review_id):
    review = _owned_review(review_id)
    if not review:
        return _not_found()
    value = _review_dict(review)
    if request.args.get("format") == "json":
        return jsonify(value)
    return Response(_review_text(value), mimetype="text/plain; charset=utf-8")


def _owned_source(source_type, source_id):
    model = CopilotSession if source_type == "copilot" else MockInterview
    primary_key = "session_id" if source_type == "copilot" else "mock_interview_id"
    return model.query.filter_by(
        **{primary_key: source_id, "user_id": session["user_id"]}
    ).first()


def _owned_review(review_id):
    return Review.query.filter_by(review_id=review_id, user_id=session["user_id"]).first()


def _review_dict(review):
    plan = db.session.get(InterviewPlan, review.plan_id)
    value = {
        "review_id": review.review_id,
        "plan_id": review.plan_id,
        "plan": {
            "company_name": plan.company_name,
            "position_name": plan.position_name,
        } if plan else None,
        "source_type": review.source_type,
        "source_id": review.source_id,
        "created_at": review.created_at.isoformat() if review.created_at else None,
        "updated_at": review.updated_at.isoformat() if review.updated_at else None,
    }
    for field in REVIEW_FIELDS:
        value[field] = getattr(review, field)
    return value


def _review_text(review):
    plan = review.get("plan") or {}
    lines = [
        f"# {plan.get('company_name', '')} · {plan.get('position_name', '')} 面试复盘",
        "",
        review["summary"],
        "",
        "## 评分",
    ]
    lines.extend(f"- {name}: {score}" for name, score in review["scores"].items())
    lines.extend(["", "## 下一步"])
    lines.extend(f"- {item}" for item in review["next_actions"])
    return "\n".join(lines)


def _parse_date(value, end_of_day=False):
    if not value:
        return None
    try:
        parsed = datetime.strptime(value, "%Y-%m-%d")
    except ValueError:
        return None
    return parsed.replace(hour=23, minute=59, second=59) if end_of_day else parsed


def _not_found():
    return jsonify({"success": False, "message": "复盘或来源记录不存在"}), 404
