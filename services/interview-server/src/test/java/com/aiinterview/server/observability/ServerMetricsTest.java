package com.aiinterview.server.observability;

import com.aiinterview.server.concurrent.BoundedExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMetricsTest {

    @Test
    void snapshotIncludesRegisteredExecutor() {
        BoundedExecutor executor = new BoundedExecutor(1, 4, "metrics-test");
        ServerMetrics.registerExecutor("metrics-test", executor);
        assertTrue(ServerMetrics.snapshot().getJSONObject("executors").containsKey("metrics-test"));
    }

    @Test
    void incrementCounterAppearsInSnapshot() {
        ServerMetrics.incrementCounter("test.counter");
        assertTrue(ServerMetrics.snapshot().getJSONObject("counters").containsKey("test.counter"));
    }

    @Test
    void copilotConnectionCounterTracksLifecycle() {
        int before = ServerMetrics.snapshot().getIntValue("copilot_connections");
        ServerMetrics.copilotConnected();
        ServerMetrics.copilotConnected();
        ServerMetrics.copilotDisconnected();
        assertEquals(before + 1, ServerMetrics.snapshot().getIntValue("copilot_connections"));
    }
}
