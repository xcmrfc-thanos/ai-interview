package com.aiinterview.server.svc;

import com.aiinterview.server.FernetBox;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmConfigProviderTest {

    private static Map<String, String> env(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void envDefaultsWhenNoDbRow() {
        LlmConfigProvider provider = new LlmConfigProvider(env(), () -> null);
        EffectiveConfig cfg = provider.effective();
        assertEquals("siliconflow", cfg.provider);
        assertEquals("https://api.siliconflow.cn/v1", cfg.baseUrl);
        assertEquals("THUDM/GLM-Z1-9B-0414", cfg.model);
        assertEquals("THUDM/GLM-Z1-9B-0414", cfg.copilotModel);
        assertEquals("THUDM/GLM-Z1-9B-0414", cfg.thinkModel);
        assertEquals("none", cfg.apiKeySource);
        assertNull(cfg.apiKey);
    }

    @Test
    void envApiKeyMarksEnvironment() {
        LlmConfigProvider provider = new LlmConfigProvider(env("LLM_API_KEY", "sk-env"), () -> null);
        assertEquals("sk-env", provider.effective().apiKey);
        assertEquals("environment", provider.effective().apiKeySource);
    }

    @Test
    void arkProviderUsesArkDefaults() {
        LlmConfigProvider provider = new LlmConfigProvider(env("LLM_PROVIDER", "ark"), () -> null);
        EffectiveConfig cfg = provider.effective();
        assertEquals("https://ark.cn-beijing.volces.com/api/coding/v3", cfg.baseUrl);
        assertEquals("deepseek-v4-flash", cfg.copilotModel);
    }

    @Test
    void dbRowOverridesEnvFieldByField() {
        String enc = new FernetBox("unit-test-secret").encrypt("unittest-dummy-key-1234567890");
        Map<String, Object> row = new HashMap<>();
        row.put("provider", "siliconflow");
        row.put("base_url", "https://db.example.com/v1");
        row.put("model", "db-model");
        row.put("copilot_model", "");
        row.put("think_model", "");
        row.put("api_key_enc", enc);
        LlmConfigProvider provider = new LlmConfigProvider(
            env("LLM_MODEL", "env-model"), () -> row, () -> new FernetBox("unit-test-secret"));
        EffectiveConfig cfg = provider.effective();
        assertEquals("https://db.example.com/v1", cfg.baseUrl);
        assertEquals("db-model", cfg.model);
        assertEquals("env-model", cfg.copilotModel);   // DB 空字段回落 env 推导值
        assertEquals("unittest-dummy-key-1234567890", cfg.apiKey);
        assertEquals("database", cfg.apiKeySource);
    }

    @Test
    void invalidateForcesReread() {
        Map<String, Object> row = new HashMap<>();
        row.put("model", "m1");
        LlmConfigProvider provider = new LlmConfigProvider(env(), () -> row);
        assertEquals("m1", provider.effective().model);
        row.put("model", "m2");
        assertEquals("m1", provider.effective().model);   // TTL 缓存内
        provider.invalidate();
        assertEquals("m2", provider.effective().model);   // 失效后重读
    }

    @Test
    void undecryptableKeyFallsBackToEnv() {
        Map<String, Object> row = new HashMap<>();
        row.put("api_key_enc", "garbage-not-fernet");
        LlmConfigProvider provider = new LlmConfigProvider(env("LLM_API_KEY", "sk-env"), () -> row);
        assertEquals("sk-env", provider.effective().apiKey);
        assertEquals("environment", provider.effective().apiKeySource);
    }
}
