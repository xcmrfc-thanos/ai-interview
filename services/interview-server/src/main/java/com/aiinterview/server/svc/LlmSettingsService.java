package com.aiinterview.server.svc;

import com.aiinterview.server.FernetBox;
import com.aiinterview.server.db.LlmConfigDao;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;

/** 模型设置读写（对应 Python routes/route_settings.py 的 GET/PUT 逻辑）。 */
public final class LlmSettingsService {

    private static final String[] TEXT_FIELDS = {"provider", "base_url", "model", "copilot_model", "think_model"};

    private LlmSettingsService() {
    }

    /** GET 响应 config 载荷：掩码回显，明文/密文不出现。 */
    public static JSONObject getConfig() {
        return getConfig(FernetBox.fromEnv(), System.getenv());
    }

    /** 包内重载：测试可注入 FernetBox。 */
    static JSONObject getConfig(FernetBox box) {
        return getConfig(box, System.getenv());
    }

    /** 密钥展示与运行时取值保持一致：数据库密钥优先，未存库时回退展示 .env 的 LLM_API_KEY。 */
    static JSONObject getConfig(FernetBox box, Map<String, String> env) {
        JSONObject payload = new JSONObject();
        payload.put("provider", "");
        payload.put("base_url", "");
        payload.put("model", "");
        payload.put("copilot_model", "");
        payload.put("think_model", "");
        payload.put("has_api_key", false);
        payload.put("api_key_masked", "");
        payload.put("api_key_source", "none");
        payload.put("updated_at", null);
        Map<String, Object> row = null;
        try {
            row = LlmConfigDao.find();
        } catch (Exception ignored) {
            // 表缺失等：按未配置处理
        }
        if (row != null) {
            payload.put("provider", nz(row.get("provider")));
            payload.put("base_url", nz(row.get("base_url")));
            payload.put("model", nz(row.get("model")));
            payload.put("copilot_model", nz(row.get("copilot_model")));
            payload.put("think_model", nz(row.get("think_model")));
            payload.put("updated_at", row.get("updated_at"));
        }
        boolean fromDb = row != null;
        String key = fromDb ? decryptQuietly(box, nz(row.get("api_key_enc"))) : null;
        if (key == null || key.isEmpty()) {
            fromDb = false;
            key = nz(env.get("LLM_API_KEY"));
        }
        boolean hasKey = key != null && !key.isEmpty();
        payload.put("has_api_key", hasKey);
        payload.put("api_key_masked", FernetBox.mask(key));
        payload.put("api_key_source", hasKey ? (fromDb ? "database" : "environment") : "none");
        return payload;
    }

    /** PUT：校验并写入；校验失败抛 IllegalArgumentException(message)（路由层转 400）。 */
    public static void update(JSONObject payload) {
        update(payload, FernetBox.fromEnv());
    }

    /** 包内重载：测试可注入 FernetBox（null = 无加密密钥）。 */
    static void update(JSONObject payload, FernetBox box) {
        Map<String, Object> row;
        try {
            row = LlmConfigDao.find();
        } catch (Exception e) {
            row = null;
        }
        String provider = row == null ? "" : nz(row.get("provider"));
        String baseUrl = row == null ? "" : nz(row.get("base_url"));
        String model = row == null ? "" : nz(row.get("model"));
        String copilotModel = row == null ? "" : nz(row.get("copilot_model"));
        String thinkModel = row == null ? "" : nz(row.get("think_model"));
        String apiKeyEnc = row == null ? null : nz(row.get("api_key_enc"));

        for (String field : TEXT_FIELDS) {
            if (!payload.containsKey(field)) {
                continue;
            }
            String value = nz(payload.get(field)).trim();
            if ("base_url".equals(field) && !value.isEmpty()
                && !value.toLowerCase().startsWith("http://")
                && !value.toLowerCase().startsWith("https://")) {
                throw new IllegalArgumentException("Base URL 必须以 http(s):// 开头");
            }
            if ("provider".equals(field)) {
                value = value.toLowerCase();
            }
            switch (field) {
                case "provider":
                    provider = value;
                    break;
                case "base_url":
                    baseUrl = value;
                    break;
                case "model":
                    model = value;
                    break;
                case "copilot_model":
                    copilotModel = value;
                    break;
                case "think_model":
                    thinkModel = value;
                    break;
                default:
                    break;
            }
        }

        if (isTruthy(payload.get("clear_api_key"))) {
            apiKeyEnc = null;
        }
        String apiKey = nz(payload.get("api_key")).trim();
        if (!apiKey.isEmpty()) {
            if (box == null) {
                throw new IllegalArgumentException(
                    "未配置加密密钥（.env 需设置 CONFIG_ENCRYPTION_KEY 或 APP_SECRET_KEY），拒绝明文保存 API Key");
            }
            String token = box.encrypt(apiKey);
            if (token == null) {
                throw new IllegalArgumentException("API Key 加密失败，请检查加密密钥配置");
            }
            apiKeyEnc = token;
        }

        LlmConfigDao.save(provider, baseUrl, model, copilotModel, thinkModel, apiKeyEnc);
        LlmConfigProvider.instance().invalidate();
    }

    private static boolean isTruthy(Object value) {
        return value != null && (Boolean.TRUE.equals(value)
            || (value instanceof String && ("1".equals(value)
                || "true".equalsIgnoreCase((String) value))));
    }

    private static String decryptQuietly(FernetBox box, String token) {
        if (token == null || token.isEmpty() || box == null) {
            return null;
        }
        return box.decrypt(token);
    }

    private static String nz(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
