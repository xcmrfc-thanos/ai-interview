package com.aiinterview.server.web;

import tech.smartboot.feat.core.server.WebSocketResponse;

/**
 * 单连接 WebSocket 出站事件串行发送，避免多线程交错 flush。
 */
public final class WsEventSink {

    private final WebSocketResponse response;

    public WsEventSink(WebSocketResponse response) {
        this.response = response;
    }

    /** 发送 JSON 文本帧并 flush。 */
    public void send(String json) {
        try {
            synchronized (response) {
                response.sendTextMessage(json);
                response.flush();
            }
        } catch (RuntimeException ignored) {
            // 连接已关闭时丢弃
        }
    }
}
