import json

from flask import Flask

from models import db, import_workspace_models
from models.User import User
from routes.route_knowledge import register_knowledge_cli


def test_knowledge_cli_imports_and_reports_stats(tmp_path):
    app = Flask(__name__)
    app.config.update(SQLALCHEMY_DATABASE_URI="sqlite:///:memory:", SQLALCHEMY_TRACK_MODIFICATIONS=False)
    db.init_app(app)
    import_workspace_models()
    register_knowledge_cli(app)
    with app.app_context():
        db.create_all()
        db.session.add(User(user_id=1, email="owner@example.com", password="x", role="applicant"))
        db.session.commit()
    source = tmp_path / "knowledge.json"
    source.write_text(json.dumps([{"title": "STAR", "question": "如何使用 STAR？"}], ensure_ascii=False), encoding="utf-8")

    runner = app.test_cli_runner()
    imported = runner.invoke(args=["knowledge", "import", str(source), "--user-id", "1"])
    stats = runner.invoke(args=["knowledge", "stats", "--user-id", "1"])

    assert imported.exit_code == 0
    assert "新增 1" in imported.output
    assert stats.exit_code == 0
    assert "启用 1" in stats.output
