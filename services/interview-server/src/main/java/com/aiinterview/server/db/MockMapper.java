package com.aiinterview.server.db;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** mock_interviews / mock_interview_turns 表 MyBatis mapper。 */
public interface MockMapper {

    @Insert("INSERT INTO mock_interviews (user_id, plan_id, status, current_turn_number, question_outline,"
        + " created_at, updated_at) VALUES (#{userId}, #{planId}, 'running', 1, #{outline},"
        + " datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "interviewId", keyColumn = "mock_interview_id")
    int createInterview(InterviewRow row);

    @Select("SELECT * FROM mock_interviews WHERE mock_interview_id = #{interviewId} AND user_id = #{userId}")
    Map<String, Object> findInterview(@Param("interviewId") long interviewId, @Param("userId") long userId);

    @Select("SELECT * FROM mock_interviews WHERE mock_interview_id = #{interviewId}")
    Map<String, Object> findInterviewAnyUser(@Param("interviewId") long interviewId);

    @Update("UPDATE mock_interviews SET status = #{status}, current_turn_number = #{turnNumber},"
        + " ended_at = COALESCE(ended_at, datetime('now')), updated_at = datetime('now')"
        + " WHERE mock_interview_id = #{interviewId}")
    int updateInterview(@Param("interviewId") long interviewId, @Param("status") String status,
                        @Param("turnNumber") int turnNumber);

    @Insert("INSERT INTO mock_interview_turns (mock_interview_id, turn_number, question, reference_points,"
        + " scores, strengths, improvements, status, created_at)"
        + " VALUES (#{interviewId}, #{turnNumber}, #{question}, '[]', '{}', '[]', '[]', 'ready', datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "turnId", keyColumn = "turn_id")
    int createTurn(TurnRow row);

    @Select("SELECT * FROM mock_interview_turns WHERE mock_interview_id = #{interviewId}"
        + " AND turn_number = #{turnNumber}")
    Map<String, Object> findTurn(@Param("interviewId") long interviewId, @Param("turnNumber") int turnNumber);

    @Select("SELECT * FROM mock_interview_turns WHERE mock_interview_id = #{interviewId} ORDER BY turn_number")
    List<Map<String, Object>> listTurns(@Param("interviewId") long interviewId);

    @Update("UPDATE mock_interview_turns SET answer = #{answer}, reference_points = #{referencePoints},"
        + " scores = #{scores}, strengths = #{strengths}, improvements = #{improvements},"
        + " status = #{status}, answered_at = datetime('now')"
        + " WHERE turn_id = #{turnId}")
    int updateTurn(TurnRow row);

    class InterviewRow {
        public long interviewId;
        public long userId;
        public long planId;
        public String outline;
    }

    class TurnRow {
        public long turnId;
        public long interviewId;
        public int turnNumber;
        public String question;
        public String answer;
        public String referencePoints;
        public String scores;
        public String strengths;
        public String improvements;
        public String status;
    }
}
