package com.aiinterview.server.asr;

import com.alibaba.fastjson2.JSONObject;

/** mica-voice-gateway WebSocket 文本帧解析（与 AsrWebSocketUpgrade 协议一致）。 */
public final class GatewayAsrProtocol {

    private GatewayAsrProtocol() {
    }

    /**
     * 将网关 JSON 文本帧解析为 AsrResult；控制帧（ready/started 等）返回 null。
     */
    public static AsrResult parseTextMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        JSONObject payload;
        try {
            payload = JSONObject.parseObject(message);
        } catch (RuntimeException ignored) {
            return new AsrResult("error", "网关返回无效 JSON");
        }
        if (payload == null) {
            return null;
        }
        String type = payload.getString("type");
        if (type == null) {
            return null;
        }
        if ("partial".equals(type) || "final".equals(type)) {
            String text = payload.getString("text");
            return new AsrResult(type, text == null ? "" : text.trim());
        }
        if ("error".equals(type)) {
            String text = payload.getString("text");
            return new AsrResult("error", text == null ? "网关 ASR 错误" : text);
        }
        return null;
    }

    /** 是否为网关就绪/启动类控制帧。 */
    public static boolean isControlAck(String message) {
        if (message == null) {
            return false;
        }
        JSONObject payload;
        try {
            payload = JSONObject.parseObject(message);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (payload == null) {
            return false;
        }
        String type = payload.getString("type");
        return "ready".equals(type) || "config-ack".equals(type) || "started".equals(type);
    }
}
