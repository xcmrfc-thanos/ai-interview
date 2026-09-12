package com.aiinterview.server.db;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** workspace_reviews / resume_optimizations 表 MyBatis mapper。 */
public interface ReviewMapper {

    @Select("SELECT * FROM workspace_reviews WHERE user_id = #{userId} AND source_type = #{sourceType}"
        + " AND source_id = #{sourceId}")
    Map<String, Object> findReviewBySource(@Param("userId") long userId, @Param("sourceType") String sourceType,
                                           @Param("sourceId") long sourceId);

    @Insert("INSERT INTO workspace_reviews (user_id, plan_id, source_type, source_id, summary,"
        + " question_categories, scores, fact_risks, expression_issues, weak_topics, next_actions,"
        + " diagnostics, created_at, updated_at)"
        + " VALUES (#{userId}, #{planId}, #{sourceType}, #{sourceId}, #{summary}, #{questionCategories},"
        + " #{scores}, #{factRisks}, #{expressionIssues}, #{weakTopics}, #{nextActions}, #{diagnostics},"
        + " datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "reviewId", keyColumn = "review_id")
    int createReview(ReviewRow row);

    @Select("<script>SELECT * FROM workspace_reviews WHERE user_id = #{userId}"
        + "<if test='planId != null'> AND plan_id = #{planId}</if>"
        + "<if test='sourceType != null and sourceType != \"\"'> AND source_type = #{sourceType}</if>"
        + " ORDER BY created_at DESC</script>")
    List<Map<String, Object>> listReviews(@Param("userId") long userId, @Param("planId") Long planId,
                                          @Param("sourceType") String sourceType);

    @Select("SELECT * FROM workspace_reviews WHERE review_id = #{reviewId} AND user_id = #{userId}")
    Map<String, Object> findReview(@Param("reviewId") long reviewId, @Param("userId") long userId);

    // ---- 简历优化 ----

    @Insert("INSERT INTO resume_optimizations (user_id, plan_id, resume_id, status, result,"
        + " confirmed_suggestion_ids, created_at, updated_at)"
        + " VALUES (#{userId}, #{planId}, #{resumeId}, 'needs_review', #{result}, '[]',"
        + " datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "optimizationId", keyColumn = "optimization_id")
    int createOptimization(OptimizationRow row);

    @Select("SELECT * FROM resume_optimizations WHERE plan_id = #{planId} AND user_id = #{userId}"
        + " ORDER BY optimization_id DESC LIMIT 1")
    Map<String, Object> latestOptimization(@Param("planId") long planId, @Param("userId") long userId);

    @Select("SELECT * FROM resume_optimizations WHERE optimization_id = #{optimizationId} AND user_id = #{userId}")
    Map<String, Object> findOptimization(@Param("optimizationId") long optimizationId, @Param("userId") long userId);

    @Update("UPDATE resume_optimizations SET confirmed_suggestion_ids = #{confirmed}, updated_at = datetime('now')"
        + " WHERE optimization_id = #{optimizationId}")
    int updateConfirmedSuggestions(@Param("optimizationId") long optimizationId, @Param("confirmed") String confirmed);

    class ReviewRow {
        public long reviewId;
        public long userId;
        public long planId;
        public String sourceType;
        public long sourceId;
        public String summary;
        public String questionCategories;
        public String scores;
        public String factRisks;
        public String expressionIssues;
        public String weakTopics;
        public String nextActions;
        public String diagnostics;
    }

    class OptimizationRow {
        public long optimizationId;
        public long userId;
        public long planId;
        public long resumeId;
        public String result;
    }
}
