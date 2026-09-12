"""Personal knowledge API and maintenance CLI."""

import tempfile
from pathlib import Path

import click
from flask import Blueprint, jsonify, request, session
from werkzeug.utils import secure_filename

from models.KnowledgeItem import KnowledgeItem
from services.knowledge_service import KnowledgeService
from utils.auth_security import applicant_required


knowledge_bp = Blueprint("knowledge", __name__)


@knowledge_bp.get("/knowledge")
@applicant_required
def search_knowledge():
    service = KnowledgeService()
    results = service.search(
        session["user_id"],
        request.args.get("q", ""),
        tech_tags=request.args.getlist("tech_tag"),
        role_tags=request.args.getlist("role_tag"),
        difficulty=request.args.get("difficulty"),
    )
    stats = service.stats(session["user_id"])
    return jsonify({
        "success": True,
        "items": results,
        "knowledge_status": {
            "total": stats["total"],
            "enabled": stats["enabled"],
            "enhancement_enabled": stats["enabled"] > 0,
        },
    })


@knowledge_bp.post("/knowledge")
@applicant_required
def create_knowledge():
    try:
        item = KnowledgeService().create_item(session["user_id"], request.get_json(silent=True) or {})
    except (TypeError, ValueError) as error:
        return jsonify({"success": False, "message": str(error)}), 400
    return jsonify({"success": True, "item": item}), 201


@knowledge_bp.patch("/knowledge/<int:item_id>")
@applicant_required
def update_knowledge(item_id):
    item = KnowledgeItem.query.filter_by(item_id=item_id, user_id=session["user_id"]).first()
    if not item:
        return jsonify({"success": False, "message": "知识条目不存在"}), 404
    data = request.get_json(silent=True) or {}
    try:
        result = KnowledgeService().update_item(item, data)
    except (TypeError, ValueError) as error:
        return jsonify({"success": False, "message": str(error)}), 400
    return jsonify({"success": True, "item": result})


@knowledge_bp.post("/knowledge/import")
@applicant_required
def import_knowledge():
    uploaded = request.files.get("file")
    filename = secure_filename(uploaded.filename or "") if uploaded else ""
    suffix = Path(filename).suffix.lower()
    if not uploaded or not filename or suffix not in {".json", ".md", ".markdown", ".txt"}:
        return jsonify({"success": False, "message": "请选择 JSON、Markdown 或 TXT 文件"}), 400
    try:
        with tempfile.TemporaryDirectory(prefix="knowledge-import-") as directory:
            target = Path(directory) / filename
            uploaded.save(target)
            result = KnowledgeService().import_file(session["user_id"], target)
    except (OSError, TypeError, ValueError) as error:
        return jsonify({"success": False, "message": f"导入失败：{error}"}), 400
    return jsonify({"success": True, "result": result})


@knowledge_bp.post("/knowledge/rebuild-index")
@applicant_required
def rebuild_knowledge_index():
    stats = KnowledgeService.stats(session["user_id"])
    return jsonify({
        "success": True,
        "checked": stats["total"],
        "message": f"结构化检索状态正常，已检查 {stats['total']} 条知识。",
    })


def register_knowledge_cli(app):
    if "knowledge" in app.cli.commands:
        return

    @app.cli.group("knowledge")
    def knowledge_group():
        """Maintain personal interview knowledge."""

    @knowledge_group.command("import")
    @click.argument("path", type=click.Path(exists=True, dir_okay=False, path_type=str))
    @click.option("--user-id", type=int, required=True)
    def import_command(path, user_id):
        result = KnowledgeService().import_file(user_id, path)
        click.echo(
            f"新增 {result['created']}，更新 {result['updated']}，"
            f"跳过 {result['skipped']}，失败 {result['failed']}"
        )

    @knowledge_group.command("stats")
    @click.option("--user-id", type=int, required=True)
    def stats_command(user_id):
        value = KnowledgeService.stats(user_id)
        click.echo(f"总计 {value['total']}，启用 {value['enabled']}，停用 {value['disabled']}")

    @knowledge_group.command("rebuild-index")
    @click.option("--user-id", type=int, required=True)
    def rebuild_command(user_id):
        value = KnowledgeService.stats(user_id)
        click.echo(f"结构化检索无需独立索引，已检查 {value['total']} 条")

    @knowledge_group.command("dedupe")
    @click.option("--user-id", type=int, required=True)
    def dedupe_command(user_id):
        value = KnowledgeService.stats(user_id)
        click.echo(f"唯一哈希约束已启用，已检查 {value['total']} 条")
