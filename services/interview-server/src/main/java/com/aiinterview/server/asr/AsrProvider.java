package com.aiinterview.server.asr;

import java.util.function.Consumer;

/** ASR 提供方：为每个 WebSocket 连接创建独立解码会话。 */
public interface AsrProvider {

    /** 打开会话；识别结果通过 listener 异步回调。 */
    AsrSession openSession(Consumer<AsrResult> listener);
}
