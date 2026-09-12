package com.aiinterview.server.copilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotAudioFrameTest {

    @Test
    void legacyFrameTreatsEntireBufferAsPcm() {
        byte[] raw = new byte[] {0x01, 0x02, 0x03};
        CopilotAudioFrame frame = CopilotAudioFrame.parse(raw);
        assertFalse(frame.isV2());
        assertEquals(3, frame.pcm().length);
        assertEquals(0x01, frame.pcm()[0]);
    }

    @Test
    void v2FrameParsesHeaderAndPcm() {
        byte[] data = new byte[10];
        data[0] = CopilotAudioFrame.MAGIC_A;
        data[1] = CopilotAudioFrame.MAGIC_I;
        data[2] = 2;
        data[3] = 1;
        data[4] = 0x05;
        data[8] = (byte) 0xAB;
        data[9] = (byte) 0xCD;
        CopilotAudioFrame frame = CopilotAudioFrame.parse(data);
        assertTrue(frame.isV2());
        assertEquals(5L, frame.sequence());
        assertEquals(1, frame.sourceCode());
        assertEquals(2, frame.pcm().length);
        assertEquals((byte) 0xAB, frame.pcm()[0]);
    }
}
