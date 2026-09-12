package com.aiinterview.server.web;

import com.alibaba.fastjson2.JSONArray;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopilotWsPolicyTest {

    @Test
    void normalizeSourceDefaultsToMicrophoneWhenMissing() {
        assertEquals("microphone", CopilotWsPolicy.normalizeSource(null));
        assertEquals("microphone", CopilotWsPolicy.normalizeSource(new JSONArray()));
    }

    @Test
    void normalizeSourceMixedFromExplicitOrDualStreams() {
        JSONArray mixed = new JSONArray();
        mixed.add("mixed");
        assertEquals("mixed", CopilotWsPolicy.normalizeSource(mixed));

        JSONArray dual = new JSONArray();
        dual.add("local");
        dual.add("remote");
        assertEquals("mixed", CopilotWsPolicy.normalizeSource(dual));
    }

    @Test
    void resolveSpeakerFromCaptureSource() {
        assertEquals("candidate", CopilotWsPolicy.resolveSpeaker("microphone", "auto"));
        assertEquals("interviewer", CopilotWsPolicy.resolveSpeaker("display", "auto"));
        assertEquals("interviewer", CopilotWsPolicy.resolveSpeaker("mixed", "interviewer"));
        assertEquals("candidate", CopilotWsPolicy.resolveSpeaker("mixed", "auto"));
    }

    @Test
    void resolveSpeakerForAudioSourceMatchesRoleModes() {
        assertEquals("candidate", CopilotWsPolicy.resolveSpeakerForAudioSource("microphone", "local", null, "auto"));
        assertEquals("interviewer", CopilotWsPolicy.resolveSpeakerForAudioSource("microphone", "remote", true, "auto"));
        assertEquals("interviewer", CopilotWsPolicy.resolveSpeakerForAudioSource("display", "auto", true, "auto"));
        assertEquals("candidate", CopilotWsPolicy.resolveSpeakerForAudioSource("microphone", "auto", true, "auto"));
        assertEquals("interviewer", CopilotWsPolicy.resolveSpeakerForAudioSource("microphone", "auto", false, "auto"));
        assertEquals("interviewer", CopilotWsPolicy.resolveSpeakerForAudioSource("microphone", "auto", null, "auto"));
    }

    @Test
    void defaultSourcesForMode() {
        assertEquals(Collections.singletonList("microphone"), CopilotWsPolicy.defaultSourcesForMode("local"));
        assertEquals(Arrays.asList("microphone", "display"), CopilotWsPolicy.defaultSourcesForMode("auto"));
        assertEquals(Arrays.asList("microphone", "display"), CopilotWsPolicy.defaultSourcesForMode("remote"));
    }

    @Test
    void sourceFromFrameCode() {
        assertEquals("microphone", CopilotWsPolicy.sourceFromFrameCode(1));
        assertEquals("display", CopilotWsPolicy.sourceFromFrameCode(2));
        assertEquals("mixed", CopilotWsPolicy.sourceFromFrameCode(0));
    }
}
