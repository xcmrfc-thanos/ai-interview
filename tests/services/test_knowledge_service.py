import json
from pathlib import Path

from flask import Flask

from models import db, import_workspace_models
from models.KnowledgeItem import KnowledgeItem
from models.User import User
from services.knowledge_service import KnowledgeService, parse_knowledge_file


def build_app():
    app = Flask(__name__)
    app.config.update(SQLALCHEMY_DATABASE_URI="sqlite:///:memory:", SQLALCHEMY_TRACK_MODIFICATIONS=False)
    db.init_app(app)
    import_workspace_models()
    return app


def test_parse_json_markdown_and_text_knowledge_files(tmp_path):
    json_path = tmp_path / "items.json"
    json_path.write_text(json.dumps([{"title": "Redis", "question": "什么是缓存击穿？"}], ensure_ascii=False), encoding="utf-8")
    md_path = tmp_path / "items.md"
    md_path.write_text("# Redis\n## 缓存穿透\n查询不存在的数据。\n## 缓存雪崩\n大量缓存同时失效。", encoding="utf-8")
    txt_path = tmp_path / "items.txt"
    txt_path.write_text("STAR 方法用于组织行为面试答案。", encoding="utf-8")

    assert parse_knowledge_file(json_path)[0]["question"] == "什么是缓存击穿？"
    assert [item["title"] for item in parse_knowledge_file(md_path)] == ["缓存穿透", "缓存雪崩"]
    assert parse_knowledge_file(txt_path)[0]["standard_answer"].startswith("STAR")


def test_import_deduplicates_and_search_ranks_matching_tags():
    app = build_app()
    with app.app_context():
        db.create_all()
        db.session.add(User(user_id=1, email="user@example.com", password="x", role="applicant"))
        db.session.commit()
        service = KnowledgeService()
        first = service.import_items(1, [{
            "title": "Redis 缓存击穿",
            "question": "如何解决缓存击穿？",
            "tech_tags": ["Redis"],
            "role_tags": ["后端"],
            "answer_points": ["互斥锁", "逻辑过期"],
            "source": "个人笔记",
        }])
        second = service.import_items(1, [{
            "title": "Redis 缓存击穿",
            "question": "如何解决缓存击穿？",
            "tech_tags": ["Redis"],
            "role_tags": ["后端"],
            "answer_points": ["互斥锁", "逻辑过期", "热点预热"],
            "source": "更新笔记",
        }])
        service.import_items(1, [{
            "title": "CSS 布局",
            "question": "Flex 如何居中？",
            "tech_tags": ["CSS"],
            "role_tags": ["前端"],
        }])

        results = service.search(1, "缓存击穿", tech_tags=["Redis"], role_tags=["后端"])

        assert first == {"created": 1, "updated": 0, "skipped": 0, "failed": 0}
        assert second["updated"] == 1
        assert KnowledgeItem.query.count() == 2
        assert results[0]["title"] == "Redis 缓存击穿"
        assert results[0]["source"] == "更新笔记"
        assert len(results) <= 5


def test_seed_knowledge_is_deliverable_and_traceable():
    items = json.loads(Path("data/knowledge/sample-interview-basics.json").read_text(encoding="utf-8"))
    categories = {item["category"] for item in items}
    identities = {(item["title"], item["question"]) for item in items}

    assert len(items) >= 12
    assert len(identities) == len(items)
    assert {"行为面试", "项目表达", "Python", "数据库", "缓存", "计算机网络", "系统设计"} <= categories
    for item in items:
        assert item.get("source")
        assert item.get("core_conclusion")
        assert item.get("answer_points")
        assert item.get("is_enabled") is True
