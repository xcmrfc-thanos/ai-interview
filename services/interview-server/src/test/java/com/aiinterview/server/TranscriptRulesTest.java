package com.aiinterview.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptRulesTest {

    @Test
    void publishesOnlyChangedTranscriptTextButAllowsPartialToFinalTransition() {
        // 完全相同内容跳帧
        assertFalse(TranscriptRules.shouldPublishTranscript(
            "partial", "请介绍一下", "partial", "请介绍一下"));
        // partial→final 变化了就发布
        assertTrue(TranscriptRules.shouldPublishTranscript(
            "partial", "请介绍一下", "final", "请介绍一下"));
        // partial 内容变化了发布
        assertTrue(TranscriptRules.shouldPublishTranscript(
            "partial", "请介绍一下", "partial", "请介绍一下自己"));
    }
}
