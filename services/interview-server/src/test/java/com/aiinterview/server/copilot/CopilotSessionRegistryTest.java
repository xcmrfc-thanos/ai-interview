package com.aiinterview.server.copilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CopilotSessionRegistryTest {

    @Test
    void findReconnectableReturnsRuntimeWithinWindow() {
        CopilotSessionRegistry.remove(42L);
        CopilotSessionRuntime runtime = new CopilotSessionRuntime(42L, 7L, 500L);
        runtime.transition(CopilotSessionState.RUNNING);
        runtime.markDisconnected();
        CopilotSessionRegistry.keepForReconnect(runtime);
        assertSame(runtime, CopilotSessionRegistry.findReconnectable(42L, 7L));
        CopilotSessionRegistry.remove(42L);
    }

    @Test
    void findReconnectableRejectsOtherUser() {
        CopilotSessionRegistry.remove(43L);
        CopilotSessionRuntime runtime = new CopilotSessionRuntime(43L, 7L, 500L);
        runtime.transition(CopilotSessionState.RUNNING);
        runtime.markDisconnected();
        CopilotSessionRegistry.keepForReconnect(runtime);
        assertNull(CopilotSessionRegistry.findReconnectable(43L, 8L));
        CopilotSessionRegistry.remove(43L);
    }

    @Test
    void registerThenRemove() {
        CopilotSessionRegistry.remove(44L);
        CopilotSessionRuntime runtime = new CopilotSessionRuntime(44L, 1L);
        CopilotSessionRegistry.register(runtime);
        runtime.transition(CopilotSessionState.RUNNING);
        runtime.markDisconnected();
        CopilotSessionRegistry.keepForReconnect(runtime);
        assertSame(runtime, CopilotSessionRegistry.findReconnectable(44L, 1L));
        CopilotSessionRegistry.remove(44L);
        assertNull(CopilotSessionRegistry.findReconnectable(44L, 1L));
    }
}
