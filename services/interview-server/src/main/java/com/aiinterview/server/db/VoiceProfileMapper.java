package com.aiinterview.server.db;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/** voice_profiles 表 MyBatis mapper（每人一份，user_id 唯一）。 */
public interface VoiceProfileMapper {

    @Select("SELECT * FROM voice_profiles WHERE user_id = #{userId}")
    Map<String, Object> findByUserId(@Param("userId") long userId);

    @Insert("INSERT INTO voice_profiles (user_id, storage_name, original_filename, mime_type, created_at, updated_at)"
        + " VALUES (#{userId}, #{storageName}, #{originalFilename}, #{mimeType}, datetime('now'), datetime('now'))")
    @Options(useGeneratedKeys = true, keyProperty = "voiceProfileId", keyColumn = "voice_profile_id")
    int insert(Row row);

    @Update("UPDATE voice_profiles SET storage_name = #{storageName}, original_filename = #{originalFilename},"
        + " mime_type = #{mimeType}, updated_at = datetime('now') WHERE user_id = #{userId}")
    int update(@Param("userId") long userId, @Param("storageName") String storageName,
               @Param("originalFilename") String originalFilename, @Param("mimeType") String mimeType);

    @Delete("DELETE FROM voice_profiles WHERE user_id = #{userId}")
    int delete(@Param("userId") long userId);

    /** 插入行载体。 */
    class Row {
        public long voiceProfileId;
        public long userId;
        public String storageName;
        public String originalFilename;
        public String mimeType;
    }
}
