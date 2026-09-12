package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;

import java.util.Map;

/** users / applicants 表 DAO（MyBatis 实现，签名与路由层解耦）。 */
public final class UserDao {

    private UserDao() {
    }

    public static Map<String, Object> findByEmail(String email) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(UserMapper.class).findByEmail(email);
        }
    }

    public static Map<String, Object> findById(long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(UserMapper.class).findById(userId);
        }
    }

    public static long createUser(String email, String passwordHash, String role) {
        try (SqlSession session = MyBatis.open()) {
            UserMapper.UserRow row = new UserMapper.UserRow();
            row.email = email;
            row.password = passwordHash;
            row.role = role;
            session.getMapper(UserMapper.class).createUser(row);
            return row.userId;
        }
    }

    public static Map<String, Object> findApplicantByUserId(long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(UserMapper.class).findApplicantByUserId(userId);
        }
    }

    public static void updatePassword(long userId, String passwordHash) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(UserMapper.class).updatePassword(userId, passwordHash);
        }
    }

    public static long createApplicant(long userId, String fullName) {
        UserMapper.ApplicantRow row = new UserMapper.ApplicantRow();
        row.userId = userId;
        row.fullName = fullName;
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(UserMapper.class).createApplicant(row);
            return row.applicantId;
        }
    }

    public static long createApplicantWithProfile(long userId, String fullName, String gender,
                                                  String birthdate, String educationLevel,
                                                  int workYears, String expectedPosition,
                                                  int expectedSalary) {
        UserMapper.ApplicantRow row = new UserMapper.ApplicantRow();
        row.userId = userId;
        row.fullName = fullName;
        row.gender = gender;
        row.birthdate = (birthdate == null || birthdate.isEmpty()) ? null : birthdate;
        row.educationLevel = educationLevel;
        row.workYears = workYears;
        row.expectedPosition = expectedPosition;
        row.expectedSalary = expectedSalary;
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(UserMapper.class).createApplicant(row);
            return row.applicantId;
        }
    }

    public static long countUsers() {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(UserMapper.class).countUsers();
        }
    }

    public static void updateEmail(long userId, String email) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(UserMapper.class).updateEmail(userId, email);
        }
    }

    public static void updateApplicantProfile(long userId, String fullName, String phone,
                                              String expectedPosition, int expectedSalary, int workYears) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(UserMapper.class).updateApplicantProfile(
                userId, fullName, phone, expectedPosition, expectedSalary, workYears);
        }
    }
}
