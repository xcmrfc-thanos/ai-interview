package com.aiinterview.server.asr;

/**
 * 单路 Copilot 音频会话：接受 PCM 帧并在 worker 线程异步解码。
 */
public interface AsrSession extends AutoCloseable {

    /** 入队 PCM16 S16LE/16kHz/mono 帧；I/O 线程立即返回。 */
    void acceptPcm(byte[] pcm);

    @Override
    void close();
}
