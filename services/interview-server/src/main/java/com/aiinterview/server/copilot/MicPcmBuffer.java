package com.aiinterview.server.copilot;

import java.util.Arrays;

/** 麦克风 PCM16 环形缓冲，保留最近若干秒音频供声纹比对。 */
public final class MicPcmBuffer {

    private final byte[] buffer;
    /** 最早有效字节的下标；size 为 0 时无意义。 */
    private int start;
    private int size;

    public MicPcmBuffer(int capacityBytes) {
        this.buffer = new byte[Math.max(0, capacityBytes)];
    }

    /** 追加 PCM 帧，超出容量时丢弃最旧数据。O(pcm.length)，与缓冲容量无关。 */
    public synchronized void append(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || buffer.length == 0) {
            return;
        }
        if (pcm.length >= buffer.length) {
            // 整帧不小于容量：只保留帧尾最近 buffer.length 字节
            System.arraycopy(pcm, pcm.length - buffer.length, buffer, 0, buffer.length);
            start = 0;
            size = buffer.length;
            return;
        }
        // 从数据尾端（模 N）写入，最多两段
        int end = (start + size) % buffer.length;
        int first = Math.min(pcm.length, buffer.length - end);
        System.arraycopy(pcm, 0, buffer, end, first);
        System.arraycopy(pcm, first, buffer, 0, pcm.length - first);
        if (size < buffer.length) {
            int newSize = size + pcm.length;
            if (newSize <= buffer.length) {
                size = newSize;
            } else {
                // 写满并淘汰被覆盖的最旧数据
                size = buffer.length;
                start = (start + newSize - buffer.length) % buffer.length;
            }
        } else {
            // 满环稳态：每追加 L 字节逻辑起点前进 L
            start = (start + pcm.length) % buffer.length;
        }
    }

    /** 返回当前缓冲快照（可能短于比对所需最小时长），按写入顺序排列。 */
    public synchronized byte[] snapshot() {
        if (start + size <= buffer.length) {
            return Arrays.copyOfRange(buffer, start, start + size);
        }
        // 数据跨数组边界：先 [start, N)，再环绕的 [0, 越界部分)
        int wrapped = start + size - buffer.length;
        byte[] out = new byte[size];
        System.arraycopy(buffer, start, out, 0, size - wrapped);
        System.arraycopy(buffer, 0, out, size - wrapped, wrapped);
        return out;
    }

    public synchronized void reset() {
        start = 0;
        size = 0;
    }

    public synchronized int size() {
        return size;
    }
}
