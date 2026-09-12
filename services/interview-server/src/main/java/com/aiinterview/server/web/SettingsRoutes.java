package com.aiinterview.server.web;

import com.aiinterview.server.svc.LlmSettingsService;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;
import tech.smartboot.feat.router.Router;

/** 模型设置路由：GET/PUT /api/settings/llm（登录保护，契约与 Python route_settings.py 对齐）。 */
public final class SettingsRoutes {

    private SettingsRoutes() {
    }

    public static void register(Router router) {
        RouteSupport.route(router, "/api/settings/llm", "GET", (ctx) -> handleGet(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/api/settings/llm", "PUT", (ctx) -> handlePut(ctx.Request, ctx.Response));
    }

    static void handleGet(HttpRequest request, HttpResponse response) throws Throwable {
        if (RouteSupport.require(request, response) == null) {
            return;
        }
        JSONObject body = new JSONObject();
        body.put("success", true);
        body.put("config", LlmSettingsService.getConfig());
        RouteSupport.ok(response, body.toJSONString());
    }

    static void handlePut(HttpRequest request, HttpResponse response) throws Throwable {
        if (RouteSupport.require(request, response) == null) {
            return;
        }
        JSONObject payload = RouteSupport.parseBody(request);
        try {
            LlmSettingsService.update(payload);
        } catch (IllegalArgumentException e) {
            RouteSupport.error(response, 400, e.getMessage());
            return;
        }
        RouteSupport.ok(response, "{\"success\":true}");
    }
}
