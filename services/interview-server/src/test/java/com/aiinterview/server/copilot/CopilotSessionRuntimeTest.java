package com.aiinterview.server.copilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotSessionRuntimeTest {

    @Test
    void runningPauseResumeCycle() {
        CopilotSessionRuntime runtime = new CopilotSessionRuntime(1L, 9L);
        assertTrue(runtime.transition(CopilotSessionState.RUNNING));
        assertTrue(runtime.transition(CopilotSessionState.PAUSED));
        assertTrue(runtime.transition(CopilotSessionState.RUNNING));
        assertEquals(CopilotSessionState.RUNNING, runtime.state());
    }

    @Test
    void disconnectAndReconnectWithinWindow() {
        CopilotSessionRuntime runtime = new CopilotSessionRuntime(2L, 9L, 500L);
        runtime.transition(CopilotSessionState.RUNNING);
        runtime.setTurnNumber(3);
        runtime.setTranscriptVersion(7L);
        runtime.markDisconnected();
        assertEquals(CopilotSessionState.RECONNECTING, runtime.state());
        assertTrue(runtime.canReconnect());
        runtime.markReconnected();
        assertEquals(CopilotSessionState.RUNNING, runtime.state());
        assertEquals(3, runtime.turnNumber());
        assertEquals(7L, runtime.transcriptVersion());
    }

    @Test
    void reconnectWindowExpires() throws InterruptedException {
        CopilotSessionRuntime runtime = new CopilotSessionRuntime(3L, 9L, 40L);
        runtime.transition(CopilotSessionState.RUNNING);
        runtime.markDisconnected();
        Thread.sleep(60L);
        assertFalse(runtime.canReconnect());
    }

    @Test
    void duplicateAudioSequenceRejected() {
        CopilotSessionRuntime runtime = new CopilotSessionRuntime(5L, 9L);
        assertTrue(runtime.acceptAudioSequence(1L));
        assertTrue(runtime.acceptAudioSequence(2L));
        assertFalse(runtime.acceptAudioSequence(2L));
        assertFalse(runtime.acceptAudioSequence(1L));
        runtime.resetAudioSequences();
        assertTrue(runtime.acceptAudioSequence(0L));
    }

    @Test
    void endingWaitsForInflightAnswers() throws InterruptedException {
        CopilotSessionRuntime runtime = new CopilotSessionRuntime(4L, 9L);
        runtime.transition(CopilotSessionState.RUNNING);
        runtime.answerStarted();
        assertTrue(runtime.beginEnding());
        Thread drain = new Thread(() -> {
            try {
                runtime.awaitPendingAnswers(1_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        drain.start();
        Thread.sleep(80L);
        runtime.answerFinished();
        drain.join(1_000L);
        assertFalse(runtime.hasPendingAnswers());
    }
}
