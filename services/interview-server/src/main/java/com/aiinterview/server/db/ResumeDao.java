package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;

import java.util.List;
import java.util.Map;

/** resumes 表 DAO（MyBatis 实现）。 */
public final class ResumeDao {

    private ResumeDao() {
    }

    public static long create(long applicantId, String fileUrl, String filename, String parsedDataJson) {
        try (SqlSession session = MyBatis.open()) {
            ResumeMapper.ResumeRow row = new ResumeMapper.ResumeRow();
            row.applicantId = applicantId;
            row.fileUrl = fileUrl;
            row.filename = filename;
            row.parsedData = parsedDataJson;
            session.getMapper(ResumeMapper.class).create(row);
            return row.resumeId;
        }
    }

    public static List<Map<String, Object>> listByApplicant(long applicantId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(ResumeMapper.class).listByApplicant(applicantId);
        }
    }

    public static Map<String, Object> findById(long resumeId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(ResumeMapper.class).findById(resumeId);
        }
    }

    public static Map<String, Object> findForUser(long resumeId, long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(ResumeMapper.class).findForUser(resumeId, userId);
        }
    }

    /** 是否有复盘报告（report 表存在性检查）。 */
    public static boolean hasReport(long resumeId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(ResumeMapper.class).hasReport(resumeId) != null;
        }
    }

    public static void updateParsedData(long resumeId, String parsedDataJson) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(ResumeMapper.class).updateParsedData(resumeId, parsedDataJson);
        }
    }

    public static void delete(long resumeId) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(ResumeMapper.class).delete(resumeId);
        }
    }
}
