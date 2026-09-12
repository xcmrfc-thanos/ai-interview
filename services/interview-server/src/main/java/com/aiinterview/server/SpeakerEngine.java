package com.aiinterview.server;

import net.dreamlu.mica.voice.config.MicaVoiceConfig;
import net.dreamlu.mica.voice.config.SpeakerConfig;
import net.dreamlu.mica.voice.core.MicaVoice;
import net.dreamlu.mica.voice.speaker.SpeakerService;

/** 进程内 mica-voice 声纹引擎（懒加载单例）。 */
public final class SpeakerEngine implements AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SpeakerEngine.class);

    private static final SpeakerEngine INSTANCE = new SpeakerEngine();

    private SpeakerService speakerService;

    private SpeakerEngine() {
    }

    public static SpeakerEngine getInstance() {
        return INSTANCE;
    }

    /** 首次访问时按 AppConfig 构建 SpeakerService。 */
    public synchronized SpeakerService speaker(AppConfig config) {
        if (speakerService == null) {
            MicaVoiceConfig props = MicaVoiceConfig.builder()
                .modelsDir(config.modelsDir())
                .threads(1)
                .build();
            SpeakerConfig speakerConfig = SpeakerConfig.builder().build();
            speakerService = MicaVoice.speaker(props, speakerConfig);
            log.info("SpeakerService created, modelsDir={}", config.modelsDir());
        }
        return speakerService;
    }

    public synchronized boolean isReady() {
        return speakerService != null;
    }

    @Override
    public synchronized void close() {
        if (speakerService != null) {
            speakerService.close();
            speakerService = null;
            log.info("SpeakerService closed");
        }
    }
}
