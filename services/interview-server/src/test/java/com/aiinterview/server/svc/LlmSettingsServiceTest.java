package com.aiinterview.server.svc;

import com.aiinterview.server.FernetBox;
import com.aiinterview.server.db.LlmConfigDao;
import com.aiinterview.server.db.MyBatis;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSettingsServiceTest {

    @TempDir
    Path tempDir;
    private final FernetBox box = new FernetBox("unit-test-secret");
    private static final String DUMMY_KEY = "unittest-dummy-key-1234567890";

    @BeforeEach
    void setUp() throws Exception {
        String db = tempDir.resolve("test.db").toString();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE llm_config (id INTEGER PRIMARY KEY, provider TEXT NOT NULL DEFAULT '', "
                + "base_url TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', "
                + "copilot_model TEXT NOT NULL DEFAULT '', think_model TEXT NOT NULL DEFAULT '', "
                + "api_key_enc TEXT, updated_at DATETIME NOT NULL)");
        }
        MyBatis.init(db);
    }

    private static JSONObject savePayload() {
        JSONObject payload = new JSONObject();
        payload.put("provider", "SiliconFlow");
        payload.put("base_url", "https://api.example.com/v1");
        payload.put("model", "test-model");
        payload.put("api_key", DUMMY_KEY);
        return payload;
    }

    @Test
    void getConfigEmptyWhenNoRow() {
        JSONObject config = LlmSettingsService.getConfig(box, Collections.emptyMap());
        assertEquals("", config.getString("provider"));
        assertFalse(config.getBooleanValue("has_api_key"));
        assertEquals("none", config.getString("api_key_source"));
        assertTrue(config.containsKey("updated_at"));
    }

    @Test
    void envApiKeyFallsBackWhenNoDbKey() {
        JSONObject config = LlmSettingsService.getConfig(
            box, Collections.singletonMap("LLM_API_KEY", DUMMY_KEY));
        assertTrue(config.getBooleanValue("has_api_key"));
        assertEquals("****7890", config.getString("api_key_masked"));
        assertEquals("environment", config.getString("api_key_source"));
    }

    @Test
    void saveThenReadMasksAndHidesPlaintext() {
        LlmSettingsService.update(savePayload(), box);

        JSONObject config = LlmSettingsService.getConfig(box);
        assertEquals("siliconflow", config.getString("provider"));   // provider 转小写
        assertEquals("test-model", config.getString("model"));
        assertTrue(config.getBooleanValue("has_api_key"));
        assertEquals("****7890", config.getString("api_key_masked"));
        assertEquals("database", config.getString("api_key_source"));
        assertFalse(config.toJSONString().contains(DUMMY_KEY));       // 明文不回显

        Map<String, Object> row = LlmConfigDao.find();
        assertNotEquals(DUMMY_KEY, row.get("api_key_enc"));           // 落库为密文
        assertEquals(DUMMY_KEY, box.decrypt(String.valueOf(row.get("api_key_enc"))));
    }

    @Test
    void blankApiKeyKeepsExisting() {
        LlmSettingsService.update(savePayload(), box);
        JSONObject payload = new JSONObject();
        payload.put("model", "new-model");
        LlmSettingsService.update(payload, box);

        JSONObject config = LlmSettingsService.getConfig(box);
        assertEquals("new-model", config.getString("model"));
        assertEquals("****7890", config.getString("api_key_masked")); // 密钥保留
    }

    @Test
    void clearApiKeyRemoves() {
        LlmSettingsService.update(savePayload(), box);
        JSONObject payload = new JSONObject();
        payload.put("clear_api_key", true);
        LlmSettingsService.update(payload, box);

        JSONObject config = LlmSettingsService.getConfig(box, Collections.emptyMap());
        assertFalse(config.getBooleanValue("has_api_key"));
        assertEquals("none", config.getString("api_key_source"));
    }

    @Test
    void invalidBaseUrlRejected() {
        JSONObject payload = new JSONObject();
        payload.put("base_url", "ftp://bad");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LlmSettingsService.update(payload, box));
        assertTrue(ex.getMessage().contains("http(s)"));
    }

    @Test
    void missingEncryptionKeyRejectsPlaintextSave() {
        JSONObject payload = new JSONObject();
        payload.put("api_key", DUMMY_KEY);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LlmSettingsService.update(payload, null));
        assertTrue(ex.getMessage().contains("CONFIG_ENCRYPTION_KEY"));
    }
}
