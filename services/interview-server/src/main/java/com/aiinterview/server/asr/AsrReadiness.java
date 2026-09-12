package com.aiinterview.server.asr;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.AsrEngine;

/** ASR 就绪探针：in-process 检查本地模型，gateway 检查远程 WS。 */
public final class AsrReadiness {

    private static volatile AppConfig config;

    private AsrReadiness() {
    }

    /** 启动时注入配置。 */
    public static void init(AppConfig appConfig) {
        config = appConfig;
    }

    /** 当前 ASR 模式是否就绪。 */
    public static boolean isReady() {
        if (config == null) {
            return false;
        }
        if (config.isGatewayAsr()) {
            return GatewayAsrProvider.probeReachable(config);
        }
        return AsrEngine.getInstance().isReady();
    }

    /** 返回 ASR 模式标识（in_process / gateway）。 */
    public static String mode() {
        return config == null ? "unknown" : config.asrProvider();
    }
}
