package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;

import java.util.List;
import java.util.Map;

/** workspace_reviews / resume_optimizations 表 DAO。 */
public final class ReviewDao {

    private ReviewDao() {
    }

    public static Map<String, Object> findReviewBySource(long userId, String sourceType, long sourceId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(ReviewMapper.class).findReviewBySource(userId, sourceType, sourceId);
        }
    }

    public static long createReview(long userId, long planId, String sourceType, long sourceId,
                                    String summary, String questionCategories, String scores,
                                    String factRisks, String expressionIssues, String weakTopics,
                                    String nextActions, String diagnostics) {
        try (SqlSession session = MyBatis.open()) {
            ReviewMapper.ReviewRow row = new ReviewMapper.ReviewRow();
            row.userId = userId;
            row.planId = planId;
            row.sourceType = sourceType;
            row.sourceId = sourceId;
            row.summary = cn.hutool.core.util.StrUtil.nullToDefault(summary, "");
            row.questionCategories = cn.hutool.core.util.StrUtil.nullToDefault(questionCategories, "[]");
            row.scores = cn.hutool.core.util.StrUtil.nullToDefault(scores, "{}");
            row.factRisks = cn.hutool.core.util.StrUtil.nullToDefault(factRisks, "[]");
            row.expressionIssues = cn.hutool.core.util.StrUtil.nullToDefault(expressionIssues, "[]");
            row.weakTopics = cn.hutool.core.util.StrUtil.nullToDefault(weakTopics, "[]");
            row.nextActions = cn.hutool.core.util.StrUtil.nullToDefault(nextActions, "[]");
            row.diagnostics = cn.hutool.core.util.StrUtil.nullToDefault(diagnostics, "{}");
            session.getMapper(ReviewMapper.class).createReview(row);
            return row.reviewId;
        }
    }

    public static List<Map<String, Object>> listReviews(long userId, Long planId, String sourceType) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(ReviewMapper.class).listReviews(userId, planId, sourceType);
        }
    }

    public static Map<String, Object> findReview(long reviewId, long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(ReviewMapper.class).findReview(reviewId, userId);
        }
    }

    public static long createOptimization(long userId, long planId, long resumeId, String result) {
        try (SqlSession session = MyBatis.open()) {
            ReviewMapper.OptimizationRow row = new ReviewMapper.OptimizationRow();
            row.userId = userId;
            row.planId = planId;
            row.resumeId = resumeId;
            row.result = result;
            session.getMapper(ReviewMapper.class).createOptimization(row);
            return row.optimizationId;
        }
    }

    public static Map<String, Object> latestOptimization(long planId, long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(ReviewMapper.class).latestOptimization(planId, userId);
        }
    }

    /** 单条建议确认：整体覆写 confirmed_suggestion_ids 列。 */
    public static void updateConfirmedSuggestions(long optimizationId, String confirmedJson) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(ReviewMapper.class).updateConfirmedSuggestions(optimizationId, confirmedJson);
        }
    }

    public static Map<String, Object> findOptimization(long optimizationId, long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(ReviewMapper.class).findOptimization(optimizationId, userId);
        }
    }
}
