package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;

import java.util.Map;

/** voice_profiles 表 DAO（MyBatis 实现）。 */
public final class VoiceProfileDao {

    private VoiceProfileDao() {
    }

    public static Map<String, Object> findByUserId(long userId) {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(VoiceProfileMapper.class).findByUserId(userId);
        }
    }

    /** 新增或覆盖（每人一份语音档案）。 */
    public static void upsert(long userId, String storageName, String originalFilename, String mimeType) {
        try (SqlSession session = MyBatis.open()) {
            VoiceProfileMapper mapper = session.getMapper(VoiceProfileMapper.class);
            if (mapper.findByUserId(userId) == null) {
                VoiceProfileMapper.Row row = new VoiceProfileMapper.Row();
                row.userId = userId;
                row.storageName = storageName;
                row.originalFilename = originalFilename;
                row.mimeType = mimeType;
                mapper.insert(row);
            } else {
                mapper.update(userId, storageName, originalFilename, mimeType);
            }
        }
    }

    public static void delete(long userId) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(VoiceProfileMapper.class).delete(userId);
        }
    }
}
