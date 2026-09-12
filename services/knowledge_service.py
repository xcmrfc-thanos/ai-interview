"""Import and retrieve small personal interview knowledge collections."""

import hashlib
import json
import re
from pathlib import Path

from models import db
from models.KnowledgeItem import KnowledgeItem


SUPPORTED_SUFFIXES = {".json", ".md", ".markdown", ".txt"}
LIST_FIELDS = ("role_tags", "tech_tags", "answer_points", "follow_ups", "pitfalls")


def parse_knowledge_file(path):
    source_path = Path(path)
    if source_path.suffix.lower() not in SUPPORTED_SUFFIXES:
        raise ValueError("仅支持 JSON、Markdown 和 TXT 知识文件")
    content = source_path.read_text(encoding="utf-8").strip()
    if not content:
        return []
    suffix = source_path.suffix.lower()
    if suffix == ".json":
        value = json.loads(content)
        items = value if isinstance(value, list) else value.get("items", [value])
        if not all(isinstance(item, dict) for item in items):
            raise ValueError("JSON 知识文件必须包含对象列表")
        return items
    if suffix in {".md", ".markdown"}:
        return _parse_markdown(content, source_path.name)
    return [{
        "title": source_path.stem,
        "question": source_path.stem,
        "standard_answer": content,
        "source": source_path.name,
    }]


class KnowledgeService:
    def create_item(self, user_id, raw_item):
        data = _normalize_item(raw_item)
        content_hash = _content_hash(data)
        if KnowledgeItem.query.filter_by(user_id=user_id, content_hash=content_hash).first():
            raise ValueError("相同标题和问题的知识条目已存在")
        item = KnowledgeItem(user_id=user_id, content_hash=content_hash)
        _apply(item, data)
        db.session.add(item)
        db.session.commit()
        return _serialize(item)

    def update_item(self, item, changes):
        current = _serialize(item)
        editable = {
            "title", "question", "category", "difficulty", "role_tags", "tech_tags",
            "core_conclusion", "answer_points", "standard_answer", "follow_ups",
            "pitfalls", "source", "source_url", "is_enabled",
        }
        current.update({key: value for key, value in changes.items() if key in editable})
        data = _normalize_item(current)
        content_hash = _content_hash(data)
        duplicate = KnowledgeItem.query.filter_by(
            user_id=item.user_id,
            content_hash=content_hash,
        ).first()
        if duplicate and duplicate.item_id != item.item_id:
            raise ValueError("相同标题和问题的知识条目已存在")
        item.content_hash = content_hash
        _apply(item, data)
        db.session.commit()
        return _serialize(item)

    def import_file(self, user_id, path):
        items = parse_knowledge_file(path)
        source = Path(path).name
        for item in items:
            item.setdefault("source", source)
        return self.import_items(user_id, items)

    def import_items(self, user_id, items):
        stats = {"created": 0, "updated": 0, "skipped": 0, "failed": 0}
        for raw_item in items:
            try:
                data = _normalize_item(raw_item)
                content_hash = _content_hash(data)
                existing = KnowledgeItem.query.filter_by(
                    user_id=user_id,
                    content_hash=content_hash,
                ).first()
                if existing:
                    _apply(existing, data)
                    stats["updated"] += 1
                else:
                    item = KnowledgeItem(user_id=user_id, content_hash=content_hash)
                    _apply(item, data)
                    db.session.add(item)
                    stats["created"] += 1
            except (TypeError, ValueError, KeyError):
                stats["failed"] += 1
        db.session.commit()
        return stats

    def search(self, user_id, query, tech_tags=None, role_tags=None, difficulty=None, limit=5):
        candidates = KnowledgeItem.query.filter_by(user_id=user_id, is_enabled=True).all()
        scored = []
        for item in candidates:
            score = _score(item, query, tech_tags or [], role_tags or [], difficulty)
            if score > 0:
                scored.append((score, item))
        scored.sort(key=lambda pair: (-pair[0], pair[1].item_id))
        return [_serialize(item) for _, item in scored[: min(max(limit, 1), 5)]]

    @staticmethod
    def stats(user_id):
        query = KnowledgeItem.query.filter_by(user_id=user_id)
        return {
            "total": query.count(),
            "enabled": query.filter_by(is_enabled=True).count(),
            "disabled": query.filter_by(is_enabled=False).count(),
        }

    @staticmethod
    def serialize(item):
        return _serialize(item)


def _normalize_item(raw):
    if not isinstance(raw, dict):
        raise TypeError("知识条目必须是对象")
    title = str(raw.get("title", "")).strip()
    question = str(raw.get("question", title)).strip()
    if not title or not question:
        raise ValueError("知识条目缺少标题或问题")
    data = {
        "title": title,
        "question": question,
        "category": _text(raw.get("category")),
        "difficulty": _text(raw.get("difficulty")),
        "core_conclusion": _text(raw.get("core_conclusion")),
        "standard_answer": _text(raw.get("standard_answer")),
        "source": _text(raw.get("source")),
        "source_url": _text(raw.get("source_url")),
        "is_enabled": bool(raw.get("is_enabled", True)),
    }
    for field in LIST_FIELDS:
        value = raw.get(field, [])
        data[field] = [str(item).strip() for item in value if str(item).strip()] if isinstance(value, list) else []
    return data


def _apply(item, data):
    for field, value in data.items():
        setattr(item, field, value)


def _content_hash(data):
    identity = f"{data['title'].casefold()}\n{data['question'].casefold()}"
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


def _score(item, query, tech_tags, role_tags, difficulty):
    terms = [term.casefold() for term in re.findall(r"[\w\u4e00-\u9fff]+", query or "") if term]
    title = item.title.casefold()
    question = item.question.casefold()
    body = " ".join([item.core_conclusion or "", item.standard_answer or ""]).casefold()
    score = sum(8 for term in terms if term in title)
    score += sum(10 for term in terms if term in question)
    score += sum(2 for term in terms if term in body)
    item_tech = {tag.casefold() for tag in item.tech_tags or []}
    item_roles = {tag.casefold() for tag in item.role_tags or []}
    score += 6 * len(item_tech & {tag.casefold() for tag in tech_tags})
    score += 4 * len(item_roles & {tag.casefold() for tag in role_tags})
    if difficulty and item.difficulty == difficulty:
        score += 2
    if not terms and not tech_tags and not role_tags and not difficulty:
        score = 1
    return score


def _serialize(item):
    return {
        "item_id": item.item_id,
        "title": item.title,
        "question": item.question,
        "category": item.category,
        "difficulty": item.difficulty,
        "role_tags": item.role_tags or [],
        "tech_tags": item.tech_tags or [],
        "core_conclusion": item.core_conclusion,
        "answer_points": item.answer_points or [],
        "standard_answer": item.standard_answer,
        "follow_ups": item.follow_ups or [],
        "pitfalls": item.pitfalls or [],
        "source": item.source,
        "source_url": item.source_url,
        "is_enabled": item.is_enabled,
        "created_at": item.created_at.isoformat() if item.created_at else None,
        "updated_at": item.updated_at.isoformat() if item.updated_at else None,
    }


def _parse_markdown(content, source):
    matches = list(re.finditer(r"^##\s+(.+?)\s*$", content, re.MULTILINE))
    if not matches:
        title_match = re.search(r"^#\s+(.+?)\s*$", content, re.MULTILINE)
        title = title_match.group(1) if title_match else Path(source).stem
        return [{"title": title, "question": title, "standard_answer": content, "source": source}]
    items = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(content)
        title = match.group(1).strip()
        answer = content[match.end():end].strip()
        items.append({"title": title, "question": title, "standard_answer": answer, "source": source})
    return items


def _text(value):
    text = str(value or "").strip()
    return text or None
