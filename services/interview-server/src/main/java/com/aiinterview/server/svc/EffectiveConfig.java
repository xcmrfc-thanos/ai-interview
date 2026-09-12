package com.aiinterview.server.svc;

/** LLM 生效配置快照（env 默认 + llm_config 表逐字段覆盖后）。 */
public final class EffectiveConfig {

    public final String provider;
    public final String baseUrl;
    public final String model;
    public final String copilotModel;
    public final String thinkModel;
    /** 解密后的 API Key；未配置时为 null。 */
    public final String apiKey;
    /** database（读库覆盖）| environment（.env）| none。 */
    public final String apiKeySource;

    EffectiveConfig(String provider, String baseUrl, String model, String copilotModel,
                    String thinkModel, String apiKey, String apiKeySource) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.copilotModel = copilotModel;
        this.thinkModel = thinkModel;
        this.apiKey = apiKey;
        this.apiKeySource = apiKeySource;
    }
}
