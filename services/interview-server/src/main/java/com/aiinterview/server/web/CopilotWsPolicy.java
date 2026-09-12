package com.aiinterview.server.web;

import com.alibaba.fastjson2.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Copilot WebSocket 采集来源与说话人策略（纯函数，便于契约测试）。
 */
public final class CopilotWsPolicy {

    private CopilotWsPolicy() {
    }

    /**
     * 归一化采集来源：mixed / microphone / display。
     * sources 缺失或空时默认 microphone。
     */
    public static String normalizeSource(JSONArray sources) {
        if (sources == null || sources.isEmpty()) {
            return "microphone";
        }
        boolean hasLocal = false;
        boolean hasRemote = false;
        for (int i = 0; i < sources.size(); i++) {
            String s = normalizeSourceToken(String.valueOf(sources.get(i)));
            if ("microphone".equals(s)) {
                hasLocal = true;
            } else if ("display".equals(s)) {
                hasRemote = true;
            } else if ("mixed".equals(s)) {
                return "mixed";
            }
        }
        return hasLocal && hasRemote ? "mixed" : hasRemote ? "display" : "microphone";
    }

    /** 解析 v2 音频帧来源码：0=mixed，1=microphone，2=display。 */
    public static String sourceFromFrameCode(int code) {
        if (code == 1) {
            return "microphone";
        }
        if (code == 2) {
            return "display";
        }
        return "mixed";
    }

    /** 将前端 sources 项归一化为 microphone / display / mixed。 */
    public static String normalizeSourceToken(String token) {
        if (token == null) {
            return "mixed";
        }
        String value = token.trim().toLowerCase();
        if ("local".equals(value) || "microphone".equals(value)) {
            return "microphone";
        }
        if ("remote".equals(value) || "display".equals(value) || "system".equals(value)) {
            return "display";
        }
        if ("mixed".equals(value)) {
            return "mixed";
        }
        return "mixed";
    }

    /** 按采集模式推导默认 ASR 会话来源列表。 */
    public static List<String> defaultSourcesForMode(String mode) {
        String normalized = mode == null ? "auto" : mode.trim().toLowerCase();
        if ("local".equals(normalized)) {
            return Collections.singletonList("microphone");
        }
        if ("remote".equals(normalized)) {
            return Arrays.asList("microphone", "display");
        }
        return Arrays.asList("microphone", "display");
    }

    /** 解析 copilot_start 应打开的 ASR 来源列表（去重、保序）。 */
    public static List<String> resolveSessionSources(JSONArray sources, String mode) {
        if (sources != null && !sources.isEmpty()) {
            Set<String> unique = new LinkedHashSet<>();
            for (int i = 0; i < sources.size(); i++) {
                unique.add(normalizeSourceToken(String.valueOf(sources.get(i))));
            }
            return new ArrayList<>(unique);
        }
        return defaultSourcesForMode(mode);
    }

    /**
     * 根据采集来源与用户设置判断有效说话人（会话级，兼容旧逻辑）。
     */
    public static String resolveSpeaker(String captureSource, String userSpeaker) {
        if ("microphone".equals(captureSource)) {
            return "candidate";
        }
        if ("display".equals(captureSource)) {
            return "interviewer";
        }
        if ("candidate".equals(userSpeaker) || "interviewer".equals(userSpeaker)) {
            return userSpeaker;
        }
        return "candidate";
    }

    /**
     * 按采集模式与音频轨来源判定说话人角色。
     * auto：系统=面试官；麦克风=声纹档案存在且匹配成功→本人，否则→面试官。
     * local：本人；remote：一律面试官。
     *
     * @param voiceProfileMatch null=未录入档案；true/false=声纹比对结果
     */
    public static String resolveSpeakerForAudioSource(
        String audioSource,
        String captureMode,
        Boolean voiceProfileMatch,
        String userSpeaker
    ) {
        String mode = captureMode == null ? "auto" : captureMode.trim().toLowerCase();
        if ("local".equals(mode)) {
            return "candidate";
        }
        if ("remote".equals(mode)) {
            return "interviewer";
        }
        if ("display".equals(audioSource)) {
            return "interviewer";
        }
        if ("microphone".equals(audioSource)) {
            if (voiceProfileMatch == null) {
                return "interviewer";
            }
            return voiceProfileMatch ? "candidate" : "interviewer";
        }
        if ("candidate".equals(userSpeaker) || "interviewer".equals(userSpeaker)) {
            return userSpeaker;
        }
        if (voiceProfileMatch == null) {
            return "interviewer";
        }
        return voiceProfileMatch ? "candidate" : "interviewer";
    }
}
