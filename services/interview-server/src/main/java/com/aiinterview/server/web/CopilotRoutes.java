package com.aiinterview.server.web;

import com.aiinterview.server.db.CopilotDao;
import com.aiinterview.server.db.PlanDao;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;
import tech.smartboot.feat.router.Router;

import java.util.Map;

/** Copilot 会话 API（对应 routes/route_copilot.py）。 */
public final class CopilotRoutes {

    private CopilotRoutes() {
    }

    public static void register(Router router) {
        RouteSupport.route(router, "/copilot/sessions", "POST", (ctx) -> handleCreate(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/copilot/sessions/:sessionId", "GET", (ctx) -> handleGet(ctx.Request, ctx.pathParam("sessionId"), ctx.Response));
        RouteSupport.route(router, "/copilot/sessions/:sessionId/pause", "POST", (ctx) -> handlePause(ctx.Request, ctx.pathParam("sessionId"), ctx.Response));
        RouteSupport.route(router, "/copilot/sessions/:sessionId/resume", "POST", (ctx) -> handleResume(ctx.Request, ctx.pathParam("sessionId"), ctx.Response));
        RouteSupport.route(router, "/copilot/sessions/:sessionId/end", "POST", (ctx) -> handleEnd(ctx.Request, ctx.pathParam("sessionId"), ctx.Response));
    }

    private static void handleCreate(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        JSONObject data = RouteSupport.parseBody(request);
        Long planId = data.getLong("plan_id");
        if (planId == null) {
            RouteSupport.error(response, 400, "缺少有效的面试计划 ID");
            return;
        }
        Map<String, Object> plan = PlanDao.findPlan(planId, session.userId);
        if (plan == null) {
            RouteSupport.error(response, 404, "计划不存在");
            return;
        }
        if (!"active".equals(String.valueOf(plan.get("status"))) || plan.get("resume_id") == null) {
            RouteSupport.error(response, 409, "面试计划未启用或未关联简历");
            return;
        }
        Map<String, Object> pack = PlanDao.latestPack(planId);
        long copilotSessionId = CopilotDao.createSession(session.userId, planId,
            ((Number) plan.get("resume_id")).longValue(),
            pack == null ? null : ((Number) pack.get("pack_id")).longValue());
        JSONObject body = new JSONObject();
        body.put("success", true);
        body.put("session", sessionDict(CopilotDao.findSession(copilotSessionId, session.userId), false));
        if (pack == null || !"confirmed".equals(String.valueOf(pack.get("status")))
            || "outdated".equals(String.valueOf(plan.get("preparation_status")))) {
            JSONObject warning = new JSONObject();
            warning.put("code", "PREPARATION_OUTDATED");
            warning.put("message", "准备包尚未确认或已过期，可以继续启动，但回答上下文可能不完整。");
            body.put("warning", warning);
        }
        RouteSupport.json(response, 201, body);
    }

    private static void handleGet(HttpRequest request, String sessionIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> copilotSession = CopilotDao.findSession(RouteSupport.parseLong(sessionIdParam), session.userId);
        if (copilotSession == null) {
            RouteSupport.error(response, 404, "会话不存在");
            return;
        }
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("session", sessionDict(copilotSession, true)).toJSONString());
    }

    private static void handlePause(HttpRequest request, String sessionIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long sessionId = RouteSupport.parseLong(sessionIdParam);
        Map<String, Object> copilotSession = CopilotDao.findSession(sessionId, session.userId);
        if (copilotSession == null) {
            RouteSupport.error(response, 404, "会话不存在");
            return;
        }
        if (!"ended".equals(String.valueOf(copilotSession.get("status")))) {
            CopilotDao.updateSessionStatus(sessionId, "paused");
        }
        copilotSession = CopilotDao.findSession(sessionId, session.userId);
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true).fluentPut("session", sessionDict(copilotSession, false)).toJSONString());
    }

    private static void handleResume(HttpRequest request, String sessionIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long sessionId = RouteSupport.parseLong(sessionIdParam);
        Map<String, Object> copilotSession = CopilotDao.findSession(sessionId, session.userId);
        if (copilotSession == null) {
            RouteSupport.error(response, 404, "会话不存在");
            return;
        }
        if ("ended".equals(String.valueOf(copilotSession.get("status")))) {
            RouteSupport.error(response, 409, "已结束的会话不能继续");
            return;
        }
        CopilotDao.updateSessionStatus(sessionId, "running");
        copilotSession = CopilotDao.findSession(sessionId, session.userId);
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true).fluentPut("session", sessionDict(copilotSession, false)).toJSONString());
    }

    private static void handleEnd(HttpRequest request, String sessionIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long sessionId = RouteSupport.parseLong(sessionIdParam);
        Map<String, Object> copilotSession = CopilotDao.findSession(sessionId, session.userId);
        if (copilotSession == null) {
            RouteSupport.error(response, 404, "会话不存在");
            return;
        }
        if (!"ended".equals(String.valueOf(copilotSession.get("status")))) {
            CopilotDao.updateSessionStatus(sessionId, "ended");
        }
        copilotSession = CopilotDao.findSession(sessionId, session.userId);
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true).fluentPut("session", sessionDict(copilotSession, false)).toJSONString());
    }

    private static JSONObject sessionDict(Map<String, Object> copilotSession, boolean includeTurns) {
        JSONObject obj = RouteSupport.toJson(copilotSession);
        if (includeTurns) {
            JSONArray turns = new JSONArray();
            long sessionId = ((Number) copilotSession.get("session_id")).longValue();
            for (Map<String, Object> turn : CopilotDao.listTurns(sessionId)) {
                turns.add(RouteSupport.toJson(turn));
            }
            obj.put("turns", turns);
        }
        return obj;
    }
}
