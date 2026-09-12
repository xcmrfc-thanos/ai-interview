package com.aiinterview.server.web;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.concurrent.BoundedExecutor;
import com.aiinterview.server.observability.ServerMetrics;
import com.aiinterview.server.db.PlanDao;
import com.aiinterview.server.db.ResumeDao;
import com.aiinterview.server.svc.Llm;
import com.aiinterview.server.svc.PreparationService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;
import tech.smartboot.feat.router.Router;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 面试计划 + 准备包路由（对应 routes/route_interview_plan.py）。 */
public final class PlanRoutes {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlanRoutes.class);

    private static final Set<String> PLAN_FIELDS = new HashSet<>(java.util.Arrays.asList(
        "resume_id", "company_name", "position_name", "job_description",
        "extra_requirements", "level", "tech_tags"));
    private static final Set<String> REQUIRED_FIELDS = new HashSet<>(java.util.Arrays.asList(
        "company_name", "position_name", "job_description"));

    private static volatile PreparationService preparationService;
    /** 准备包生成线程池：有界队列，满时拒绝任务。 */
    private static volatile BoundedExecutor PREP_EXECUTOR;

    /** 启动时按 AppConfig 初始化（线程数默认 2，见 PREP_EXECUTOR_THREADS）。 */
    public static void initExecutors(com.aiinterview.server.AppConfig config) {
        int threads = Math.max(1, config.prepExecutorThreads());
        PREP_EXECUTOR = new BoundedExecutor(threads, 8, "preparation-generator");
        ServerMetrics.registerExecutor("preparation-generator", PREP_EXECUTOR);
    }

    private PlanRoutes() {
    }

    public static void init(AppConfig config) {
        if (config.llmApiKey() != null && !config.llmApiKey().trim().isEmpty()) {
            preparationService = new PreparationService(new Llm());
        }
    }

    public static void register(Router router) {
        RouteSupport.route(router, "/interview-plans", "GET", (ctx) -> handleList(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/interview-plans", "POST", (ctx) -> handleCreate(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/interview-plans/:planId", "GET", (ctx) -> handleGet(ctx.Request, ctx.pathParam("planId"), ctx.Response));
        RouteSupport.route(router, "/interview-plans/:planId", "PATCH", (ctx) -> handleUpdate(ctx.Request, ctx.pathParam("planId"), ctx.Response));
        RouteSupport.route(router, "/interview-plans/:planId", "DELETE", (ctx) -> handleArchive(ctx.Request, ctx.pathParam("planId"), ctx.Response));
        RouteSupport.route(router, "/interview-plans/:planId/preparation", "POST", (ctx) -> handleGeneratePreparation(ctx.Request, ctx.pathParam("planId"), ctx.Response));
        RouteSupport.route(router, "/interview-plans/:planId/preparation/:packId", "PATCH", (ctx) -> handleUpdatePack(ctx.Request, ctx.pathParam("planId"), ctx.pathParam("packId"), ctx.Response));
        RouteSupport.route(router, "/interview-plans/:planId/preparation/:packId/sections/:field/regenerate", "POST",
            (ctx) -> handleRegenerateSection(ctx.Request, ctx.pathParam("planId"), ctx.pathParam("packId"), ctx.pathParam("field"), ctx.Response));
        RouteSupport.route(router, "/interview-plans/:planId/preparation/:packId/confirm", "POST", (ctx) -> handleConfirmPreparation(ctx.Request, ctx.pathParam("planId"), ctx.pathParam("packId"), ctx.Response));
    }

    private static void handleList(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        JSONArray plans = new JSONArray();
        for (Map<String, Object> plan : PlanDao.listPlans(session.userId, null)) {
            plans.add(planDict(plan, false));
        }
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true).fluentPut("plans", plans).toJSONString());
    }

    private static void handleCreate(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        JSONObject data = RouteSupport.parseBody(request);
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String field : REQUIRED_FIELDS) {
            if (!data.containsKey(field) || data.getString(field) == null || data.getString(field).trim().isEmpty()) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            RouteSupport.error(response, 400, "缺少字段: " + String.join(", ", missing));
            return;
        }
        Long resumeId = data.getLong("resume_id");
        if (resumeId != null && ResumeDao.findForUser(resumeId, session.userId) == null) {
            RouteSupport.error(response, 400, "简历不存在或无权访问");
            return;
        }
        long planId = PlanDao.createPlan(session.userId, resumeId,
            data.getString("company_name"), data.getString("position_name"),
            data.getString("job_description"), data.getString("extra_requirements"),
            data.getString("level"), jsonOrNull(data.get("tech_tags")));
        Map<String, Object> plan = PlanDao.findPlan(planId, session.userId);
        if (resumeId == null) {
            RouteSupport.ok(response, new JSONObject()
                .fluentPut("success", true)
                .fluentPut("plan", planDict(plan, false))
                .fluentPut("preparation", null)
                .fluentPut("next_action", "attach_resume").toJSONString());
            return;
        }
        startPreparation(planId);
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("plan", planDict(plan, false))
            .fluentPut("preparation", null)
            .fluentPut("next_action", "poll_preparation")
            .fluentPut("status", "generating").toJSONString());
    }

    private static void handleGet(HttpRequest request, String planIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> plan = PlanDao.findPlan(RouteSupport.parseLong(planIdParam), session.userId);
        if (plan == null) {
            RouteSupport.error(response, 404, "计划不存在");
            return;
        }
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("plan", planDict(plan, true)).toJSONString());
    }

    private static void handleUpdate(HttpRequest request, String planIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long planId = RouteSupport.parseLong(planIdParam);
        Map<String, Object> plan = PlanDao.findPlan(planId, session.userId);
        if (plan == null) {
            RouteSupport.error(response, 404, "计划不存在");
            return;
        }
        JSONObject data = RouteSupport.parseBody(request);
        if (data.getLong("resume_id") != null
            && ResumeDao.findForUser(data.getLong("resume_id"), session.userId) == null) {
            RouteSupport.error(response, 400, "简历不存在或无权访问");
            return;
        }
        boolean changed = false;
        for (String field : PLAN_FIELDS) {
            if (data.containsKey(field) && !String.valueOf(plan.get(field)).equals(String.valueOf(data.get(field)))) {
                changed = true;
                break;
            }
        }
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        for (String field : PLAN_FIELDS) {
            if (data.containsKey(field)) {
                updates.put(field, data.get(field) instanceof String
                    ? data.getString(field)
                    : JSON.toJSONString(data.get(field)));
            }
        }
        // 改字段 + 标记准备包 outdated 两步包进同一事务，失败整体回滚
        final boolean planChanged = changed;
        com.aiinterview.server.db.MyBatis.tx(s -> {
            PlanDao.updatePlanFields(s, planId, updates);
            if (planChanged) {
                PlanDao.setPlanPreparationStatus(s, planId, "outdated");
                Map<String, Object> pack = PlanDao.latestPack(planId);
                if (pack != null) {
                    PlanDao.markPacksOutdated(s, planId, ((Number) pack.get("pack_id")).longValue());
                }
            }
        });
        plan = PlanDao.findPlan(planId, session.userId);
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true).fluentPut("plan", planDict(plan, false)).toJSONString());
    }

    private static void handleArchive(HttpRequest request, String planIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long planId = RouteSupport.parseLong(planIdParam);
        Map<String, Object> plan = PlanDao.findPlan(planId, session.userId);
        if (plan == null) {
            RouteSupport.error(response, 404, "计划不存在");
            return;
        }
        PlanDao.archivePlan(planId);
        plan = PlanDao.findPlan(planId, session.userId);
        RouteSupport.ok(response, new JSONObject().fluentPut("success", true).fluentPut("plan", planDict(plan, false)).toJSONString());
    }

    private static void handleGeneratePreparation(HttpRequest request, String planIdParam, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long planId = RouteSupport.parseLong(planIdParam);
        Map<String, Object> plan = PlanDao.findPlan(planId, session.userId);
        if (plan == null) {
            RouteSupport.error(response, 404, "计划不存在");
            return;
        }
        Object resumeId = plan.get("resume_id");
        if (resumeId == null || ResumeDao.findForUser(((Number) resumeId).longValue(), session.userId) == null) {
            RouteSupport.error(response, 400, "请先关联一份本人简历，再生成准备包");
            return;
        }
        startPreparation(planId);
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("plan", planDict(plan, false))
            .fluentPut("next_action", "poll_preparation")
            .fluentPut("status", "generating").toJSONString());
    }

    private static void handleConfirmPreparation(HttpRequest request, String planIdParam, String packIdParam,
                                                 HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long planId = RouteSupport.parseLong(planIdParam);
        Map<String, Object> plan = PlanDao.findPlan(planId, session.userId);
        if (plan == null) {
            RouteSupport.error(response, 404, "计划不存在");
            return;
        }
        Map<String, Object> pack = PlanDao.findPack(RouteSupport.parseLong(packIdParam), planId);
        if (pack == null) {
            RouteSupport.error(response, 404, "准备包不存在");
            return;
        }
        PlanDao.confirmPack(((Number) pack.get("pack_id")).longValue());
        PlanDao.setPlanPreparationStatus(planId, "confirmed");
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("preparation", packDict(PlanDao.findPack(((Number) pack.get("pack_id")).longValue(), planId)))
            .toJSONString());
    }

    private static final Set<String> PACK_FIELDS = new HashSet<>(java.util.Arrays.asList(
        "intro_30", "intro_60", "intro_90", "highlights", "project_followups",
        "risk_points", "frequent_questions", "star_stories", "review_topics", "source_evidence"));

    /** 准备包内容编辑：数组字段序列化为 JSON 文本；confirmed 包编辑后退回 draft。 */
    private static void handleUpdatePack(HttpRequest request, String planIdParam, String packIdParam,
                                         HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long planId = RouteSupport.parseLong(planIdParam);
        if (PlanDao.findPlan(planId, session.userId) == null) {
            RouteSupport.error(response, 404, "计划不存在");
            return;
        }
        Map<String, Object> pack = PlanDao.findPack(RouteSupport.parseLong(packIdParam), planId);
        if (pack == null) {
            RouteSupport.error(response, 404, "准备包不存在");
            return;
        }
        JSONObject data = RouteSupport.parseBody(request);
        Map<String, Object> updates = new java.util.HashMap<>();
        for (String field : PACK_FIELDS) {
            if (data.containsKey(field) && data.get(field) != null) {
                Object value = data.get(field);
                updates.put(field, value instanceof String ? data.getString(field) : JSON.toJSONString(value));
            }
        }
        if (updates.isEmpty()) {
            RouteSupport.error(response, 400, "缺少可更新的准备包字段");
            return;
        }
        if ("confirmed".equals(String.valueOf(pack.get("status")))) {
            updates.put("_status", "draft");
        }
        PlanDao.updatePackFields(((Number) pack.get("pack_id")).longValue(), updates);
        if (updates.containsKey("_status")) {
            PlanDao.setPlanPreparationStatus(planId, "draft");
        }
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("preparation", packDict(PlanDao.findPack(((Number) pack.get("pack_id")).longValue(), planId)))
            .toJSONString());
    }

    /** 区块重生成：异步整包重生成（新版本落库前旧包保持可用）。 */
    private static void handleRegenerateSection(HttpRequest request, String planIdParam, String packIdParam,
                                                String field, HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long planId = RouteSupport.parseLong(planIdParam);
        Map<String, Object> plan = PlanDao.findPlan(planId, session.userId);
        if (plan == null) {
            RouteSupport.error(response, 404, "计划不存在");
            return;
        }
        if (PlanDao.findPack(RouteSupport.parseLong(packIdParam), planId) == null) {
            RouteSupport.error(response, 404, "准备包不存在");
            return;
        }
        if (field == null || !PACK_FIELDS.contains(field)) {
            RouteSupport.error(response, 400, "未知区块: " + field);
            return;
        }
        if (preparationService == null) {
            RouteSupport.error(response, 503, "LLM 未配置，无法重生成");
            return;
        }
        startPreparation(planId);
        RouteSupport.ok(response, new JSONObject()
            .fluentPut("success", true)
            .fluentPut("field", field)
            .fluentPut("status", "generating")
            .fluentPut("next_action", "poll_preparation").toJSONString());
    }

    // ---- 准备包后台生成 ----

    private static void startPreparation(long planId) {
        if (preparationService == null) {
            PlanDao.setPlanPreparationStatus(planId, "failed");
            return;
        }
        PlanDao.setPlanPreparationStatus(planId, "generating");
        if (!PREP_EXECUTOR.tryExecute(() -> runPreparation(planId))) {
            PlanDao.setPlanPreparationStatus(planId, "failed");
            log.warn("准备包任务队列已满 planId={}", planId);
        }
    }

    private static void runPreparation(long planId) {
        try {
            Map<String, Object> plan = PlanDao.findPlanAnyUser(planId);
            if (plan == null) {
                return;
            }
            Long userId = ((Number) plan.get("user_id")).longValue();
            plan = PlanDao.findPlan(planId, userId);
            Object resumeId = plan.get("resume_id");
            if (resumeId == null) {
                PlanDao.setPlanPreparationStatus(planId, "failed");
                return;
            }
            Map<String, Object> resume = ResumeDao.findById(((Number) resumeId).longValue());
            JSONObject resumePayload = resume == null ? null
                : JSONObject.parseObject(String.valueOf(resume.get("parsed_data")));
            JSONObject result = preparationService.generate(plan, resumePayload);
            PlanDao.markPacksOutdated(planId, -1);
            int version = PlanDao.nextPackVersion(planId);
            String status = result.getBoolean("needs_review") == Boolean.TRUE ? "needs_review" : "draft";
            PlanDao.createPack(planId, version, result.getString("source_fingerprint"), status,
                result.getString("intro_30"), result.getString("intro_60"), result.getString("intro_90"),
                JSON.toJSONString(result.getJSONArray("highlights")),
                JSON.toJSONString(result.getJSONArray("project_followups")),
                JSON.toJSONString(result.getJSONArray("risk_points")),
                JSON.toJSONString(result.getJSONArray("frequent_questions")),
                JSON.toJSONString(result.getJSONArray("star_stories")),
                JSON.toJSONString(result.getJSONArray("review_topics")),
                JSON.toJSONString(result.getJSONArray("source_evidence")));
            PlanDao.setPlanPreparationStatus(planId, status);
        } catch (Exception e) {
            try {
                PlanDao.setPlanPreparationStatus(planId, "failed");
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }

    // ---- 序列化 ----

    private static JSONObject planDict(Map<String, Object> plan, boolean includePacks) {
        JSONObject obj = RouteSupport.toJson(plan);
        if (includePacks) {
            JSONArray packs = new JSONArray();
            long planId = ((Number) plan.get("plan_id")).longValue();
            for (Map<String, Object> pack : PlanDao.listPacks(planId)) {
                packs.add(packDict(pack));
            }
            obj.put("packs", packs);
        }
        return obj;
    }

    private static JSONObject packDict(Map<String, Object> pack) {
        return RouteSupport.toJson(pack);
    }

    private static String jsonOrNull(Object value) {
        return value == null ? null : (value instanceof String ? (String) value : JSON.toJSONString(value));
    }
}
