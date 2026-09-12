package com.aiinterview.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Pcm16DecoderTest {

    @Test
    void decodesLittleEndianPcm16Samples() {
        byte[] pcm = new byte[] {0x00, 0x00, 0x00, (byte) 0x80, (byte) 0xFF, 0x7F};
        assertArrayEquals(new float[] {0.0f, -1.0f, 0.999969482421875f}, Pcm16Decoder.decode(pcm));
    }

    @Test
    void rejectsOddLengthPayload() {
        assertThrows(IllegalArgumentException.class, () -> Pcm16Decoder.decode(new byte[] {0x00}));
    }
}
