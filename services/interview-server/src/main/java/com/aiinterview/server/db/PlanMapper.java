package com.aiinterview.server.db;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** interview_plans / preparation_packs / knowledge_items 表 MyBatis mapper。 */
public interface PlanMapper {

    // ---- 面试计划 ----

    @Select("<script>SELECT * FROM interview_plans WHERE user_id = #{userId}"
        + "<if test='status != null and status != \"\"'> AND status = #{status}</if>"
        + " ORDER BY updated_at DESC</script>")
    List<Map<String, Object>> listPlans(@Param("userId") long userId, @Param("status") String status);

    @Select("SELECT * FROM interview_plans WHERE plan_id = #{planId} AND user_id = #{userId}")
    Map<String, Object> findPlan(@Param("planId") long planId, @Param("userId") long userId);

    @Select("SELECT * FROM interview_plans WHERE plan_id = #{planId}")
    Map<String, Object> findPlanAnyUser(@Param("planId") long planId);

    @Insert("INSERT INTO interview_plans (user_id, resume_id, company_name, position_name, job_description,"
        + " extra_requirements, level, tech_tags, status, preparation_status, created_at, updated_at)"
        + " VALUES (#{userId}, #{resumeId}, #{companyName}, #{positionName}, #{jobDescription},"
        + " #{extraRequirements}, #{level}, #{techTags}, 'active', 'none', datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "planId", keyColumn = "plan_id")
    int createPlan(PlanRow row);

    @Update("<script>UPDATE interview_plans SET updated_at = datetime('now')"
        + "<if test='resumeId != null'> , resume_id = #{resumeId}</if>"
        + "<if test='companyName != null'> , company_name = #{companyName}</if>"
        + "<if test='positionName != null'> , position_name = #{positionName}</if>"
        + "<if test='jobDescription != null'> , job_description = #{jobDescription}</if>"
        + "<if test='extraRequirements != null'> , extra_requirements = #{extraRequirements}</if>"
        + "<if test='level != null'> , level = #{level}</if>"
        + "<if test='techTags != null'> , tech_tags = #{techTags}</if>"
        + " WHERE plan_id = #{planId}</script>")
    int updatePlan(PlanRow row);

    @Update("UPDATE interview_plans SET status = 'archived', updated_at = datetime('now') WHERE plan_id = #{planId}")
    int archivePlan(@Param("planId") long planId);

    @Update("UPDATE interview_plans SET preparation_status = #{status}, updated_at = datetime('now')"
        + " WHERE plan_id = #{planId}")
    int setPreparationStatus(@Param("planId") long planId, @Param("status") String status);

    // ---- 准备包 ----

    @Select("SELECT * FROM preparation_packs WHERE plan_id = #{planId} ORDER BY version DESC LIMIT 1")
    Map<String, Object> latestPack(@Param("planId") long planId);

    @Select("SELECT * FROM preparation_packs WHERE plan_id = #{planId} ORDER BY version DESC")
    List<Map<String, Object>> listPacks(@Param("planId") long planId);

    @Select("SELECT * FROM preparation_packs WHERE pack_id = #{packId} AND plan_id = #{planId}")
    Map<String, Object> findPack(@Param("packId") long packId, @Param("planId") long planId);

    @Insert("INSERT INTO preparation_packs (plan_id, version, source_fingerprint, status, intro_30, intro_60,"
        + " intro_90, highlights, project_followups, risk_points, frequent_questions, star_stories,"
        + " review_topics, source_evidence, created_at, updated_at)"
        + " VALUES (#{planId}, #{version}, #{fingerprint}, #{status}, #{intro30}, #{intro60}, #{intro90},"
        + " #{highlights}, #{projectFollowups}, #{riskPoints}, #{frequentQuestions}, #{starStories},"
        + " #{reviewTopics}, #{sourceEvidence}, datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "packId", keyColumn = "pack_id")
    int createPack(PackRow row);

    @Update("UPDATE preparation_packs SET status = 'confirmed', confirmed_at = datetime('now')"
        + " WHERE pack_id = #{packId}")
    int confirmPack(@Param("packId") long packId);

    @Update("<script>UPDATE preparation_packs SET updated_at = datetime('now')"
        + "<if test='status != null'> , status = #{status}</if>"
        + "<if test='intro30 != null'> , intro_30 = #{intro30}</if>"
        + "<if test='intro60 != null'> , intro_60 = #{intro60}</if>"
        + "<if test='intro90 != null'> , intro_90 = #{intro90}</if>"
        + "<if test='highlights != null'> , highlights = #{highlights}</if>"
        + "<if test='projectFollowups != null'> , project_followups = #{projectFollowups}</if>"
        + "<if test='riskPoints != null'> , risk_points = #{riskPoints}</if>"
        + "<if test='frequentQuestions != null'> , frequent_questions = #{frequentQuestions}</if>"
        + "<if test='starStories != null'> , star_stories = #{starStories}</if>"
        + "<if test='reviewTopics != null'> , review_topics = #{reviewTopics}</if>"
        + "<if test='sourceEvidence != null'> , source_evidence = #{sourceEvidence}</if>"
        + " WHERE pack_id = #{packId}</script>")
    int updatePack(PackRow row);

    @Update("UPDATE preparation_packs SET status = 'outdated' WHERE plan_id = #{planId} AND pack_id != #{exceptPackId}")
    int markPacksOutdated(@Param("planId") long planId, @Param("exceptPackId") long exceptPackId);

    @Select("SELECT MAX(version) FROM preparation_packs WHERE plan_id = #{planId}")
    Integer maxPackVersion(@Param("planId") long planId);

    // ---- 知识库 ----

    @Select("<script>SELECT * FROM knowledge_items WHERE user_id = #{userId}"
        + "<if test='category != null and category != \"\"'> AND category = #{category}</if>"
        + "<if test='query != null and query != \"\"'> AND (title LIKE #{like} OR question LIKE #{like}"
        + " OR core_conclusion LIKE #{like})</if>"
        + " ORDER BY updated_at DESC</script>")
    List<Map<String, Object>> listKnowledge(@Param("userId") long userId, @Param("category") String category,
                                            @Param("query") String query, @Param("like") String like);

    @Select("SELECT * FROM knowledge_items WHERE item_id = #{itemId} AND user_id = #{userId}")
    Map<String, Object> findKnowledge(@Param("itemId") long itemId, @Param("userId") long userId);

    @Insert("INSERT INTO knowledge_items (user_id, title, question, category, role_tags, tech_tags, difficulty,"
        + " core_conclusion, answer_points, standard_answer, follow_ups, pitfalls, source, source_url,"
        + " content_hash, is_enabled, created_at, updated_at)"
        + " VALUES (#{userId}, #{title}, #{question}, #{category}, #{roleTags}, #{techTags}, #{difficulty},"
        + " #{coreConclusion}, #{answerPoints}, #{standardAnswer}, #{followUps}, #{pitfalls}, #{source},"
        + " #{sourceUrl}, #{contentHash}, 1, datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "itemId", keyColumn = "item_id")
    int createKnowledge(KnowledgeRow row);

    @Update("<script>UPDATE knowledge_items SET updated_at = datetime('now')"
        + "<if test='title != null'> , title = #{title}</if>"
        + "<if test='question != null'> , question = #{question}</if>"
        + "<if test='category != null'> , category = #{category}</if>"
        + "<if test='coreConclusion != null'> , core_conclusion = #{coreConclusion}</if>"
        + "<if test='standardAnswer != null'> , standard_answer = #{standardAnswer}</if>"
        + "<if test='isEnabled != null'> , is_enabled = #{isEnabled}</if>"
        + " WHERE item_id = #{itemId}</script>")
    int updateKnowledge(KnowledgeRow row);

    @Delete("DELETE FROM knowledge_items WHERE item_id = #{itemId}")
    int deleteKnowledge(@Param("itemId") long itemId);

    // ---- 行载体 ----

    class PlanRow {
        public long planId;
        public long userId;
        public Long resumeId;
        public String companyName;
        public String positionName;
        public String jobDescription;
        public String extraRequirements;
        public String level;
        public String techTags;
    }

    class PackRow {
        public long packId;
        public long planId;
        public int version;
        public String fingerprint;
        public String status;
        public String intro30;
        public String intro60;
        public String intro90;
        public String highlights;
        public String projectFollowups;
        public String riskPoints;
        public String frequentQuestions;
        public String starStories;
        public String reviewTopics;
        public String sourceEvidence;
    }

    class KnowledgeRow {
        public long itemId;
        public long userId;
        public String title;
        public String question;
        public String category;
        public String roleTags;
        public String techTags;
        public String difficulty;
        public String coreConclusion;
        public String answerPoints;
        public String standardAnswer;
        public String followUps;
        public String pitfalls;
        public String source;
        public String sourceUrl;
        public String contentHash;
        public Boolean isEnabled;
    }
}
