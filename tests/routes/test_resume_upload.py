from zipfile import ZipFile, ZIP_DEFLATED
from types import SimpleNamespace

import pytest

from services.structured_output import StructuredOutputError


def test_docx_text_can_be_extracted_without_remote_file_id(tmp_path):
    from routes.route_resume import _extract_local_resume_text

    resume_path = tmp_path / "resume.docx"
    document_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:body><w:p><w:r><w:t>Python 工程师</w:t></w:r></w:p>'
        '<w:p><w:r><w:t>负责面试系统开发</w:t></w:r></w:p></w:body></w:document>'
    )
    with ZipFile(resume_path, "w", ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", document_xml)

    assert _extract_local_resume_text(resume_path) == "Python 工程师\n负责面试系统开发"


def test_missing_remote_file_id_is_detected_before_content_request():
    from routes.route_resume import _get_remote_file_id

    class UploadResponse:
        id = None

    assert _get_remote_file_id(UploadResponse()) is None


def test_resume_completion_parser_accepts_fenced_json_from_compatible_models():
    from routes.route_resume import _parse_resume_completion

    completion = SimpleNamespace(
        choices=[
            SimpleNamespace(
                message=SimpleNamespace(
                    content="模型说明：\n```json\n{\"name\": \"张三\"}\n```"
                )
            )
        ]
    )

    assert _parse_resume_completion(completion) == {"name": "张三"}


def test_resume_completion_parser_turns_empty_model_content_into_stable_error():
    from routes.route_resume import _parse_resume_completion

    completion = SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=None))]
    )

    with pytest.raises(StructuredOutputError, match="有效 JSON"):
        _parse_resume_completion(completion)


def test_resume_llm_timeout_is_bounded_and_configurable(monkeypatch):
    from routes.route_resume import _get_resume_llm_timeout

    monkeypatch.delenv("LLM_TIMEOUT_SECONDS", raising=False)
    assert _get_resume_llm_timeout() == 45.0

    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "18")
    assert _get_resume_llm_timeout() == 18.0

    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "not-a-number")
    assert _get_resume_llm_timeout() == 45.0


def test_upload_resume_returns_after_local_extraction_without_calling_llm(monkeypatch, tmp_path):
    import io
    from flask import Flask

    from models import db, import_workspace_models
    from models.Applicant import Applicant
    from models.User import User
    import routes.route_resume as route_module

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
    app.register_blueprint(route_module.resume_bp, url_prefix="/api")
    with app.app_context():
        db.create_all()
        db.session.add(User(user_id=1, email="one@example.com", password="x", role="applicant"))
        db.session.flush()
        db.session.add(Applicant(user_id=1, full_name="甲"))
        db.session.commit()

    def unexpected_llm(*_args, **_kwargs):
        raise AssertionError("上传阶段不应调用大模型")

    monkeypatch.setattr(route_module, "chat_complete", unexpected_llm)
    monkeypatch.setattr(route_module, "_start_resume_analysis", lambda *_args: None, raising=False)
    document_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:body><w:p><w:r><w:t>Python Flask 工程师</w:t></w:r></w:p></w:body></w:document>'
    )
    from zipfile import ZipFile, ZIP_DEFLATED
    archive_buffer = io.BytesIO()
    with ZipFile(archive_buffer, "w", ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", document_xml)
    archive_buffer.seek(0)

    client = app.test_client()
    with client.session_transaction() as session:
        session["user_id"] = 1
        session["role"] = "applicant"
    response = client.post(
        "/api/upload_resume",
        data={"storage_name": "python-cv", "file": (archive_buffer, "resume.docx")},
        content_type="multipart/form-data",
    )

    assert response.status_code == 201
    payload = response.get_json()
    assert payload["status"] == "uploaded"
    assert payload["analysis_status"] == "pending"
    with app.app_context():
        resume = db.session.query(route_module.Resume).one()
        assert resume.parsed_data["keywords"] == ["Python", "Flask"]
