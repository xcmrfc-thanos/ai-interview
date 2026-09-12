package com.aiinterview.server.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.common.HttpStatus;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;

import java.util.Map;

/**
 * 路由公共层：7 个 Routes 类共用的 JSON 解析/响应/鉴权工具。
 * error(...) 只接受纯文案，杜绝「JSON 串被二次转义」这类 bug（见 BE-BUG-1）。
 */
public final class RouteSupport {

    private RouteSupport() {
    }

    /** 解析请求体为 JSONObject，空体/非法体返回空对象（与历史行为一致）。 */
    public static JSONObject parseBody(HttpRequest request) {
        try {
            String body = cn.hutool.core.io.IoUtil.readUtf8(request.getInputStream());
            return body == null || body.trim().isEmpty() ? new JSONObject() : JSONObject.parseObject(body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 解析 long 参数，失败返回 -1。 */
    public static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 200 + JSON（自动带 Content-Type）。 */
    public static void ok(HttpResponse response, String body) throws Throwable {
        json(response, 200, body);
    }

    /** 指定状态码 + JSON 字符串。 */
    public static void json(HttpResponse response, int status, String body) throws Throwable {
        response.setHttpStatus(HttpStatus.valueOf(status));
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        response.write(body);
    }

    /** 指定状态码 + JSONObject。 */
    public static void json(HttpResponse response, int status, JSONObject body) throws Throwable {
        json(response, status, body.toJSONString());
    }

    /**
     * 错误响应：message 只接受纯文案，内部负责 JSON 转义。
     * 需要返回结构化 body（如 success/evaluation/message）时请用 json(...)。
     */
    public static void error(HttpResponse response, int status, String message) throws Throwable {
        json(response, status, "{\"success\":false,\"message\":" + JSON.toJSONString(message) + "}");
    }

    /** Map → JSONObject（替换手写 entry 循环）。 */
    public static JSONObject toJson(Map<String, Object> map) {
        return new JSONObject(map);
    }

    /** 容错解析 JSON 字段：String/Map/其他 → JSONObject。 */
    public static JSONObject parsePayload(Object value) {
        if (value instanceof String) {
            try {
                return JSONObject.parseObject((String) value);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
        if (value instanceof Map) {
            return new JSONObject((Map<String, Object>) value);
        }
        return new JSONObject();
    }

    /** 注册路由并同时注册 /api 前缀别名（契约 §0：REST 统一 /api 前缀；已是 /api 的路径不重复）。 */
    public static void route(tech.smartboot.feat.router.Router router, String path, String method,
                             tech.smartboot.feat.router.RouterHandler handler) {
        router.route(path, method, handler);
        if (!path.startsWith("/api/") && !"/api".equals(path)) {
            router.route("/api" + path, method, handler);
        }
    }

    /** GET 路由 + /api 别名。 */
    public static void route(tech.smartboot.feat.router.Router router, String path,
                             tech.smartboot.feat.router.RouterHandler handler) {
        route(router, path, "GET", handler);
    }

    /**
     * 会话鉴权：未登录时写好 401 并返回 null。
     * 调用方：{@code SessionStore.Session s = RouteSupport.require(req, resp); if (s == null) return;}
     */
    public static SessionStore.Session require(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = AuthRoutes.current(request);
        if (session == null) {
            error(response, 401, "用户未登录");
            return null;
        }
        return session;
    }
}
