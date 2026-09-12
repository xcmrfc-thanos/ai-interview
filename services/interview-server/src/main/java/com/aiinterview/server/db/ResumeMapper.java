package com.aiinterview.server.db;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** resumes 表 MyBatis mapper。 */
public interface ResumeMapper {

    @Insert("INSERT INTO resumes (applicant_id, file_url, filename, upload_date, parsed_data)"
        + " VALUES (#{applicantId}, #{fileUrl}, #{filename}, datetime('now'), #{parsedData})")
    @Options(useGeneratedKeys = true, keyProperty = "resumeId", keyColumn = "resume_id")
    int create(ResumeRow row);

    @Select("SELECT * FROM resumes WHERE applicant_id = #{applicantId} ORDER BY resume_id DESC")
    List<Map<String, Object>> listByApplicant(@Param("applicantId") long applicantId);

    @Select("SELECT * FROM resumes WHERE resume_id = #{resumeId}")
    Map<String, Object> findById(@Param("resumeId") long resumeId);

    @Select("SELECT r.* FROM resumes r JOIN applicants a ON a.applicant_id = r.applicant_id"
        + " WHERE r.resume_id = #{resumeId} AND a.user_id = #{userId}")
    Map<String, Object> findForUser(@Param("resumeId") long resumeId, @Param("userId") long userId);

    @Update("UPDATE resumes SET parsed_data = #{parsedData} WHERE resume_id = #{resumeId}")
    int updateParsedData(@Param("resumeId") long resumeId, @Param("parsedData") String parsedData);

    @Select("SELECT 1 AS has_report FROM report WHERE resume_id = #{resumeId} LIMIT 1")
    Map<String, Object> hasReport(@Param("resumeId") long resumeId);

    @Delete("DELETE FROM resumes WHERE resume_id = #{resumeId}")
    int delete(@Param("resumeId") long resumeId);

    /** resumes 插入行载体。 */
    class ResumeRow {
        public long resumeId;
        public long applicantId;
        public String fileUrl;
        public String filename;
        public String parsedData;
    }
}
