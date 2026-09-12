package com.aiinterview.server.db;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/** llm_config 表（id=1 单例，主仓 Python 模型配置写入）mapper。 */
public interface LlmConfigMapper {

    @Select("SELECT id, provider, base_url, model, copilot_model, think_model, api_key_enc, updated_at "
        + "FROM llm_config WHERE id = 1")
    Map<String, Object> findRow();

    @Update("INSERT INTO llm_config (id, provider, base_url, model, copilot_model, think_model, api_key_enc, updated_at) "
        + "VALUES (1, #{provider}, #{baseUrl}, #{model}, #{copilotModel}, #{thinkModel}, #{apiKeyEnc}, datetime('now')) "
        + "ON CONFLICT(id) DO UPDATE SET provider = excluded.provider, base_url = excluded.base_url, "
        + "model = excluded.model, copilot_model = excluded.copilot_model, think_model = excluded.think_model, "
        + "api_key_enc = excluded.api_key_enc, updated_at = datetime('now')")
    int upsert(@Param("provider") String provider, @Param("baseUrl") String baseUrl,
               @Param("model") String model, @Param("copilotModel") String copilotModel,
               @Param("thinkModel") String thinkModel, @Param("apiKeyEnc") String apiKeyEnc);
}
