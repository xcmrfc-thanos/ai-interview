package com.aiinterview.server.asr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayAsrProtocolTest {

    @Test
    void parsesPartialTranscript() {
        AsrResult result = GatewayAsrProtocol.parseTextMessage("{\"type\":\"partial\",\"text\":\"你好\"}");
        assertEquals("partial", result.type());
        assertEquals("你好", result.text());
    }

    @Test
    void parsesFinalTranscript() {
        AsrResult result = GatewayAsrProtocol.parseTextMessage("{\"type\":\"final\",\"text\":\"请介绍自己\"}");
        assertEquals("final", result.type());
        assertEquals("请介绍自己", result.text());
    }

    @Test
    void ignoresControlFrames() {
        assertNull(GatewayAsrProtocol.parseTextMessage("{\"type\":\"started\"}"));
        assertTrue(GatewayAsrProtocol.isControlAck("{\"type\":\"config-ack\"}"));
    }
}
