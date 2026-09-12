package com.aiinterview.server;

import net.dreamlu.mica.voice.asr.OnlineAsrService;
import net.dreamlu.mica.voice.config.MicaVoiceConfig;
import net.dreamlu.mica.voice.config.OnlineAsrConfig;
import net.dreamlu.mica.voice.config.OnlineAsrConfig.ModelType;
import net.dreamlu.mica.voice.core.MicaVoice;

/** 进程内唯一的 mica-voice 在线 ASR 引擎；不依赖 Spring。 */
public final class AsrEngine implements AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AsrEngine.class);

    private static final AsrEngine INSTANCE = new AsrEngine();

    private OnlineAsrService onlineAsrService;

    private AsrEngine() {
    }

    public static AsrEngine getInstance() {
        return INSTANCE;
    }

    /** 首次访问时按 AppConfig 构建 OnlineAsrService。 */
    public synchronized OnlineAsrService onlineAsr(AppConfig config) {
        if (onlineAsrService == null) {
            MicaVoiceConfig props = MicaVoiceConfig.builder()
                .modelsDir(config.modelsDir())
                .threads(2)
                .build();
            OnlineAsrConfig online = OnlineAsrConfig.builder()
                .modelDirName(config.onlineModelDirName())
                .modelType(ModelType.X_ASR)
                .enableEndpoint(true)
                .endpointRule1MinTrailingSilence(1.8)
                .endpointRule2MinTrailingSilence(1.5)
                .endpointRule3MinUtteranceLength(1.5)
                .chunkSize(1600)
                .build();
            onlineAsrService = MicaVoice.onlineAsrTyped(props, online);
            log.info("OnlineAsrService created, modelDir={}", config.onlineModelDirName());
        }
        return onlineAsrService;
    }

    /** ASR 模型是否已完成首次加载（预热或懒加载后）。 */
    public synchronized boolean isReady() {
        return onlineAsrService != null;
    }

    @Override
    public synchronized void close() {
        if (onlineAsrService != null) {
            onlineAsrService.close();
            onlineAsrService = null;
            log.info("OnlineAsrService closed");
        }
    }
}
