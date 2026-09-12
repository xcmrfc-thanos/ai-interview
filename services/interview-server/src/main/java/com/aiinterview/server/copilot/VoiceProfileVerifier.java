package com.aiinterview.server.copilot;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.SpeakerEngine;
import com.aiinterview.server.db.VoiceProfileDao;
import net.dreamlu.mica.voice.audio.AudioData;
import net.dreamlu.mica.voice.audio.AudioReaders;
import net.dreamlu.mica.voice.speaker.SpeakerService;
import net.dreamlu.mica.voice.speaker.VerificationResult;

import java.io.File;
import java.util.Map;

/**
 * 会话级声纹比对：将个人中心语音档案注册为参考 embedding，
 * 对麦克风轨最近 PCM 窗口做 verify；匹配失败视为面试官。
 */
public final class VoiceProfileVerifier implements AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VoiceProfileVerifier.class);

    /** 16kHz mono PCM16，至少 1 秒才尝试比对。 */
    private static final int SAMPLE_RATE = 16000;
    private static final int MIN_VERIFY_BYTES = SAMPLE_RATE * 2;

    private final SpeakerService speakerService;
    private final String enrolledName;
    private final boolean enrolled;

    private VoiceProfileVerifier(SpeakerService speakerService, String enrolledName, boolean enrolled) {
        this.speakerService = speakerService;
        this.enrolledName = enrolledName;
        this.enrolled = enrolled;
    }

    /**
     * 若用户已录入语音档案则尝试注册声纹；模型/文件不可用则返回 null（自动模式麦克风视为面试官）。
     */
    public static VoiceProfileVerifier tryCreate(AppConfig config, long userId) {
        Map<String, Object> profile = VoiceProfileDao.findByUserId(userId);
        if (profile == null) {
            return null;
        }
        File file = profileFile(config, profile);
        if (file == null) {
            return null;
        }
        String name = "user-" + userId;
        try {
            SpeakerService speaker = SpeakerEngine.getInstance().speaker(config);
            speaker.enroll(name, file);
            log.info("Voice profile enrolled for speaker verification userId={}", userId);
            return new VoiceProfileVerifier(speaker, name, true);
        } catch (RuntimeException error) {
            log.warn("Voice profile enroll failed userId={}: {}", userId, error.getMessage());
            return null;
        }
    }

    public boolean isEnrolled() {
        return enrolled;
    }

    /**
     * 比对最近 PCM 窗口与档案声纹。
     *
     * @return true=本人；false=不匹配或音频不足/引擎异常（均视为非本人）
     */
    public boolean verifyRecentPcm(byte[] pcm16le) {
        if (!enrolled || speakerService == null || pcm16le == null || pcm16le.length < MIN_VERIFY_BYTES) {
            return false;
        }
        try {
            AudioData audio = AudioReaders.fromPcm16(pcm16le, SAMPLE_RATE);
            VerificationResult result = speakerService.verify(enrolledName, audio);
            return result != null && result.isMatched();
        } catch (RuntimeException error) {
            log.debug("Voice verify failed for {}: {}", enrolledName, error.getMessage());
            return false;
        }
    }

    private static File profileFile(AppConfig config, Map<String, Object> profile) {
        Object storageName = profile.get("storage_name");
        if (storageName == null) {
            return null;
        }
        File dir = new File(config.uploadDir(), "voice_profiles");
        File file = new File(dir, String.valueOf(storageName));
        return file.isFile() ? file : null;
    }

    @Override
    public void close() {
        if (speakerService != null && enrolledName != null) {
            try {
                speakerService.remove(enrolledName);
            } catch (RuntimeException error) {
                log.debug("Voice profile remove failed for {}: {}", enrolledName, error.getMessage());
            }
        }
    }
}
