package com.aiinterview.server.db;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/** users / applicants 表 MyBatis mapper（注解 SQL）。 */
public interface UserMapper {

    @Select("SELECT * FROM users WHERE email = #{email}")
    Map<String, Object> findByEmail(@Param("email") String email);

    @Select("SELECT * FROM users WHERE user_id = #{userId}")
    Map<String, Object> findById(@Param("userId") long userId);

    @Insert("INSERT INTO users (password, email, role, created_at, updated_at)"
        + " VALUES (#{password}, #{email}, #{role}, datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "userId", keyColumn = "user_id")
    int createUser(UserRow row);

    @Update("UPDATE users SET password = #{password}, updated_at = datetime('now') WHERE user_id = #{userId}")
    int updatePassword(@Param("userId") long userId, @Param("password") String password);

    @Update("UPDATE users SET email = #{email}, updated_at = datetime('now') WHERE user_id = #{userId}")
    int updateEmail(@Param("userId") long userId, @Param("email") String email);

    @Update("UPDATE applicants SET full_name = #{fullName}, phone = #{phone},"
        + " expected_position = #{expectedPosition}, expected_salary = #{expectedSalary},"
        + " work_years = #{workYears}, updated_at = datetime('now') WHERE user_id = #{userId}")
    int updateApplicantProfile(@Param("userId") long userId, @Param("fullName") String fullName,
                               @Param("phone") String phone, @Param("expectedPosition") String expectedPosition,
                               @Param("expectedSalary") int expectedSalary, @Param("workYears") int workYears);

    @Select("SELECT * FROM applicants WHERE user_id = #{userId}")
    Map<String, Object> findApplicantByUserId(@Param("userId") long userId);

    @Insert("INSERT INTO applicants (user_id, full_name, gender, birthdate, education_level, work_years,"
        + " expected_position, expected_salary, created_at, updated_at)"
        + " VALUES (#{userId}, #{fullName}, #{gender}, #{birthdate}, #{educationLevel}, #{workYears},"
        + " #{expectedPosition}, #{expectedSalary}, datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "applicantId", keyColumn = "applicant_id")
    int createApplicant(ApplicantRow row);

    @Select("SELECT COUNT(*) FROM users")
    long countUsers();

    /** users 插入行载体（MyBatis 参数对象）。 */
    class UserRow {
        public long userId;
        public String password;
        public String email;
        public String role;
    }

    /** applicants 插入行载体。 */
    class ApplicantRow {
        public long applicantId;
        public long userId;
        public String fullName;
        public String gender;
        public String birthdate;
        public String educationLevel;
        public int workYears;
        public String expectedPosition;
        public int expectedSalary;
    }
}
