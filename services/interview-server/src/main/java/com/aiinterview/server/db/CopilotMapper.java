package com.aiinterview.server.db;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** copilot_sessions / copilot_turns / copilot_events 表 MyBatis mapper。 */
public interface CopilotMapper {

    @Insert("INSERT INTO copilot_sessions (user_id, plan_id, resume_id, preparation_pack_id, status,"
        + " last_client_sequence, current_turn_number, created_at, updated_at)"
        + " VALUES (#{userId}, #{planId}, #{resumeId}, #{packId}, 'created', 0, 1,"
        + " datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "sessionId", keyColumn = "session_id")
    int createSession(SessionRow row);

    @Select("SELECT * FROM copilot_sessions WHERE session_id = #{sessionId} AND user_id = #{userId}")
    Map<String, Object> findSession(@Param("sessionId") long sessionId, @Param("userId") long userId);

    @Select("SELECT * FROM copilot_sessions WHERE session_id = #{sessionId}")
    Map<String, Object> findSessionAnyUser(@Param("sessionId") long sessionId);

    @Update("UPDATE copilot_sessions SET status = #{status},"
        + " ended_at = CASE WHEN #{status} = 'ended' THEN COALESCE(ended_at, datetime('now')) ELSE ended_at END,"
        + " updated_at = datetime('now') WHERE session_id = #{sessionId}")
    int updateSessionStatus(@Param("sessionId") long sessionId, @Param("status") String status);

    @Update("UPDATE copilot_sessions SET last_client_sequence = #{sequence}, updated_at = datetime('now')"
        + " WHERE session_id = #{sessionId}")
    int updateSequence(@Param("sessionId") long sessionId, @Param("sequence") long sequence);

    @Insert("INSERT INTO copilot_turns (session_id, turn_number, status, partial_transcript, transcript,"
        + " answer_points, reference_answer, follow_up, knowledge_item_ids, created_at, updated_at)"
        + " VALUES (#{sessionId}, #{turnNumber}, 'transcribing', '', '', '[]', '', '', '[]',"
        + " datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "turnId", keyColumn = "turn_id")
    int createTurn(TurnRow row);

    @Select("SELECT * FROM copilot_turns WHERE session_id = #{sessionId} ORDER BY turn_number DESC LIMIT 1")
    Map<String, Object> latestTurn(@Param("sessionId") long sessionId);

    @Select("SELECT * FROM copilot_turns WHERE session_id = #{sessionId} ORDER BY turn_number")
    List<Map<String, Object>> listTurns(@Param("sessionId") long sessionId);

    @Update("UPDATE copilot_turns SET status = #{status}, transcript = #{transcript},"
        + " answer_points = #{answerPoints}, reference_answer = #{referenceAnswer}, follow_up = #{followUp},"
        + " updated_at = datetime('now') WHERE turn_id = #{turnId}")
    int updateTurn(TurnRow row);

    @Insert("INSERT INTO copilot_events (session_id, event_type, client_sequence, latency_ms, error_code,"
        + " payload, created_at) VALUES (#{sessionId}, #{eventType}, #{sequence}, #{latencyMs}, #{errorCode},"
        + " #{payload}, datetime('now'))")
    int createEvent(EventRow row);

    class SessionRow {
        public long sessionId;
        public long userId;
        public long planId;
        public Long resumeId;
        public Long packId;
    }

    class TurnRow {
        public long turnId;
        public long sessionId;
        public int turnNumber;
        public String status;
        public String transcript;
        public String answerPoints;
        public String referenceAnswer;
        public String followUp;
    }

    class EventRow {
        public long sessionId;
        public String eventType;
        public long sequence;
        public Long latencyMs;
        public String errorCode;
        public String payload;
    }
}
