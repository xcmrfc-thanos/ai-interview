package com.aiinterview.server.copilot;

/**
 * Copilot WebSocket 音频帧解析结果。
 * v1：整帧为裸 PCM；v2：8 字节头 + PCM（magic AI + version + source + sequence）。
 */
public final class CopilotAudioFrame {

    /** v2 帧 magic 第一字节 'A'。 */
    public static final byte MAGIC_A = 0x41;
    /** v2 帧 magic 第二字节 'I'。 */
    public static final byte MAGIC_I = 0x49;
    /** 当前支持的 v2 协议版本号。 */
    public static final int PROTOCOL_VERSION = 2;
    /** v2 帧头长度（字节）。 */
    public static final int V2_HEADER_SIZE = 8;

    private final int protocolVersion;
    private final int sourceCode;
    private final long sequence;
    private final byte[] pcm;

    private CopilotAudioFrame(int protocolVersion, int sourceCode, long sequence, byte[] pcm) {
        this.protocolVersion = protocolVersion;
        this.sourceCode = sourceCode;
        this.sequence = sequence;
        this.pcm = pcm;
    }

    /** 构造 v1 裸 PCM 帧（无序列号）。 */
    public static CopilotAudioFrame legacy(byte[] pcm) {
        return new CopilotAudioFrame(1, -1, -1L, pcm == null ? new byte[0] : pcm);
    }

    /** 构造 v2 帧。 */
    public static CopilotAudioFrame v2(int sourceCode, long sequence, byte[] pcm) {
        return new CopilotAudioFrame(PROTOCOL_VERSION, sourceCode, sequence, pcm == null ? new byte[0] : pcm);
    }

    /**
     * 解析二进制 WebSocket 载荷：识别 v2 头，否则按 v1 裸 PCM 处理。
     */
    public static CopilotAudioFrame parse(byte[] data) {
        if (data == null || data.length == 0) {
            return legacy(new byte[0]);
        }
        if (data.length >= V2_HEADER_SIZE
            && data[0] == MAGIC_A
            && data[1] == MAGIC_I
            && (data[2] & 0xFF) == PROTOCOL_VERSION) {
            int source = data[3] & 0xFF;
            long sequence = (data[4] & 0xFFL)
                | ((data[5] & 0xFFL) << 8)
                | ((data[6] & 0xFFL) << 16)
                | ((data[7] & 0xFFL) << 24);
            byte[] pcm = new byte[data.length - V2_HEADER_SIZE];
            System.arraycopy(data, V2_HEADER_SIZE, pcm, 0, pcm.length);
            return v2(source, sequence, pcm);
        }
        return legacy(data);
    }

    public boolean isV2() {
        return protocolVersion == PROTOCOL_VERSION && sequence >= 0L;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public int sourceCode() {
        return sourceCode;
    }

    public long sequence() {
        return sequence;
    }

    public byte[] pcm() {
        return pcm;
    }
}
