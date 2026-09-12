package com.aiinterview.server;

import com.alibaba.fastjson2.JSONObject;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import net.dreamlu.mica.voice.asr.OnlineAsrService;
import tech.smartboot.feat.core.common.codec.websocket.CloseReason;
import tech.smartboot.feat.core.server.WebSocketRequest;
import tech.smartboot.feat.core.server.WebSocketResponse;
import tech.smartboot.feat.core.server.upgrade.websocket.WebSocketUpgrade;

/** 与原 Spring 网关 OnlineAsrWebSocketHandler 协议完全一致的 Feat 版。 */
public class AsrWebSocketUpgrade extends WebSocketUpgrade {

    private final OnlineAsrService asrService;

    private OnlineStream stream;
    private String lastTranscriptText = "";
    private String lastTranscriptType = "";

    public AsrWebSocketUpgrade(OnlineAsrService asrService) {
        this.asrService = asrService;
    }

    @Override
    public void onHandShake(WebSocketRequest request, WebSocketResponse response) {
        send(response, "ready", null);
    }

    @Override
    public void handleTextMessage(WebSocketRequest request, WebSocketResponse response, String message) {
        JSONObject payload;
        try {
            payload = JSONObject.parseObject(message);
        } catch (RuntimeException e) {
            send(response, "error", "无效的控制帧: " + message);
            return;
        }
        if (payload == null) {
            send(response, "error", "无效的控制帧: " + message);
            return;
        }
        String type = String.valueOf(payload.get("type"));
        if ("config".equals(type)) {
            send(response, "config-ack", null);
        } else if ("start".equals(type)) {
            replaceStream();
            send(response, "started", null);
        } else if ("stop".equals(type)) {
            finish(response);
        } else if ("close".equals(type)) {
            response.close();
        } else {
            send(response, "error", "未知控制帧: " + type);
        }
    }

    @Override
    public void handleBinaryMessage(WebSocketRequest request, WebSocketResponse response, byte[] data) {
        if (data.length == 0) {
            return;
        }
        if (stream == null) {
            replaceStream();
        }
        try {
            stream.acceptWaveform(Pcm16Decoder.decode(data), 16000);
            while (asrService.getRecognizer().isReady(stream)) {
                asrService.getRecognizer().decode(stream);
            }
            OnlineRecognizerResult result = asrService.getRecognizer().getResult(stream);
                boolean endpoint = asrService.getRecognizer().isEndpoint(stream);
            String eventType = endpoint ? "final" : "partial";
            publishTranscript(response, eventType, result.getText());
            if (endpoint) {
                asrService.getRecognizer().reset(stream);
                clearTranscriptCache();
            }
        } catch (RuntimeException error) {
            send(response, "error", error.getMessage());
        }
    }

    @Override
    public void onClose(WebSocketRequest request, WebSocketResponse response, CloseReason closeReason) {
        release();
    }

    private void replaceStream() {
        release();
        clearTranscriptCache();
        stream = asrService.createStream();
    }

    private void finish(WebSocketResponse response) {
        if (stream == null) {
            return;
        }
        try {
            stream.inputFinished();
            while (asrService.getRecognizer().isReady(stream)) {
                asrService.getRecognizer().decode(stream);
            }
            publishTranscript(response, "final", asrService.getRecognizer().getResult(stream).getText());
        } finally {
            release();
        }
    }

    private void release() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
    }

    private void publishTranscript(WebSocketResponse response, String type, String text) {
        if (!TranscriptRules.shouldPublishTranscript(lastTranscriptType, lastTranscriptText, type, text)) {
            return;
        }
        String normalized = text == null ? "" : text.trim();
        send(response, type, normalized);
        lastTranscriptText = normalized;
        lastTranscriptType = type;
    }

    private void clearTranscriptCache() {
        lastTranscriptText = "";
        lastTranscriptType = "";
    }

    private void send(WebSocketResponse response, String type, String value) {
        JSONObject payload = new JSONObject();
        payload.put("type", type);
        if (value != null) {
            payload.put("text", value);
        }
        try {
            synchronized (response) {
                response.sendTextMessage(payload.toJSONString());
            }
        } catch (RuntimeException ignored) {
            // 连接关闭时无法再发送任何帧，直接丢弃。
        }
    }
}
