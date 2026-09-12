package com.aiinterview.server.copilot;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 面试官话轮边界判定（对应 Python utterance_service.py 核心规则）。
 */
public final class UtteranceAssembler {

    private static final Set<String> ACKNOWLEDGEMENTS = new HashSet<>(Arrays.asList(
        "好", "好的", "嗯", "嗯嗯", "对", "是的", "可以", "明白", "收到"
    ));
    private static final Pattern QUESTION_FEATURES = Pattern.compile(
        "[?？]$|^(请|能否|可以|说说|谈谈|介绍|解释|描述|为什么|怎么|如何|什么|哪些|是否|有没有)|"
            + "(吗|呢|么|为什么|怎么|如何|什么|哪些|是否|区别|原理|场景|经验)$|"
            + "(介绍.*(自己|个人)|自我介绍|说一下|讲一下)"
    );
    private static final Pattern QUESTION_PREFIXES = Pattern.compile(
        "^(请|能否|可以|说说|谈谈|介绍|解释|描述|为什么|怎么|如何|什么|哪些|是否|有没有)"
    );
    private static final Set<String> DIRECT_PROMPTS = new HashSet<>(Arrays.asList(
        "请介绍一下自己", "自我介绍"
    ));
    private static final int SHORT_PREFIX_LIMIT = 7;
    private static final long SILENCE_MS = 800L;

    private String partial = "";
    private String pendingText = "";
    private String lastFinal = "";
    private long lastActivityMs = 0L;

    /** 重置连接级话轮状态。 */
    public void reset() {
        partial = "";
        pendingText = "";
        lastFinal = "";
        lastActivityMs = 0L;
    }

    /**
     * 处理 ASR final 转写，决定是否构成完整面试官问句。
     *
     * @param text 转写文本
     * @param speaker candidate / interviewer
     * @param nowMs 当前时间戳（毫秒）
     */
    public UtteranceDecision acceptFinal(String text, String speaker, long nowMs) {
        String normalized = normalize(text);
        if (!"interviewer".equals(speaker)) {
            return UtteranceDecision.ignore("user_speech", normalized);
        }
        if (normalized.isEmpty()) {
            return UtteranceDecision.ignore("empty_text", normalized);
        }
        if (ACKNOWLEDGEMENTS.contains(normalized)) {
            return UtteranceDecision.ignore("short_acknowledgement", normalized);
        }

        normalized = mergeWithPartial(partial, normalized);
        if (!pendingText.isEmpty()) {
            long elapsed = nowMs - lastActivityMs;
            normalized = joinSegments(pendingText, normalized);
            if (elapsed < SILENCE_MS && !normalized.matches(".*[?？]$")) {
                pendingText = normalized;
                partial = normalized;
                lastActivityMs = nowMs;
                return UtteranceDecision.update("awaiting_silence", normalized);
            }
            pendingText = "";
        }
        if (normalized.equals(lastFinal)) {
            return UtteranceDecision.ignore("duplicate_final", normalized);
        }
        if (isShortQuestionPrefix(normalized)) {
            pendingText = normalized;
            partial = normalized;
            lastActivityMs = nowMs;
            return UtteranceDecision.update("awaiting_context", normalized);
        }
        if (QUESTION_FEATURES.matcher(normalized).find()) {
            lastFinal = normalized;
            partial = "";
            return UtteranceDecision.complete("question_feature", normalized);
        }
        long activity = lastActivityMs == 0L ? nowMs : lastActivityMs;
        if (nowMs - activity >= SILENCE_MS) {
            lastFinal = normalized;
            partial = "";
            return UtteranceDecision.complete("silence_boundary", normalized);
        }
        partial = normalized;
        lastActivityMs = activity;
        return UtteranceDecision.update("awaiting_silence", normalized);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static boolean isShortQuestionPrefix(String text) {
        return !DIRECT_PROMPTS.contains(text)
            && text.length() <= SHORT_PREFIX_LIMIT
            && QUESTION_PREFIXES.matcher(text).find()
            && !text.matches(".*[?？]$");
    }

    private static String joinSegments(String first, String second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        if (first.endsWith(second)) {
            return first;
        }
        if (second.startsWith(first)) {
            return second;
        }
        boolean needsSpace = first.endsWith(" ") || (Character.isLetterOrDigit(first.charAt(first.length() - 1))
            && Character.isLetterOrDigit(second.charAt(0)));
        return needsSpace ? first + " " + second : first + second;
    }

    private static String mergeWithPartial(String partialText, String finalText) {
        String left = normalize(partialText);
        String right = normalize(finalText);
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        if (left.endsWith(right)) {
            return left;
        }
        if (right.startsWith(left)) {
            return right;
        }
        return right;
    }
}
