package com.aiinterview.gateway;

import net.dreamlu.mica.voice.asr.OnlineAsrService;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.BinaryMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnlineAsrWebSocketHandlerTest {

    @Test
    void delaysVeryShortEndpointTextWithoutPunctuation() {
        assertTrue(OnlineAsrWebSocketHandler.shouldDelayEndpoint("思路"));
        assertTrue(OnlineAsrWebSocketHandler.shouldDelayEndpoint("请介绍一下"));
        assertFalse(OnlineAsrWebSocketHandler.shouldDelayEndpoint("请介绍一下自己"));
        assertFalse(OnlineAsrWebSocketHandler.shouldDelayEndpoint("思路里的传播机制"));
        assertFalse(OnlineAsrWebSocketHandler.shouldDelayEndpoint("好。"));
    }

    @Test
    void publishesOnlyChangedTranscriptTextButAllowsPartialToFinalTransition() {
        assertFalse(OnlineAsrWebSocketHandler.shouldPublishTranscript(
            "partial", "请介绍一下", "partial", "请介绍一下"
        ));
        assertTrue(OnlineAsrWebSocketHandler.shouldPublishTranscript(
            "partial", "请介绍一下", "final", "请介绍一下"
        ));
        assertTrue(OnlineAsrWebSocketHandler.shouldPublishTranscript(
            "partial", "请介绍一下", "partial", "请介绍一下自己"
        ));
    }

    @Test
    void establishesSessionWithConcurrentAttributesAndSendsReady() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        when(session.getAttributes()).thenReturn(attributes);
        OnlineAsrWebSocketHandler handler = new OnlineAsrWebSocketHandler(
            mock(OnlineAsrService.class)
        );

        assertDoesNotThrow(() -> handler.afterConnectionEstablished(session));
        verify(session).sendMessage(argThat(message ->
            message instanceof TextMessage
                && ((TextMessage) message).getPayload().contains("\"type\":\"ready\"")
        ));
    }

    @Test
    void closingSessionReleasesAndRemovesStreamAttribute() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        OnlineStream stream = mock(OnlineStream.class);
        attributes.put("stream", stream);
        when(session.getAttributes()).thenReturn(attributes);
        OnlineAsrWebSocketHandler handler = new OnlineAsrWebSocketHandler(
            mock(OnlineAsrService.class)
        );

        assertDoesNotThrow(() -> handler.afterConnectionClosed(session, CloseStatus.NORMAL));

        verify(stream).release();
        assertFalse(attributes.containsKey("stream"));
    }

    @Test
    void emitsFinalTranscriptWhenRecognizerDetectsEndpointDuringStreaming() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        OnlineStream stream = mock(OnlineStream.class);
        OnlineRecognizer recognizer = mock(OnlineRecognizer.class);
        OnlineAsrService service = mock(OnlineAsrService.class);
        when(session.getAttributes()).thenReturn(attributes);
        when(service.getRecognizer()).thenReturn(recognizer);
        when(service.createStream()).thenReturn(stream);
        when(recognizer.isReady(stream)).thenReturn(false);
        when(recognizer.getResult(stream)).thenReturn(
            new OnlineRecognizerResult("请简单介绍一下自己", new String[0], new float[0], new float[0])
        );
        when(recognizer.isEndpoint(stream)).thenReturn(true);
        OnlineAsrWebSocketHandler handler = new OnlineAsrWebSocketHandler(service);
        attributes.put("stream", stream);

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {0, 0}));

        verify(session).sendMessage(argThat(message ->
            message instanceof TextMessage
                && ((TextMessage) message).getPayload().contains("\"type\":\"final\"")
                && ((TextMessage) message).getPayload().contains("请简单介绍一下自己")
        ));
        verify(recognizer).reset(stream);
    }
}
