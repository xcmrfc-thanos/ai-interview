package com.aiinterview.server.web;

import com.aiinterview.server.db.PlanDao;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.common.multipart.Part;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;
import tech.smartboot.feat.router.Router;

import java.util.Map;

/** 知识库路由（对应 routes/route_knowledge.py 的 API 部分）。 */
public final class KnowledgeRoutes {

    private KnowledgeRoutes() {
    }

    public static void register(Router router) {
        RouteSupport.route(router, "/knowledge", "GET", (ctx) -> handleSearch(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/knowledge", "POST", (ctx) -> handleCreate(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/knowledge/import", "POST", (ctx) -> handleImport(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/knowledge/rebuild-index", "POST", (ctx) -> handleRebuildIndex(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/knowledge/:itemId", "PATCH", (ctx) -> handleUpdate(ctx.Request, ctx.pathParam("itemId"), ctx.Response));
        RouteSupport.route(router, "/knowledge/:itemId", "DELETE", (ctx) -> handleDelete(ctx.Request, ctx.pathParam("itemId"), ctx.Response));
    }

    private static void handleSearch(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        String query = request.getParameter("q");
        String category = request.getParameter("category");
        JSONArray items = new JSONArray();
        for (Map<String, Object> row : PlanDao.listKnowledge(session.userId, query, category)) {
            items.add(RouteSupport.toJson(row));
        }
        RouteSupport.json(response, 200, new JSONObject().fluentPut("success", true).fluentPut("items", items));
    }

    private static void handleCreate(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        JSONObject data = RouteSupport.parseBody(request);
        String title = data.getString("title");
        if (title == null || title.trim().isEmpty()) {
            RouteSupport.error(response, 400, "缺少标题");
            return;
        }
        long itemId = PlanDao.createKnowledge(session.userId, data);
        Map<String, Object> row = PlanDao.findKnowledge(itemId, session.userId);
        RouteSupport.json(response, 200, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("item", RouteSupport.toJson(row)));
    }

    private static void handleUpdate(HttpRequest request, String itemIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long itemId = RouteSupport.parseLong(itemIdParam);
        if (PlanDao.findKnowledge(itemId, session.userId) == null) {
            RouteSupport.error(response, 404, "知识条目不存在");
            return;
        }
        JSONObject data = RouteSupport.parseBody(request);
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        for (String key : data.keySet()) {
            updates.put(key, data.get(key));
        }
        PlanDao.updateKnowledge(itemId, updates);
        Map<String, Object> row = PlanDao.findKnowledge(itemId, session.userId);
        RouteSupport.json(response, 200, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("item", RouteSupport.toJson(row)));
    }

    private static void handleDelete(HttpRequest request, String itemIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long itemId = RouteSupport.parseLong(itemIdParam);
        if (PlanDao.findKnowledge(itemId, session.userId) == null) {
            RouteSupport.error(response, 404, "知识条目不存在");
            return;
        }
        PlanDao.deleteKnowledge(itemId);
        RouteSupport.json(response, 200, "{\"success\":true}");
    }

    /** 导入 json/md/txt：JSON 走条目数组，文本按「# 标题 + 正文」分块；幂等以 title+question 哈希近似。 */
    private static void handleImport(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Part filePart = null;
        for (Part part : request.getParts()) {
            if ("file".equals(part.getName())) {
                filePart = part;
            }
        }
        if (filePart == null || filePart.getSubmittedFileName() == null) {
            RouteSupport.error(response, 400, "没有文件部分");
            return;
        }
        String text = cn.hutool.core.io.IoUtil.readUtf8(filePart.getInputStream());
        if (text.trim().isEmpty()) {
            RouteSupport.error(response, 400, "文件内容为空");
            return;
        }
        java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();
        String name = filePart.getSubmittedFileName().toLowerCase();
        if (name.endsWith(".json")) {
            Object parsed = com.alibaba.fastjson2.JSON.parse(text);
            if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                for (Object o : arr) {
                    entries.add(RouteSupport.parsePayload(o));
                }
            } else {
                JSONObject wrapper = RouteSupport.parsePayload(parsed);
                JSONArray items = wrapper.getJSONArray("items");
                if (items != null) {
                    for (Object o : items) {
                        entries.add(RouteSupport.parsePayload(o));
                    }
                }
            }
        } else {
            // Markdown/纯文本：按 `# ` 一级标题分块，块内首行为标题
            String[] blocks = text.split("(?m)^#\\s+");
            for (String block : blocks) {
                String trimmed = block.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int nl = trimmed.indexOf('\n');
                Map<String, Object> entry = new java.util.HashMap<>();
                String title = nl < 0 ? trimmed : trimmed.substring(0, nl).trim();
                String content = nl < 0 ? "" : trimmed.substring(nl + 1).trim();
                entry.put("title", title);
                entry.put("core_conclusion", content);
                entries.add(entry);
            }
        }
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (Map<String, Object> entry : entries) {
            String title = entry.get("title") == null ? null : String.valueOf(entry.get("title"));
            if (title == null || title.trim().isEmpty()) {
                failed++;
                continue;
            }
            // 内容字段归一化：content → core_conclusion（与导入服务字段名兼容）
            if (entry.get("core_conclusion") == null && entry.get("content") != null) {
                entry.put("core_conclusion", entry.get("content"));
            }
            if (entry.get("question") == null) {
                entry.put("question", "");
            }
            try {
                PlanDao.createKnowledge(session.userId, entry);
                created++;
            } catch (Exception e) {
                skipped++;
            }
        }
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("result", new JSONObject()
                .fluentPut("created", created)
                .fluentPut("updated", 0)
                .fluentPut("skipped", skipped)
                .fluentPut("failed", failed))
            .toJSONString());
    }

    /** 检索为直查列实现，无需独立倒排索引：重建 = 统计并返回。 */
    private static void handleRebuildIndex(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        int total = PlanDao.listKnowledge(session.userId, null, null).size();
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("checked", total)
            .fluentPut("message", "索引已重建（共 " + total + " 条）")
            .toJSONString());
    }
}
