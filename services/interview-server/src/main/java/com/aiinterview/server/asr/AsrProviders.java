package com.aiinterview.server.asr;

import com.aiinterview.server.AppConfig;
import net.dreamlu.mica.voice.asr.OnlineAsrService;

/** 按 AppConfig 创建 in-process 或 gateway ASR Provider。 */
public final class AsrProviders {

    private static volatile AsrProvider copilotProvider;

    private AsrProviders() {
    }

    /** 启动时初始化 Copilot 使用的 ASR Provider（单例）。 */
    public static void init(AppConfig config, OnlineAsrService inProcessService) {
        if (config.isGatewayAsr()) {
            copilotProvider = new GatewayAsrProvider(config);
        } else {
            copilotProvider = new InProcessAsrProvider(inProcessService);
        }
    }

    /** 返回 Copilot WebSocket 使用的 Provider。 */
    public static AsrProvider copilot() {
        if (copilotProvider == null) {
            throw new IllegalStateException("AsrProviders 未初始化");
        }
        return copilotProvider;
    }

    /** 测试或特殊场景直接构造 Provider。 */
    public static AsrProvider create(AppConfig config, OnlineAsrService inProcessService) {
        if (config.isGatewayAsr()) {
            return new GatewayAsrProvider(config);
        }
        return new InProcessAsrProvider(inProcessService);
    }
}
