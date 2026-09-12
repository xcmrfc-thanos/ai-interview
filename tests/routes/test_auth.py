import io

from werkzeug.security import check_password_hash
from pathlib import Path

from flask import Flask

from models import db, import_workspace_models
from models.Applicant import Applicant
from models.User import User
from models.VoiceProfile import VoiceProfile
from routes.route_login import login_bp
from routes.route_user import user_bp

from utils.auth_security import hash_password, verify_password


def test_new_password_is_stored_as_a_hash_and_verifies():
    hashed = hash_password("correct horse battery staple")

    assert hashed != "correct horse battery staple"
    assert check_password_hash(hashed, "correct horse battery staple")
    assert verify_password(hashed, "correct horse battery staple") is True
    assert verify_password(hashed, "wrong") is False


def test_legacy_plaintext_password_can_be_verified_and_marked_for_upgrade():
    result = verify_password("legacy-secret", "legacy-secret")

    assert result is True


def test_upload_filename_is_reduced_to_a_safe_basename():
    from utils.auth_security import safe_upload_name

    assert safe_upload_name("../../resume final.pdf") == "resume_final.pdf"
    assert safe_upload_name("avatar.exe") is None


def test_source_files_do_not_contain_the_retired_hardcoded_api_key():
    retired_key = "NZqKCStAhnPgzYxeedqv" + ":MLftHIXgoeRTbmkACUHD"
    source_roots = (Path("app.py"), Path("routes"), Path("services"), Path("utils"))
    source_files = []
    for root in source_roots:
        source_files.extend([root] if root.is_file() else root.rglob("*.py"))

    offenders = [str(path) for path in source_files if retired_key in path.read_text(encoding="utf-8")]

    assert offenders == []


def test_applicant_login_redirects_to_personal_workspace():
    app = Flask(__name__)
    app.secret_key = "test"
    app.config.update(
        TESTING=True,
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
    )
    db.init_app(app)
    import_workspace_models()
    app.register_blueprint(login_bp, url_prefix="/api")

    @app.route("/applicant/workspace", endpoint="applicant_workspace")
    def workspace():
        return "workspace"

    @app.route("/company/dashboard", endpoint="company_dashboard")
    def company_dashboard():
        return "company"

    with app.app_context():
        db.create_all()
        user = User(email="candidate@example.com", password=hash_password("secret"), role="applicant")
        db.session.add(user)
        db.session.flush()
        db.session.add(Applicant(user_id=user.user_id, full_name="测试用户"))
        db.session.commit()

    response = app.test_client().post(
        "/api/login",
        data={"email": "candidate@example.com", "password": "secret"},
    )

    assert response.status_code == 302
    assert response.headers["Location"].endswith("/applicant/workspace")


def test_profile_update_hashes_new_password_and_saves_job_preferences():
    app = Flask(__name__)
    app.secret_key = "test"
    app.config.update(
        TESTING=True,
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
    )
    db.init_app(app)
    import_workspace_models()
    app.register_blueprint(user_bp, url_prefix="/api")
    with app.app_context():
        db.create_all()
        user = User(email="profile@example.com", password=hash_password("old"), role="applicant")
        db.session.add(user)
        db.session.flush()
        db.session.add(Applicant(
            user_id=user.user_id,
            full_name="旧姓名",
            expected_position="旧职位",
            expected_salary=10000,
        ))
        db.session.commit()
        user_id = user.user_id
    client = app.test_client()
    with client.session_transaction() as flask_session:
        flask_session["user_id"] = user_id
        flask_session["role"] = "applicant"

    import secrets

    new_password = "pw-" + secrets.token_hex(8)
    response = client.post("/api/users/update", json={
        "full_name": "新姓名",
        "email": "profile@example.com",
        "expected_position": "Python 工程师",
        "expected_salary": 20000,
        "password": new_password,
    })

    assert response.status_code == 200
    with app.app_context():
        user = db.session.get(User, user_id)
        applicant = Applicant.query.filter_by(user_id=user_id).first()
        assert user.password != new_password
        assert verify_password(user.password, new_password)
        assert applicant.expected_position == "Python 工程师"
        assert applicant.expected_salary == 20000


def test_voice_profile_can_be_uploaded_retrieved_and_deleted(tmp_path):
    app = Flask(__name__)
    app.secret_key = "test"
    app.config.update(
        TESTING=True,
        SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
        UPLOAD_DIR=str(tmp_path),
    )
    db.init_app(app)
    import_workspace_models()
    app.register_blueprint(user_bp, url_prefix="/api")
    with app.app_context():
        db.create_all()
        user = User(email="voice@example.com", password=hash_password("old"), role="applicant")
        db.session.add(user)
        db.session.flush()
        db.session.add(Applicant(user_id=user.user_id, full_name="语音测试"))
        db.session.commit()
        user_id = user.user_id

    client = app.test_client()
    with client.session_transaction() as flask_session:
        flask_session["user_id"] = user_id
        flask_session["role"] = "applicant"

    response = client.post(
        "/api/users/voice-profile",
        data={"audio": (io.BytesIO(b"RIFF voice sample"), "self-introduction.wav")},
        content_type="multipart/form-data",
    )

    assert response.status_code == 201
    payload = response.get_json()
    assert payload["voice_profile"]["available"] is True
    assert payload["voice_profile"]["audio_url"].startswith("/api/users/voice-profile/audio")
    with app.app_context():
        assert VoiceProfile.query.count() == 1

    fetched = client.get("/api/users/voice-profile")
    assert fetched.status_code == 200
    assert fetched.get_json()["voice_profile"]["original_filename"] == "self-introduction.wav"
    audio = client.get("/api/users/voice-profile/audio")
    assert audio.status_code == 200
    assert audio.data == b"RIFF voice sample"

    deleted = client.delete("/api/users/voice-profile")
    assert deleted.status_code == 200
    assert deleted.get_json()["voice_profile"]["available"] is False
