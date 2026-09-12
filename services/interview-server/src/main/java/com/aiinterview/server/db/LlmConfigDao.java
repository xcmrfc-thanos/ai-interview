package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;

import java.util.Map;

/** llm_config 表 DAO（id=1 单例）。 */
public final class LlmConfigDao {

    private LlmConfigDao() {
    }

    /** 读取 id=1 行；无行返回 null。 */
    public static Map<String, Object> find() {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(LlmConfigMapper.class).findRow();
        }
    }

    /** upsert id=1 行；apiKeyEnc 可为 null（清除密钥）。 */
    public static void save(String provider, String baseUrl, String model,
                            String copilotModel, String thinkModel, String apiKeyEnc) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(LlmConfigMapper.class)
                .upsert(provider, baseUrl, model, copilotModel, thinkModel, apiKeyEnc);
        }
    }
}
