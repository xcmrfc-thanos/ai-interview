package com.aiinterview.server;

import java.io.File;
import java.util.Map;

public final class AppConfig {

    private final int port;
    private final String host;
    private final String modelsDir;
    private final String onlineModelDirName;
    private final String llmApiKey;
    private final String llmBaseUrl;
    private final String llmModel;
    private final String dbPath;
    private final String uploadDir;
    private final long llmTimeoutSeconds;
    private final boolean cookieSecure;
    private final int answerExecutorThreads;
    private final int prepExecutorThreads;
    private final String asrProvider;
    private final String asrGatewayUrl;
    private final long asrGatewayConnectTimeoutMs;
    private final long asrGatewayReconnectDelayMs;

    private AppConfig(int port, String host, String modelsDir, String onlineModelDirName,
                      String llmApiKey, String llmBaseUrl, String llmModel,
                      String dbPath, String uploadDir, long llmTimeoutSeconds,
                      boolean cookieSecure, int answerExecutorThreads, int prepExecutorThreads,
                      String asrProvider, String asrGatewayUrl,
                      long asrGatewayConnectTimeoutMs, long asrGatewayReconnectDelayMs) {
        this.port = port;
        this.host = host;
        this.modelsDir = modelsDir;
        this.onlineModelDirName = onlineModelDirName;
        this.llmApiKey = llmApiKey;
        this.llmBaseUrl = llmBaseUrl;
        this.llmModel = llmModel;
        this.dbPath = dbPath;
        this.uploadDir = uploadDir;
        this.llmTimeoutSeconds = llmTimeoutSeconds;
        this.cookieSecure = cookieSecure;
        this.answerExecutorThreads = answerExecutorThreads;
        this.prepExecutorThreads = prepExecutorThreads;
        this.asrProvider = asrProvider;
        this.asrGatewayUrl = asrGatewayUrl;
        this.asrGatewayConnectTimeoutMs = asrGatewayConnectTimeoutMs;
        this.asrGatewayReconnectDelayMs = asrGatewayReconnectDelayMs;
    }

    public static AppConfig load() {
        Map<String, String> env = System.getenv();
        String projectRoot = System.getProperty("user.dir");
        int port = Integer.parseInt(firstNonBlank(env.get("SERVER_PORT"), "18081"));
        String host = firstNonBlank(env.get("SERVER_HOST"), "127.0.0.1");
        String modelsDir = firstNonBlank(env.get("MICA_VOICE_MODELS_DIR"), "third_party/mica-voice/models");
        String onlineModelDirName = firstNonBlank(env.get("MICA_VOICE_ONLINE_MODEL"), "x-asr-zh-en-chunk-960ms");
        String llmApiKey = env.get("LLM_API_KEY");
        String llmBaseUrl = firstNonBlank(env.get("LLM_BASE_URL"), "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String llmModel = firstNonBlank(env.get("LLM_MODEL"), "qwen-plus");
        String dbPath = firstNonBlank(env.get("AI_INTERVIEW_DATABASE_URL"), "instance/ai_interview_local.db");
        if (dbPath.startsWith("sqlite:///")) {
            dbPath = dbPath.substring("sqlite:///".length());
        }
        dbPath = resolveProjectPath(projectRoot, dbPath);
        String uploadDir = firstNonBlank(env.get("UPLOAD_DIR"), "uploads");
        uploadDir = resolveProjectPath(projectRoot, uploadDir);
        long llmTimeoutSeconds = parseLong(firstNonBlank(env.get("LLM_TIMEOUT_SECONDS"), "45"), 45);
        boolean cookieSecure = "true".equalsIgnoreCase(firstNonBlank(env.get("COOKIE_SECURE"), "false"));
        int answerExecutorThreads = parseInt(firstNonBlank(env.get("ANSWER_EXECUTOR_THREADS"), "4"), 4);
        int prepExecutorThreads = parseInt(firstNonBlank(env.get("PREP_EXECUTOR_THREADS"), "2"), 2);
        String asrProvider = resolveAsrProvider(env, modelsDir, onlineModelDirName);
        String asrGatewayUrl = firstNonBlank(env.get("ASR_GATEWAY_URL"),
            "ws://127.0.0.1:18080/mica/voice/ws/online-asr");
        long asrGatewayConnectTimeoutMs = parseLong(firstNonBlank(env.get("ASR_GATEWAY_CONNECT_TIMEOUT_MS"), "5000"), 5000);
        long asrGatewayReconnectDelayMs = parseLong(firstNonBlank(env.get("ASR_GATEWAY_RECONNECT_DELAY_MS"), "1000"), 1000);
        return new AppConfig(port, host, modelsDir, onlineModelDirName, llmApiKey, llmBaseUrl, llmModel,
            dbPath, uploadDir, llmTimeoutSeconds, cookieSecure,
            answerExecutorThreads, prepExecutorThreads,
            asrProvider, asrGatewayUrl, asrGatewayConnectTimeoutMs, asrGatewayReconnectDelayMs);
    }

    /**
     * 解析 ASR 后端：优先读 ASR_BACKEND（gateway/builtin/auto，与 py 端共用的项目 .env 开关），
     * 未设置时回落旧的 ASR_PROVIDER（in_process/gateway）。
     * auto 与 py 端语义一致：本地模型目录存在则进程内，否则回退独立网关。
     */
    private static String resolveAsrProvider(Map<String, String> env, String modelsDir, String onlineModelDirName) {
        String backend = firstNonBlank(env.get("ASR_BACKEND"), "").toLowerCase();
        if (backend.isEmpty()) {
            return firstNonBlank(env.get("ASR_PROVIDER"), "in_process").toLowerCase();
        }
        switch (backend) {
            case "gateway":
                return "gateway";
            case "builtin":
                return "in_process";
            case "auto":
                boolean localModel = new File(modelsDir, onlineModelDirName).isDirectory();
                return localModel ? "in_process" : "gateway";
            default:
                throw new IllegalStateException(
                    "未知 ASR_BACKEND: " + backend + "（支持 gateway / builtin / auto）");
        }
    }

    /** 测试入口：包内可见，供 AppConfigTest 直接验证解析逻辑。 */
    static String resolveAsrProviderForTest(Map<String, String> env, String modelsDir, String onlineModelDirName) {
        return resolveAsrProvider(env, modelsDir, onlineModelDirName);
    }

    /** 启动前校验：in_process 需本地模型；gateway 需网关 URL。 */
    public void validate() {
        if (isGatewayAsr()) {
            if (asrGatewayUrl == null || asrGatewayUrl.trim().isEmpty()) {
                throw new IllegalStateException("ASR_PROVIDER=gateway 时必须设置 ASR_GATEWAY_URL");
            }
            return;
        }
        if (!isInProcessAsr()) {
            throw new IllegalStateException("未知 ASR_PROVIDER: " + asrProvider + "（支持 in_process / gateway）");
        }
        validateLocalModel();
    }

    /** 校验本地 mica-voice 在线模型目录。 */
    private void validateLocalModel() {
        File modelDir = new File(modelsDir, onlineModelDirName);
        if (!modelDir.isDirectory()) {
            throw new IllegalStateException(
                "在线 ASR 模型目录不存在: " + modelDir.getAbsolutePath()
                    + System.lineSeparator()
                    + "请下载模型后重试（见 README）："
                    + "powershell -File third_party/mica-voice/models/scripts/download-models.ps1 x-asr"
                    + System.lineSeparator()
                    + "并把 MICA_VOICE_MODELS_DIR 指向包含 " + onlineModelDirName + " 的目录");
        }
    }

    public int port() { return port; }
    public String host() { return host; }
    public String modelsDir() { return modelsDir; }
    public String onlineModelDirName() { return onlineModelDirName; }
    public String llmApiKey() { return llmApiKey; }
    public String llmBaseUrl() { return llmBaseUrl; }
    public String llmModel() { return llmModel; }
    public String dbPath() { return dbPath; }
    public String uploadDir() { return uploadDir; }
    public long llmTimeoutSeconds() { return llmTimeoutSeconds; }
    public boolean cookieSecure() { return cookieSecure; }
    public int answerExecutorThreads() { return answerExecutorThreads; }
    public int prepExecutorThreads() { return prepExecutorThreads; }
    public String asrProvider() { return asrProvider; }
    public String asrGatewayUrl() { return asrGatewayUrl; }
    public long asrGatewayConnectTimeoutMs() { return asrGatewayConnectTimeoutMs; }
    public long asrGatewayReconnectDelayMs() { return asrGatewayReconnectDelayMs; }

    /** 是否使用独立 mica-voice-gateway。 */
    public boolean isGatewayAsr() {
        return "gateway".equalsIgnoreCase(asrProvider);
    }

    /** 是否使用进程内 mica-voice。 */
    public boolean isInProcessAsr() {
        return "in_process".equalsIgnoreCase(asrProvider) || asrProvider == null || asrProvider.isEmpty();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }

    /**
     * 解析项目相对路径：从 user.dir（start.ps1 的 Push-Location 使其为
     * services/interview-server）逐层向上，找到含 app.py 的项目根。
     */
    private static String resolveProjectPath(String userDir, String path) {
        java.io.File file = new java.io.File(path);
        if (file.isAbsolute()) {
            return file.getAbsolutePath();
        }
        java.io.File dir = new java.io.File(userDir).getAbsoluteFile();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            if (new java.io.File(dir, "app.py").isFile()) {
                return new java.io.File(dir, path).getAbsolutePath();
            }
            dir = dir.getParentFile();
        }
        return new java.io.File(userDir, path).getAbsolutePath();
    }
}
