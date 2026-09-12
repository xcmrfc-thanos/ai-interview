package com.aiinterview.gateway;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts signed little-endian PCM16 frames to sherpa-onnx float samples. */
final class Pcm16Decoder {

    private Pcm16Decoder() {
    }

    static float[] decode(byte[] pcm) {
        if (pcm == null || pcm.length % 2 != 0) {
            throw new IllegalArgumentException("pcm16 数据长度必须是 2 的倍数");
        }
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[pcm.length / 2];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = buffer.getShort() / 32768.0f;
        }
        return samples;
    }
}
