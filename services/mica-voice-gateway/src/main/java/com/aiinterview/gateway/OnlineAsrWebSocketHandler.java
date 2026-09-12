package com.aiinterview.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import net.dreamlu.mica.voice.asr.OnlineAsrService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class OnlineAsrWebSocketHandler extends TextWebSocketHandler {

    private static final int MIN_ENDPOINT_TEXT_LENGTH = 4;
    private static final String LAST_TRANSCRIPT_TEXT = "lastTranscriptText";
    private static final String LAST_TRANSCRIPT_TYPE = "lastTranscriptType";
    private static final Pattern SHORT_QUESTION_PREFIX = Pattern.compile(
        "^(请|能否|可以|说说|谈谈|介绍|解释|描述|为什么|怎么|如何|什么|哪些|是否|有没有).{0,5}$"
    );

    private final OnlineAsrService asrService;
    private final ObjectMapper mapper = new ObjectMapper();

    public OnlineAsrWebSocketHandler(OnlineAsrService asrService) {
        this.asrService = asrService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        send(session, "ready", null);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload = mapper.readValue(
            message.getPayload(), new TypeReference<Map<String, Object>>() { }
        );
        String type = String.valueOf(payload.get("type"));
        if ("config".equals(type)) {
            send(session, "config-ack", null);
        } else if ("start".equals(type)) {
            replaceStream(session);
            send(session, "started", null);
        } else if ("stop".equals(type)) {
            finish(session);
        } else if ("close".equals(type)) {
            session.close();
        } else {
            send(session, "error", "未知控制帧: " + type);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        OnlineStream stream = stream(session);
        if (stream == null) {
            replaceStream(session);
            stream = stream(session);
        }
        byte[] bytes = new byte[message.getPayloadLength()];
        message.getPayload().get(bytes);
        if (bytes.length == 0) {
            return;
        }
        try {
            stream.acceptWaveform(Pcm16Decoder.decode(bytes), 16000);
            while (asrService.getRecognizer().isReady(stream)) {
                asrService.getRecognizer().decode(stream);
            }
            OnlineRecognizerResult result = asrService.getRecognizer().getResult(stream);
            boolean endpoint = asrService.getRecognizer().isEndpoint(stream)
                && !shouldDelayEndpoint(result.getText());
            String eventType = endpoint ? "final" : "partial";
            publishTranscript(session, eventType, result.getText());
            if (endpoint) {
                asrService.getRecognizer().reset(stream);
                clearTranscriptCache(session);
            }
        } catch (RuntimeException error) {
            send(session, "error", error.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        release(session);
    }

    private void replaceStream(WebSocketSession session) {
        release(session);
        clearTranscriptCache(session);
        session.getAttributes().put("stream", asrService.createStream());
    }

    private void finish(WebSocketSession session) {
        OnlineStream current = stream(session);
        if (current == null) {
            return;
        }
        try {
            current.inputFinished();
            while (asrService.getRecognizer().isReady(current)) {
                asrService.getRecognizer().decode(current);
            }
            publishTranscript(session, "final", asrService.getRecognizer().getResult(current).getText());
        } finally {
            release(session);
        }
    }

    private OnlineStream stream(WebSocketSession session) {
        return (OnlineStream) session.getAttributes().get("stream");
    }

    private void release(WebSocketSession session) {
        OnlineStream current = stream(session);
        if (current != null) {
            current.release();
            session.getAttributes().remove("stream");
        }
    }

    private void publishTranscript(WebSocketSession session, String type, String text) {
        String previousText = String.valueOf(session.getAttributes().getOrDefault(LAST_TRANSCRIPT_TEXT, ""));
        String previousType = String.valueOf(session.getAttributes().getOrDefault(LAST_TRANSCRIPT_TYPE, ""));
        if (!shouldPublishTranscript(previousType, previousText, type, text)) {
            return;
        }
        String normalized = text == null ? "" : text.trim();
        send(session, type, normalized);
        session.getAttributes().put(LAST_TRANSCRIPT_TEXT, normalized);
        session.getAttributes().put(LAST_TRANSCRIPT_TYPE, type);
    }

    private void clearTranscriptCache(WebSocketSession session) {
        session.getAttributes().remove(LAST_TRANSCRIPT_TEXT);
        session.getAttributes().remove(LAST_TRANSCRIPT_TYPE);
    }

    private void send(WebSocketSession session, String type, String value) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            if (value != null) {
                payload.put("text", value);
            }
            synchronized (session) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (Exception ignored) {
            // 连接关闭时无法再发送错误事件。
        }
    }

    static boolean shouldDelayEndpoint(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return true;
        }
        if (SHORT_QUESTION_PREFIX.matcher(normalized).matches()
            && !"请介绍一下自己".equals(normalized)) {
            return true;
        }
        if (normalized.length() >= MIN_ENDPOINT_TEXT_LENGTH) {
            return false;
        }
        return !normalized.matches(".*[?？。！!；;]$");
    }

    static boolean shouldPublishTranscript(
        String previousType,
        String previousText,
        String nextType,
        String nextText
    ) {
        String previous = previousText == null ? "" : previousText.trim();
        String next = nextText == null ? "" : nextText.trim();
        if (next.isEmpty()) {
            return false;
        }
        return !next.equals(previous) || !String.valueOf(nextType).equals(String.valueOf(previousType));
    }
}
