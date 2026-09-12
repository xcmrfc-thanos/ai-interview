"""Authentication, authorization, and upload safety helpers."""

import hmac
from functools import wraps
from pathlib import Path

from flask import jsonify, redirect, request, session, url_for
from werkzeug.security import check_password_hash, generate_password_hash
from werkzeug.utils import secure_filename


HASH_PREFIXES = ("scrypt:", "pbkdf2:")
ALLOWED_RESUME_EXTENSIONS = {".docx", ".doc", ".txt", ".pdf"}


def hash_password(password):
    return generate_password_hash(password)


def password_needs_upgrade(stored_password):
    return not str(stored_password or "").startswith(HASH_PREFIXES)


def verify_password(stored_password, provided_password):
    stored = str(stored_password or "")
    provided = str(provided_password or "")
    if password_needs_upgrade(stored):
        return hmac.compare_digest(stored, provided)
    return check_password_hash(stored, provided)


def safe_upload_name(filename):
    safe_name = secure_filename(Path(str(filename or "")).name)
    if not safe_name or Path(safe_name).suffix.lower() not in ALLOWED_RESUME_EXTENSIONS:
        return None
    return safe_name


def role_matches(session_data, expected_role):
    return bool(session_data.get("user_id") and session_data.get("role") == expected_role)


def _role_required(expected_role):
    def decorator(view):
        @wraps(view)
        def wrapped(*args, **kwargs):
            if role_matches(session, expected_role):
                return view(*args, **kwargs)
            if request.path.startswith("/api/"):
                return jsonify({"success": False, "message": "无权访问"}), 401
            return redirect("/login")

        return wrapped

    return decorator


applicant_required = _role_required("applicant")
company_required = _role_required("company")
