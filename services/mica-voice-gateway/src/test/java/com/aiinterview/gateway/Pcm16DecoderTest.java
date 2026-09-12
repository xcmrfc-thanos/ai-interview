package com.aiinterview.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Pcm16DecoderTest {

    @Test
    void decodesLittleEndianSignedPcmSamples() {
        byte[] pcm = new byte[] {0, 0, 0, (byte) 0x80, (byte) 0xff, 0x7f};

        assertArrayEquals(
            new float[] {0.0f, -1.0f, 32767.0f / 32768.0f},
            Pcm16Decoder.decode(pcm),
            0.0001f
        );
    }

    @Test
    void rejectsOddLengthPcmPayload() {
        assertThrows(IllegalArgumentException.class, () -> Pcm16Decoder.decode(new byte[] {1}));
    }
}
