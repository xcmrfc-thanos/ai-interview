package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;

import java.util.List;
import java.util.Map;

/** mock_interviews / mock_interview_turns 表 DAO。 */
public final class MockDao {

    private MockDao() {
    }

    public static long createInterview(long userId, long planId, String outlineJson) {
        try (SqlSession session = MyBatis.open()) {
            MockMapper.InterviewRow row = new MockMapper.InterviewRow();
            row.userId = userId;
            row.planId = planId;
            row.outline = outlineJson;
            session.getMapper(MockMapper.class).createInterview(row);
            return row.interviewId;
        }
    }

    public static Map<String, Object> findInterview(long interviewId, long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(MockMapper.class).findInterview(interviewId, userId);
        }
    }

    public static Map<String, Object> findInterviewAnyUser(long interviewId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(MockMapper.class).findInterviewAnyUser(interviewId);
        }
    }

    public static void updateInterview(long interviewId, String status, int turnNumber) {
        try (SqlSession session = MyBatis.open()) {
            updateInterview(session, interviewId, status, turnNumber);
        }
    }

    /** 事务内重载：与调用方共用同一连接。 */
    public static void updateInterview(SqlSession session, long interviewId, String status, int turnNumber) {
        session.getMapper(MockMapper.class).updateInterview(interviewId, status, turnNumber);
    }

    public static long createTurn(long interviewId, int turnNumber, String question) {
        try (SqlSession session = MyBatis.open()) {
            return createTurn(session, interviewId, turnNumber, question);
        }
    }

    /** 事务内重载：与调用方共用同一连接。 */
    public static long createTurn(SqlSession session, long interviewId, int turnNumber, String question) {
        MockMapper.TurnRow row = new MockMapper.TurnRow();
        row.interviewId = interviewId;
        row.turnNumber = turnNumber;
        row.question = question;
        session.getMapper(MockMapper.class).createTurn(row);
        return row.turnId;
    }

    public static Map<String, Object> findTurn(long interviewId, int turnNumber) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(MockMapper.class).findTurn(interviewId, turnNumber);
        }
    }

    public static List<Map<String, Object>> listTurns(long interviewId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(MockMapper.class).listTurns(interviewId);
        }
    }

    public static void updateTurn(long turnId, String answer, String referencePoints, String scores,
                                  String strengths, String improvements, String status) {
        try (SqlSession session = MyBatis.open()) {
            updateTurn(session, turnId, answer, referencePoints, scores, strengths, improvements, status);
        }
    }

    /** 事务内重载：与调用方共用同一连接。 */
    public static void updateTurn(SqlSession session, long turnId, String answer, String referencePoints,
                                  String scores, String strengths, String improvements, String status) {
        MockMapper.TurnRow row = new MockMapper.TurnRow();
        row.turnId = turnId;
        row.answer = answer;
        row.referencePoints = cn.hutool.core.util.StrUtil.nullToDefault(referencePoints, "[]");
        row.scores = cn.hutool.core.util.StrUtil.nullToDefault(scores, "{}");
        row.strengths = cn.hutool.core.util.StrUtil.nullToDefault(strengths, "[]");
        row.improvements = cn.hutool.core.util.StrUtil.nullToDefault(improvements, "[]");
        row.status = status;
        session.getMapper(MockMapper.class).updateTurn(row);
    }
}
