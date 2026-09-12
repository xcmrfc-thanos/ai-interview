package com.aiinterview.server;

public final class TranscriptRules {

    private TranscriptRules() {
    }

    /**
     * 判断是否应将转写片段作为新帧发布。
     * 空内容不发布；内容或类型发生变化即视为新帧。
     */
    public static boolean shouldPublishTranscript(
        String previousType, String previousText, String nextType, String nextText
    ) {
        String previous = previousText == null ? "" : previousText.trim();
        String next = nextText == null ? "" : nextText.trim();
        if (next.isEmpty()) {
            return false;
        }
        // 内容或类型变化了就是新帧
        return !next.equals(previous) || !String.valueOf(nextType).equals(String.valueOf(previousType));
    }
}
