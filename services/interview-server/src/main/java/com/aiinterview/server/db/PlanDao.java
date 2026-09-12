package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;

import java.util.List;
import java.util.Map;

/** interview_plans / preparation_packs / knowledge_items 表 DAO（MyBatis 实现）。 */
public final class PlanDao {

    private PlanDao() {
    }

    // ---- 面试计划 ----

    public static List<Map<String, Object>> listPlans(long userId, String status) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(PlanMapper.class).listPlans(userId, status);
        }
    }

    public static Map<String, Object> findPlan(long planId, long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(PlanMapper.class).findPlan(planId, userId);
        }
    }

    public static Map<String, Object> findPlanAnyUser(long planId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(PlanMapper.class).findPlanAnyUser(planId);
        }
    }

    public static long createPlan(long userId, Long resumeId, String companyName, String positionName,
                                  String jobDescription, String extraRequirements, String level,
                                  String techTagsJson) {
        try (SqlSession session = MyBatis.open()) {
            PlanMapper.PlanRow row = new PlanMapper.PlanRow();
            row.userId = userId;
            row.resumeId = resumeId;
            row.companyName = companyName;
            row.positionName = positionName;
            row.jobDescription = jobDescription;
            row.extraRequirements = extraRequirements;
            row.level = level;
            row.techTags = cn.hutool.core.util.StrUtil.blankToDefault(techTagsJson, "[]");
            session.getMapper(PlanMapper.class).createPlan(row);
            return row.planId;
        }
    }

    public static void updatePlanFields(long planId, Map<String, Object> data) {
        try (SqlSession session = MyBatis.open()) {
            updatePlanFields(session, planId, data);
        }
    }

    /** 事务内重载：与调用方共用同一连接。 */
    public static void updatePlanFields(SqlSession session, long planId, Map<String, Object> data) {
        PlanMapper.PlanRow row = new PlanMapper.PlanRow();
        row.planId = planId;
        if (data.containsKey("resume_id")) {
            Object value = data.get("resume_id");
            row.resumeId = value == null ? null : Long.valueOf(String.valueOf(value));
        }
        row.companyName = strOrNull(data.get("company_name"));
        row.positionName = strOrNull(data.get("position_name"));
        row.jobDescription = strOrNull(data.get("job_description"));
        row.extraRequirements = strOrNull(data.get("extra_requirements"));
        row.level = strOrNull(data.get("level"));
        row.techTags = strOrNull(data.get("tech_tags"));
        session.getMapper(PlanMapper.class).updatePlan(row);
    }

    public static void archivePlan(long planId) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(PlanMapper.class).archivePlan(planId);
        }
    }

    public static void setPlanPreparationStatus(long planId, String status) {
        try (SqlSession session = MyBatis.open()) {
            setPlanPreparationStatus(session, planId, status);
        }
    }

    /** 事务内重载：与调用方共用同一连接。 */
    public static void setPlanPreparationStatus(SqlSession session, long planId, String status) {
        session.getMapper(PlanMapper.class).setPreparationStatus(planId, status);
    }

    // ---- 准备包 ----

    public static Map<String, Object> latestPack(long planId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(PlanMapper.class).latestPack(planId);
        }
    }

    public static List<Map<String, Object>> listPacks(long planId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(PlanMapper.class).listPacks(planId);
        }
    }

    public static Map<String, Object> findPack(long packId, long planId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(PlanMapper.class).findPack(packId, planId);
        }
    }

    public static long createPack(long planId, int version, String fingerprint, String status,
                                  String intro30, String intro60, String intro90,
                                  String highlights, String projectFollowups, String riskPoints,
                                  String frequentQuestions, String starStories, String reviewTopics,
                                  String sourceEvidence) {
        try (SqlSession session = MyBatis.open()) {
            PlanMapper.PackRow row = new PlanMapper.PackRow();
            row.planId = planId;
            row.version = version;
            row.fingerprint = fingerprint;
            row.status = status;
            row.intro30 = cn.hutool.core.util.StrUtil.nullToDefault(intro30, "");
            row.intro60 = cn.hutool.core.util.StrUtil.nullToDefault(intro60, "");
            row.intro90 = cn.hutool.core.util.StrUtil.nullToDefault(intro90, "");
            row.highlights = cn.hutool.core.util.StrUtil.nullToDefault(highlights, "[]");
            row.projectFollowups = cn.hutool.core.util.StrUtil.nullToDefault(projectFollowups, "[]");
            row.riskPoints = cn.hutool.core.util.StrUtil.nullToDefault(riskPoints, "[]");
            row.frequentQuestions = cn.hutool.core.util.StrUtil.nullToDefault(frequentQuestions, "[]");
            row.starStories = cn.hutool.core.util.StrUtil.nullToDefault(starStories, "[]");
            row.reviewTopics = cn.hutool.core.util.StrUtil.nullToDefault(reviewTopics, "[]");
            row.sourceEvidence = cn.hutool.core.util.StrUtil.nullToDefault(sourceEvidence, "[]");
            session.getMapper(PlanMapper.class).createPack(row);
            return row.packId;
        }
    }

    public static void confirmPack(long packId) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(PlanMapper.class).confirmPack(packId);
        }
    }

    /** 部分更新准备包内容字段（map 中缺失/null 的字段跳过）；status 可携带 'draft' 实现确认后编辑回退。 */
    public static void updatePackFields(long packId, Map<String, Object> data) {
        try (SqlSession session = MyBatis.open()) {
            PlanMapper.PackRow row = new PlanMapper.PackRow();
            row.packId = packId;
            row.status = strOrNull(data.get("_status"));
            row.intro30 = strOrNull(data.get("intro_30"));
            row.intro60 = strOrNull(data.get("intro_60"));
            row.intro90 = strOrNull(data.get("intro_90"));
            row.highlights = strOrNull(data.get("highlights"));
            row.projectFollowups = strOrNull(data.get("project_followups"));
            row.riskPoints = strOrNull(data.get("risk_points"));
            row.frequentQuestions = strOrNull(data.get("frequent_questions"));
            row.starStories = strOrNull(data.get("star_stories"));
            row.reviewTopics = strOrNull(data.get("review_topics"));
            row.sourceEvidence = strOrNull(data.get("source_evidence"));
            session.getMapper(PlanMapper.class).updatePack(row);
        }
    }

    public static void markPacksOutdated(long planId, long exceptPackId) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(PlanMapper.class).markPacksOutdated(planId, exceptPackId);
        }
    }

    /** 事务内重载：与调用方共用同一连接。 */
    public static void markPacksOutdated(SqlSession session, long planId, long exceptPackId) {
        session.getMapper(PlanMapper.class).markPacksOutdated(planId, exceptPackId);
    }

    public static int nextPackVersion(long planId) {
        try (SqlSession session = MyBatis.open()) {
            Integer max = session.getMapper(PlanMapper.class).maxPackVersion(planId);
            return max == null ? 1 : max + 1;
        }
    }

    // ---- 知识库 ----

    public static List<Map<String, Object>> listKnowledge(long userId, String query, String category) {
        try (SqlSession session = MyBatis.open()) {
            String like = (query == null || query.isEmpty()) ? null : "%" + query + "%";
            return session.getMapper(PlanMapper.class).listKnowledge(userId, category, query, like);
        }
    }

    public static Map<String, Object> findKnowledge(long itemId, long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(PlanMapper.class).findKnowledge(itemId, userId);
        }
    }

    public static long createKnowledge(long userId, Map<String, Object> data) {
        try (SqlSession session = MyBatis.open()) {
            PlanMapper.KnowledgeRow row = new PlanMapper.KnowledgeRow();
            row.userId = userId;
            row.title = strOrNull(data.get("title"));
            row.question = nvl(data.get("question"), "");
            row.category = strOrNull(data.get("category"));
            row.roleTags = jsonOrEmpty(data.get("role_tags"));
            row.techTags = jsonOrEmpty(data.get("tech_tags"));
            row.difficulty = strOrNull(data.get("difficulty"));
            row.coreConclusion = strOrNull(data.get("core_conclusion"));
            row.answerPoints = jsonOrEmpty(data.get("answer_points"));
            row.standardAnswer = strOrNull(data.get("standard_answer"));
            row.followUps = jsonOrEmpty(data.get("follow_ups"));
            row.pitfalls = jsonOrEmpty(data.get("pitfalls"));
            row.source = strOrNull(data.get("source"));
            row.sourceUrl = strOrNull(data.get("source_url"));
            String hash = strOrNull(data.get("content_hash"));
            row.contentHash = cn.hutool.core.util.StrUtil.blankToDefault(hash,
                cn.hutool.crypto.digest.DigestUtil.sha256Hex(row.title + row.question));
            session.getMapper(PlanMapper.class).createKnowledge(row);
            return row.itemId;
        }
    }

    public static void updateKnowledge(long itemId, Map<String, Object> data) {
        try (SqlSession session = MyBatis.open()) {
            PlanMapper.KnowledgeRow row = new PlanMapper.KnowledgeRow();
            row.itemId = itemId;
            row.title = strOrNull(data.get("title"));
            row.question = strOrNull(data.get("question"));
            row.category = strOrNull(data.get("category"));
            row.coreConclusion = strOrNull(data.get("core_conclusion"));
            row.standardAnswer = strOrNull(data.get("standard_answer"));
            Object enabled = data.get("is_enabled");
            row.isEnabled = enabled == null ? null : Boolean.valueOf(String.valueOf(enabled));
            session.getMapper(PlanMapper.class).updateKnowledge(row);
        }
    }

    public static void deleteKnowledge(long itemId) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(PlanMapper.class).deleteKnowledge(itemId);
        }
    }

    private static String strOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nvl(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String jsonOrEmpty(Object value) {
        if (value == null) {
            return "[]";
        }
        if (value instanceof String) {
            String s = (String) value;
            return s.trim().isEmpty() ? "[]" : s;
        }
        return com.alibaba.fastjson2.JSON.toJSONString(value);
    }
}
