package com.aiinterview.server.web;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.db.MockDao;
import com.aiinterview.server.db.CopilotDao;
import com.aiinterview.server.db.PlanDao;
import com.aiinterview.server.db.ReviewDao;
import com.aiinterview.server.svc.Llm;
import com.aiinterview.server.svc.PreparationService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;
import tech.smartboot.feat.router.Router;
import tech.smartboot.feat.ai.chat.entity.Message;

import java.util.Arrays;
import java.util.Map;

/** 复盘 + 简历优化路由（对应 route_review.py / route_resume_optimize.py）。 */
public final class ReviewRoutes {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReviewRoutes.class);

    private static volatile Llm llm;

    private ReviewRoutes() {
    }

    public static void init(AppConfig config) {
        if (config.llmApiKey() != null && !config.llmApiKey().trim().isEmpty()) {
            llm = new Llm();
        }
    }

    public static void register(Router router) {
        RouteSupport.route(router, "/reviews/generate", "POST", (ctx) -> handleGenerate(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/reviews", "GET", (ctx) -> handleList(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/reviews/:reviewId", "GET", (ctx) -> handleGet(ctx.Request, ctx.pathParam("reviewId"), ctx.Response));
        RouteSupport.route(router, "/reviews/:reviewId/export", "GET", (ctx) -> handleExport(ctx.Request, ctx.pathParam("reviewId"), ctx.Response));
        RouteSupport.route(router, "/resume-optimizations", "POST", (ctx) -> handleGenerateOptimization(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/resume-optimizations/latest", "GET", (ctx) -> handleLatestOptimization(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/resume-optimizations/:optimizationId/confirm", "POST", (ctx) -> handleConfirmOptimization(ctx.Request, ctx.pathParam("optimizationId"), ctx.Response));
        RouteSupport.route(router, "/resume-optimizations/:optimizationId/suggestions/:suggestionId/confirm", "POST",
            (ctx) -> handleConfirmSuggestion(ctx.Request, ctx.pathParam("optimizationId"), ctx.pathParam("suggestionId"), ctx.Response));
    }

    private static void handleGenerate(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        if (llm == null) {
            RouteSupport.error(response, 503, "未配置 LLM_API_KEY");
            return;
        }
        JSONObject data = RouteSupport.parseBody(request);
        String sourceType = data.getString("source_type");
        Long sourceId = data.getLong("source_id");
        if (!("copilot".equals(sourceType) || "mock".equals(sourceType)) || sourceId == null) {
            RouteSupport.error(response, 400, "复盘来源无效");
            return;
        }
        Map<String, Object> existing = ReviewDao.findReviewBySource(session.userId, sourceType, sourceId);
        if (existing != null) {
            RouteSupport.ok(response, new JSONObject().fluentPut("success", true).fluentPut("review", RouteSupport.toJson(existing)).toJSONString());
            return;
        }
        long planId;
        String sourceData;
        if ("mock".equals(sourceType)) {
            Map<String, Object> interview = MockDao.findInterview(sourceId, session.userId);
            if (interview == null) {
                RouteSupport.error(response, 404, "模拟面试不存在");
                return;
            }
            planId = ((Number) interview.get("plan_id")).longValue();
            sourceData = buildMockSource(interview);
        } else {
            Map<String, Object> copilotSession = CopilotDao.findSession(sourceId, session.userId);
            if (copilotSession == null) {
                RouteSupport.error(response, 404, "Copilot 会话不存在");
                return;
            }
            planId = ((Number) copilotSession.get("plan_id")).longValue();
            sourceData = buildCopilotSource(copilotSession);
        }
        Map<String, Object> plan = PlanDao.findPlan(planId, session.userId);
        if (plan == null) {
            RouteSupport.error(response, 404, "计划不存在");
            return;
        }
        JSONObject result;
        try {
            String completion = llm.chat(Arrays.asList(
                Message.ofSystem("你是面试复盘专家。基于面试记录输出结构化复盘 JSON："
                    + "{\"summary\":\"总体总结\",\"question_categories\":[{\"category\":\"分类\",\"count\":数量}],"
                    + "\"scores\":{\"technical\":0-100,\"expression\":0-100,\"preparation\":0-100},"
                    + "\"fact_risks\":[\"事实风险\"],\"expression_issues\":[\"表达问题\"],"
                    + "\"weak_topics\":[\"薄弱主题\"],\"next_actions\":[\"下一步行动\"],"
                    + "\"diagnostics\":{\"note\":\"说明\"}}"),
                Message.ofUser("岗位：" + plan.get("position_name") + "\n面试记录：\n" + sourceData)), 0.3);
            result = PreparationService.extractJson(completion);
        } catch (Exception e) {
            log.error("复盘生成失败", e);
            RouteSupport.error(response, 503, "复盘生成失败，请稍后重试");
            return;
        }
        long reviewId = ReviewDao.createReview(session.userId, planId, sourceType, sourceId,
            result.getString("summary"),
            String.valueOf(result.getJSONArray("question_categories")),
            String.valueOf(result.getJSONObject("scores")),
            String.valueOf(result.getJSONArray("fact_risks")),
            String.valueOf(result.getJSONArray("expression_issues")),
            String.valueOf(result.getJSONArray("weak_topics")),
            String.valueOf(result.getJSONArray("next_actions")),
            String.valueOf(result.getJSONObject("diagnostics")));
        Map<String, Object> review = ReviewDao.findReview(reviewId, session.userId);
        RouteSupport.json(response, 201, new JSONObject().fluentPut("success", true).fluentPut("review", RouteSupport.toJson(review)));
    }

    private static void handleList(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Long planId = parseLongOrNull(request.getParameter("plan_id"));
        String sourceType = request.getParameter("source_type");
        JSONArray list = new JSONArray();
        for (Map<String, Object> review : ReviewDao.listReviews(session.userId, planId, sourceType)) {
            list.add(RouteSupport.toJson(review));
        }
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true).fluentPut("reviews", list).toJSONString());
    }

    private static void handleGet(HttpRequest request, String reviewIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> review = ReviewDao.findReview(RouteSupport.parseLong(reviewIdParam), session.userId);
        if (review == null) {
            RouteSupport.error(response, 404, "复盘不存在");
            return;
        }
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true).fluentPut("review", RouteSupport.toJson(review)).toJSONString());
    }

    private static void handleExport(HttpRequest request, String reviewIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> review = ReviewDao.findReview(RouteSupport.parseLong(reviewIdParam), session.userId);
        if (review == null) {
            RouteSupport.error(response, 404, "复盘不存在");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("面试复盘\n");
        text.append("总结：").append(review.get("summary")).append('\n');
        text.append("分数：").append(review.get("scores")).append('\n');
        text.append("薄弱主题：").append(review.get("weak_topics")).append('\n');
        text.append("下一步：").append(review.get("next_actions")).append('\n');
        response.setHeader("Content-Type", "text/plain; charset=utf-8");
        response.setHeader("Content-Disposition", "attachment; filename=review.txt");
        response.write(text.toString());
    }

    private static void handleGenerateOptimization(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        if (llm == null) {
            RouteSupport.error(response, 503, "未配置 LLM_API_KEY");
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
            : com.aiinterview.server.db.ResumeDao.findForUser(((Number) resumeId).longValue(), session.userId);
        if (resume == null) {
            RouteSupport.error(response, 409, "面试计划未关联可用简历");
            return;
        }
        JSONObject result;
        try {
            String completion = llm.chat(Arrays.asList(
                Message.ofSystem("你是简历优化专家。对照岗位 JD 分析简历匹配度并输出 JSON："
                    + "{\"match_analysis\":\"匹配分析\",\"gaps\":[{\"gap\":\"缺口\",\"suggestion\":\"修改建议\"}],"
                    + "\"keywords\":[\"应补充关键词\"],\"summary\":\"总体结论\"}"),
                Message.ofUser("岗位：" + plan.get("position_name") + "\nJD：" + plan.get("job_description")
                    + "\n简历：" + String.valueOf(resume.get("parsed_data")))), 0.3);
            result = PreparationService.extractJson(completion);
        } catch (Exception e) {
            log.error("简历优化生成失败", e);
            RouteSupport.error(response, 503, "简历优化生成失败，请稍后重试");
            return;
        }
        long optimizationId = ReviewDao.createOptimization(session.userId, planId,
            ((Number) resume.get("resume_id")).longValue(), result.toJSONString());
        Map<String, Object> optimization = ReviewDao.findOptimization(optimizationId, session.userId);
        RouteSupport.json(response, 201, new JSONObject().fluentPut("success", true)
            .fluentPut("optimization", optimizationDict(optimization)));
    }

    private static void handleLatestOptimization(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Long planId = parseLongOrNull(request.getParameter("plan_id"));
        Map<String, Object> optimization = planId == null ? null
            : ReviewDao.latestOptimization(planId, session.userId);
        if (optimization == null) {
            RouteSupport.error(response, 404, "暂无简历优化结果");
            return;
        }
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true)
            .fluentPut("optimization", optimizationDict(optimization)).toJSONString());
    }

    private static void handleConfirmOptimization(HttpRequest request, String optimizationIdParam,
                                                  HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> optimization = ReviewDao.findOptimization(RouteSupport.parseLong(optimizationIdParam), session.userId);
        if (optimization == null) {
            RouteSupport.error(response, 404, "优化结果不存在");
            return;
        }
        RouteSupport.ok(response, "{\"success\":true}");
    }

    /** 单条改写建议确认：把 suggestion_id 追加进 resume_optimizations.confirmed_suggestion_ids。 */
    private static void handleConfirmSuggestion(HttpRequest request, String optimizationIdParam,
                                                String suggestionId, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> optimization = ReviewDao.findOptimization(RouteSupport.parseLong(optimizationIdParam), session.userId);
        if (optimization == null) {
            RouteSupport.error(response, 404, "优化结果不存在");
            return;
        }
        if (suggestionId == null || suggestionId.trim().isEmpty()) {
            RouteSupport.error(response, 400, "缺少 suggestion_id");
            return;
        }
        JSONArray confirmed = confirmedIdsOf(optimization);
        if (!confirmed.contains(suggestionId)) {
            confirmed.add(suggestionId);
            ReviewDao.updateConfirmedSuggestions(
                ((Number) optimization.get("optimization_id")).longValue(), confirmed.toJSONString());
        }
        Map<String, Object> updated = ReviewDao.findOptimization(
            ((Number) optimization.get("optimization_id")).longValue(), session.userId);
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true)
            .fluentPut("optimization", optimizationDict(updated)).toJSONString());
    }

    /** 优先取列存储，兼容旧数据回退到 result JSON 内字段。 */
    private static JSONArray confirmedIdsOf(Map<String, Object> optimization) {
        Object column = optimization.get("confirmed_suggestion_ids");
        if (column != null && !String.valueOf(column).trim().isEmpty()) {
            try {
                JSONArray parsed = com.alibaba.fastjson2.JSON.parseArray(String.valueOf(column));
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // 落到 result JSON 兜底
            }
        }
        JSONObject result = RouteSupport.parsePayload(optimization.get("result"));
        JSONArray fromResult = result.getJSONArray("confirmed_suggestion_ids");
        return fromResult == null ? new JSONArray() : fromResult;
    }

    /** 存储结果 → 契约 optimization 形状（matches/gaps/keywords/suggestions/confirmed_suggestion_ids）。 */
    private static JSONObject optimizationDict(Map<String, Object> optimization) {
        JSONObject result = RouteSupport.parsePayload(optimization.get("result"));
        JSONObject out = new JSONObject();
        out.put("optimization_id", optimization.get("optimization_id"));
        out.put("plan_id", optimization.get("plan_id"));
        out.put("status", optimization.get("status"));
        JSONArray suggestions = new JSONArray();
        JSONArray gaps = result.getJSONArray("gaps");
        JSONArray gapTexts = new JSONArray();
        if (gaps != null) {
            for (int i = 0; i < gaps.size(); i++) {
                JSONObject gap = gaps.getJSONObject(i);
                if (gap == null) {
                    continue;
                }
                String gapText = gap.getString("gap");
                if (gapText != null) {
                    gapTexts.add(gapText);
                }
                JSONObject sug = new JSONObject();
                sug.put("suggestion_id", "sug-" + i);
                sug.put("original", gapText);
                sug.put("rewritten", gap.getString("suggestion"));
                sug.put("reason", gapText);
                suggestions.add(sug);
            }
        }
        JSONArray matches = new JSONArray();
        String matchAnalysis = result.getString("match_analysis");
        if (matchAnalysis != null && !matchAnalysis.trim().isEmpty()) {
            matches.add(matchAnalysis);
        }
        out.put("summary", result.getString("summary"));
        out.put("matches", matches);
        out.put("gaps", gapTexts);
        out.put("keywords", result.getJSONArray("keywords") == null ? new JSONArray() : result.getJSONArray("keywords"));
        out.put("suggestions", suggestions);
        out.put("confirmed_suggestion_ids", confirmedIdsOf(optimization));
        return out;
    }

    private static String buildMockSource(Map<String, Object> interview) {
        StringBuilder sb = new StringBuilder();
        long interviewId = ((Number) interview.get("mock_interview_id")).longValue();
        for (Map<String, Object> turn : MockDao.listTurns(interviewId)) {
            sb.append("问：").append(turn.get("question")).append('\n');
            sb.append("答：").append(String.valueOf(turn.get("answer") == null ? "" : turn.get("answer"))).append('\n');
            sb.append("评分：").append(String.valueOf(turn.get("scores"))).append('\n');
        }
        return sb.toString();
    }

    private static String buildCopilotSource(Map<String, Object> copilotSession) {
        StringBuilder sb = new StringBuilder();
        long sessionId = ((Number) copilotSession.get("session_id")).longValue();
        for (Map<String, Object> turn : CopilotDao.listTurns(sessionId)) {
            if (turn.get("transcript") == null || String.valueOf(turn.get("transcript")).isEmpty()) {
                continue;
            }
            sb.append("问：").append(turn.get("transcript")).append('\n');
            sb.append("回答要点：").append(String.valueOf(turn.get("answer_points"))).append('\n');
        }
        return sb.toString();
    }

    private static Long parseLongOrNull(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
