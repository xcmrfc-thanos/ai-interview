package com.aiinterview.server.svc;

import com.aiinterview.server.FernetBox;
import com.aiinterview.server.db.LlmConfigDao;

import java.util.Map;
import java.util.function.Supplier;

/** LLM 生效配置提供器：.env 默认 + llm_config 表逐字段覆盖（对应 Python utils/llm_config.py）。 */
public final class LlmConfigProvider {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmConfigProvider.class);

    public static final String DEFAULT_MODEL = "THUDM/GLM-Z1-9B-0414";
    public static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";
    public static final String ARK_DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/coding/v3";
    public static final String ARK_DEFAULT_COPILOT_MODEL = "deepseek-v4-flash";

    private static final long TTL_NANOS = 5_000_000_000L;

    private static volatile LlmConfigProvider instance = new LlmConfigProvider();

    private final Map<String, String> env;
    private final Supplier<Map<String, Object>> rowSource;
    private final Supplier<FernetBox> boxSource;
    private volatile EffectiveConfig cache;
    private volatile long cacheAtNanos;

    private LlmConfigProvider() {
        this(System.getenv(), LlmConfigDao::find, FernetBox::fromEnv);
    }

    /** 测试注入：自定义 env 与行来源（包内可见），解密密钥取真实 env。 */
    LlmConfigProvider(Map<String, String> env, Supplier<Map<String, Object>> rowSource) {
        this(env, rowSource, FernetBox::fromEnv);
    }

    /** 测试注入：自定义 env、行来源与解密密钥（包内可见）。 */
    LlmConfigProvider(Map<String, String> env, Supplier<Map<String, Object>> rowSource,
                      Supplier<FernetBox> boxSource) {
        this.env = env;
        this.rowSource = rowSource;
        this.boxSource = boxSource;
    }

    public static LlmConfigProvider instance() {
        return instance;
    }

    /** 生效配置；5s TTL 缓存内不重复读库。 */
    public EffectiveConfig effective() {
        EffectiveConfig cached = cache;
        if (cached != null && System.nanoTime() - cacheAtNanos < TTL_NANOS) {
            return cached;
        }
        EffectiveConfig computed = compute();
        cache = computed;
        cacheAtNanos = System.nanoTime();
        return computed;
    }

    /** 配置保存后调用：使下次 effective() 重新读库（对应 Python invalidate_llm_config_cache）。 */
    public void invalidate() {
        cache = null;
    }

    private EffectiveConfig compute() {
        String provider = firstNonBlank(env.get("LLM_PROVIDER"), "siliconflow").toLowerCase();
        String model = firstNonBlank(env.get("LLM_MODEL"), DEFAULT_MODEL);
        String baseUrl;
        String copilotModel;
        if ("ark".equals(provider)) {
            baseUrl = firstNonBlank(env.get("LLM_BASE_URL"), ARK_DEFAULT_BASE_URL);
            copilotModel = firstNonBlank(env.get("LLM_COPILOT_MODEL"), ARK_DEFAULT_COPILOT_MODEL);
        } else {
            baseUrl = firstNonBlank(env.get("LLM_BASE_URL"), DEFAULT_BASE_URL);
            copilotModel = firstNonBlank(env.get("LLM_COPILOT_MODEL"), model);
        }
        String thinkModel = firstNonBlank(env.get("LLM_THINK_MODEL"), model);
        String apiKey = env.get("LLM_API_KEY");
        String apiKeySource = (apiKey != null && !apiKey.trim().isEmpty()) ? "environment" : "none";

        Map<String, Object> row = null;
        try {
            row = rowSource.get();
        } catch (Exception e) {
            log.warn("读取 llm_config 失败，回退 .env 配置: {}", e.toString());
        }
        if (row != null) {
            provider = firstNonBlank(str(row.get("provider")), provider);
            baseUrl = firstNonBlank(str(row.get("base_url")), baseUrl);
            model = firstNonBlank(str(row.get("model")), model);
            copilotModel = firstNonBlank(str(row.get("copilot_model")), copilotModel);
            thinkModel = firstNonBlank(str(row.get("think_model")), thinkModel);
            String decrypted = decrypt(str(row.get("api_key_enc")));
            if (decrypted != null) {
                apiKey = decrypted;
            }
            if (decrypted != null || hasAnyDbTextValue(row)) {
                apiKeySource = "database";
            }
        }
        return new EffectiveConfig(provider, baseUrl, model, copilotModel, thinkModel, apiKey, apiKeySource);
    }

    private String decrypt(String apiKeyEnc) {
        if (apiKeyEnc == null || apiKeyEnc.isEmpty()) {
            return null;
        }
        FernetBox box = boxSource.get();
        return box == null ? null : box.decrypt(apiKeyEnc);
    }

    private static boolean hasAnyDbTextValue(Map<String, Object> row) {
        return firstNonBlank(str(row.get("provider")), null) != null
            || firstNonBlank(str(row.get("base_url")), null) != null
            || firstNonBlank(str(row.get("model")), null) != null
            || firstNonBlank(str(row.get("copilot_model")), null) != null
            || firstNonBlank(str(row.get("think_model")), null) != null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }
}
