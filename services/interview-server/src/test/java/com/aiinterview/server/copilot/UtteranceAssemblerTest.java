package com.aiinterview.server.copilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtteranceAssemblerTest {

    @Test
    void ignoresCandidateSpeech() {
        UtteranceAssembler assembler = new UtteranceAssembler();
        UtteranceDecision decision = assembler.acceptFinal("我的回答", "candidate", 1000L);
        assertEquals(UtteranceDecision.Action.IGNORE, decision.action());
    }

    @Test
    void completesQuestionWithFeature() {
        UtteranceAssembler assembler = new UtteranceAssembler();
        UtteranceDecision decision = assembler.acceptFinal("请介绍一下你自己", "interviewer", 1000L);
        assertTrue(decision.isComplete());
        assertEquals("question_feature", decision.reason());
    }

    @Test
    void awaitsShortQuestionPrefix() {
        UtteranceAssembler assembler = new UtteranceAssembler();
        UtteranceDecision decision = assembler.acceptFinal("请介绍", "interviewer", 1000L);
        assertFalse(decision.isComplete());
        assertEquals("awaiting_context", decision.reason());
    }
}
