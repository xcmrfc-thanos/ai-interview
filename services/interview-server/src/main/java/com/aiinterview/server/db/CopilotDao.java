package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;

import java.util.List;
import java.util.Map;

/** copilot_sessions / copilot_turns / copilot_events 表 DAO。 */
public final class CopilotDao {

    private CopilotDao() {
    }

    public static long createSession(long userId, long planId, Long resumeId, Long packId) {
        try (SqlSession session = MyBatis.open()) {
            CopilotMapper.SessionRow row = new CopilotMapper.SessionRow();
            row.userId = userId;
            row.planId = planId;
            row.resumeId = resumeId;
            row.packId = packId;
            session.getMapper(CopilotMapper.class).createSession(row);
            return row.sessionId;
        }
    }

    public static Map<String, Object> findSession(long sessionId, long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(CopilotMapper.class).findSession(sessionId, userId);
        }
    }

    public static Map<String, Object> findSessionAnyUser(long sessionId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(CopilotMapper.class).findSessionAnyUser(sessionId);
        }
    }

    public static void updateSessionStatus(long sessionId, String status) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(CopilotMapper.class).updateSessionStatus(sessionId, status);
        }
    }

    public static void updateSequence(long sessionId, long sequence) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(CopilotMapper.class).updateSequence(sessionId, sequence);
        }
    }

    public static long createTurn(long sessionId, int turnNumber) {
        try (SqlSession session = MyBatis.open()) {
            CopilotMapper.TurnRow row = new CopilotMapper.TurnRow();
            row.sessionId = sessionId;
            row.turnNumber = turnNumber;
            session.getMapper(CopilotMapper.class).createTurn(row);
            return row.turnId;
        }
    }

    /**
     * 在同一事务内创建 turn 并写入转写文本，返回 turnId。
     */
    public static long beginTurn(long sessionId, int turnNumber, String transcript) {
        final long[] turnId = {0L};
        MyBatis.tx(sqlSession -> {
            CopilotMapper mapper = sqlSession.getMapper(CopilotMapper.class);
            CopilotMapper.TurnRow row = new CopilotMapper.TurnRow();
            row.sessionId = sessionId;
            row.turnNumber = turnNumber;
            mapper.createTurn(row);
            turnId[0] = row.turnId;
            row.status = "ready";
            row.transcript = transcript == null ? "" : transcript.trim();
            row.answerPoints = "[]";
            row.referenceAnswer = "";
            row.followUp = "";
            mapper.updateTurn(row);
        });
        return turnId[0];
    }

    /** 使用单线程写队列持久化 turn 结果。 */
    public static void completeTurnAsync(long turnId, String status, String transcript, String answerPoints,
                                         String referenceAnswer, String followUp) throws Exception {
        DbWriteExecutor.runAndWait(() -> updateTurn(turnId, status, transcript, answerPoints, referenceAnswer, followUp));
    }

    public static Map<String, Object> latestTurn(long sessionId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(CopilotMapper.class).latestTurn(sessionId);
        }
    }

    /** 更新最新 turn 的转写文本（final 到达时）。 */
    public static void updateLatestTurnTranscript(long sessionId, String transcript) {
        try (SqlSession session = MyBatis.open()) {
            CopilotMapper.TurnRow row = new CopilotMapper.TurnRow();
            Map<String, Object> turn = session.getMapper(CopilotMapper.class).latestTurn(sessionId);
            if (turn == null) {
                return;
            }
            row.turnId = ((Number) turn.get("turn_id")).longValue();
            row.status = "ready";
            row.transcript = transcript;
            session.getMapper(CopilotMapper.class).updateTurn(row);
        }
    }

    public static List<Map<String, Object>> listTurns(long sessionId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(CopilotMapper.class).listTurns(sessionId);
        }
    }

    public static void updateTurn(long turnId, String status, String transcript, String answerPoints,
                                  String referenceAnswer, String followUp) {
        try (SqlSession session = MyBatis.open()) {
            CopilotMapper.TurnRow row = new CopilotMapper.TurnRow();
            row.turnId = turnId;
            row.status = status;
            row.transcript = transcript;
            row.answerPoints = cn.hutool.core.util.StrUtil.nullToDefault(answerPoints, "[]");
            row.referenceAnswer = cn.hutool.core.util.StrUtil.nullToDefault(referenceAnswer, "");
            row.followUp = cn.hutool.core.util.StrUtil.nullToDefault(followUp, "");
            session.getMapper(CopilotMapper.class).updateTurn(row);
        }
    }

    public static void createEvent(long sessionId, String eventType, long sequence, Long latencyMs,
                                   String errorCode, String payload) {
        try (SqlSession session = MyBatis.open()) {
            CopilotMapper.EventRow row = new CopilotMapper.EventRow();
            row.sessionId = sessionId;
            row.eventType = eventType;
            row.sequence = sequence;
            row.latencyMs = latencyMs;
            row.errorCode = errorCode;
            row.payload = payload;
            session.getMapper(CopilotMapper.class).createEvent(row);
        }
    }
}
