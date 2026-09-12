package com.aiinterview.server.web;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.db.MockDao;
import com.aiinterview.server.db.PlanDao;
import com.aiinterview.server.db.ResumeDao;
import com.aiinterview.server.svc.Llm;
import com.aiinterview.server.svc.MockInterviewService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;
import tech.smartboot.feat.router.Router;

import java.util.Map;

/** 模拟面试路由（对应 routes/route_mock_interview.py）。 */
public final class MockRoutes {

    private static volatile MockInterviewService service;

    private MockRoutes() {
    }

    public static void init(AppConfig config) {
        if (config.llmApiKey() != null && !config.llmApiKey().trim().isEmpty()) {
            service = new MockInterviewService(new Llm());
        }
    }

    public static void register(Router router) {
        RouteSupport.route(router, "/mock-interviews", "POST", (ctx) -> handleCreate(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/mock-interviews/:interviewId", "GET", (ctx) -> handleGet(ctx.Request, ctx.pathParam("interviewId"), ctx.Response));
        RouteSupport.route(router, "/mock-interviews/:interviewId/answers", "POST", (ctx) -> handleAnswer(ctx.Request, ctx.pathParam("interviewId"), ctx.Response));
        RouteSupport.route(router, "/mock-interviews/:interviewId/end", "POST", (ctx) -> handleEnd(ctx.Request, ctx.pathParam("interviewId"), ctx.Response));
    }

    private static void handleCreate(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        if (service == null) {
            RouteSupport.error(response, 503, "未配置 LLM_API_KEY，无法生成题目");
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
        Object resumeId = plan.get("resume_id");
        Map<String, Object> resume = resumeId == null ? null
            : ResumeDao.findForUser(((Number) resumeId).longValue(), session.userId);
        if (resume == null) {
            RouteSupport.error(response, 409, "面试计划未关联可用简历");
            return;
        }
        JSONObject resumePayload = RouteSupport.parsePayload(resume.get("parsed_data"));
        JSONArray outline = service.generateOutline(plan, resumePayload);
        long interviewId = MockDao.createInterview(session.userId, planId, outline.toJSONString());
        MockDao.createTurn(interviewId, 1, outline.getJSONObject(0).getString("question"));
        JSONObject body = new JSONObject();
        body.put("success", true);
        body.put("interview", interviewDict(MockDao.findInterview(interviewId, session.userId), false));
        RouteSupport.json(response, 201, body);
    }

    private static void handleGet(HttpRequest request, String interviewIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long interviewId = RouteSupport.parseLong(interviewIdParam);
        Map<String, Object> interview = MockDao.findInterview(interviewId, session.userId);
        if (interview == null) {
            RouteSupport.error(response, 404, "模拟面试不存在");
            return;
        }
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("interview", interviewDict(interview, true)).toJSONString());
    }

    private static void handleAnswer(HttpRequest request, String interviewIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        if (service == null) {
            RouteSupport.error(response, 503, "未配置 LLM_API_KEY");
            return;
        }
        long interviewId = RouteSupport.parseLong(interviewIdParam);
        Map<String, Object> interview = MockDao.findInterview(interviewId, session.userId);
        if (interview == null) {
            RouteSupport.error(response, 404, "模拟面试不存在");
            return;
        }
        if (!"running".equals(String.valueOf(interview.get("status")))) {
            RouteSupport.error(response, 409, "模拟面试已结束");
            return;
        }
        JSONObject data = RouteSupport.parseBody(request);
        String answer = data.getString("answer");
        if (answer == null || answer.trim().isEmpty()) {
            RouteSupport.error(response, 400, "回答不能为空");
            return;
        }
        int turnNumber = ((Number) interview.get("current_turn_number")).intValue();
        Map<String, Object> turn = MockDao.findTurn(interviewId, turnNumber);
        if (turn == null) {
            RouteSupport.error(response, 409, "当前轮次不存在，请重新开始模拟面试");
            return;
        }
        long planId = ((Number) interview.get("plan_id")).longValue();
        Map<String, Object> plan = PlanDao.findPlanAnyUser(planId);
        Object resumeId = plan == null ? null : plan.get("resume_id");
        Map<String, Object> resume = resumeId == null ? null : ResumeDao.findById(((Number) resumeId).longValue());
        JSONObject resumePayload = RouteSupport.parsePayload(resume == null ? null : resume.get("parsed_data"));
        JSONObject evaluation = service.evaluateAnswer(
            String.valueOf(turn.get("question")), answer, resumePayload,
            plan == null ? "" : String.valueOf(plan.get("job_description")));
        long turnId = ((Number) turn.get("turn_id")).longValue();
        if (evaluation.getBoolean("retryable") == Boolean.TRUE) {
            MockDao.updateTurn(turnId, answer, null, null, null, null, "evaluation_failed");
            JSONObject body = new JSONObject();
            body.put("success", false);
            body.put("evaluation", evaluation);
            body.put("message", "评分解析失败，可重试");
            // 直接写 body（已含 success/evaluation/message），不走 error() 避免二次 JSON 转义
            RouteSupport.json(response, 503, body);
            return;
        }
        // 三步写操作包进同一事务（updateTurn → 推进轮次/新建 turn），失败整体回滚
        final JSONArray outline = JSON.parseArray(String.valueOf(interview.get("question_outline")));
        com.aiinterview.server.db.MyBatis.tx(s -> {
            MockDao.updateTurn(s, turnId, answer,
                String.valueOf(evaluation.getJSONArray("reference_points")),
                String.valueOf(evaluation.getJSONObject("scores")),
                String.valueOf(evaluation.getJSONArray("strengths")),
                String.valueOf(evaluation.getJSONArray("improvements")), "completed");
            if (turnNumber < outline.size()) {
                MockDao.updateInterview(s, interviewId, "running", turnNumber + 1);
                MockDao.createTurn(s, interviewId, turnNumber + 1, outline.getJSONObject(turnNumber).getString("question"));
            } else {
                MockDao.updateInterview(s, interviewId, "completed", turnNumber);
            }
        });
        interview = MockDao.findInterview(interviewId, session.userId);
        JSONObject body = new JSONObject();
        body.put("success", true);
        body.put("evaluation", evaluation);
        body.put("interview", interviewDict(interview, true));
        RouteSupport.ok(response, body.toJSONString());
    }

    private static void handleEnd(HttpRequest request, String interviewIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long interviewId = RouteSupport.parseLong(interviewIdParam);
        Map<String, Object> interview = MockDao.findInterview(interviewId, session.userId);
        if (interview == null) {
            RouteSupport.error(response, 404, "模拟面试不存在");
            return;
        }
        if (!"ended".equals(String.valueOf(interview.get("status")))) {
            MockDao.updateInterview(interviewId, "ended", ((Number) interview.get("current_turn_number")).intValue());
        }
        interview = MockDao.findInterview(interviewId, session.userId);
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("interview", interviewDict(interview, true)).toJSONString());
    }

    private static JSONObject interviewDict(Map<String, Object> interview, boolean includeTurns) {
        JSONObject obj = RouteSupport.toJson(interview);
        if (includeTurns) {
            JSONArray turns = new JSONArray();
            long interviewId = ((Number) interview.get("mock_interview_id")).longValue();
            for (Map<String, Object> turn : MockDao.listTurns(interviewId)) {
                turns.add(RouteSupport.toJson(turn));
            }
            obj.put("turns", turns);
        }
        return obj;
    }
}
