package com.aiinterview.server;

import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import net.dreamlu.mica.voice.asr.OnlineAsrService;
import org.junit.jupiter.api.Test;
import tech.smartboot.feat.core.server.WebSocketRequest;
import tech.smartboot.feat.core.server.WebSocketResponse;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsrWebSocketUpgradeTest {

    private final WebSocketRequest request = mock(WebSocketRequest.class);
    private final WebSocketResponse response = mock(WebSocketResponse.class);
    private final OnlineAsrService service = mock(OnlineAsrService.class);
    private final OnlineStream stream = mock(OnlineStream.class);
    private final OnlineRecognizer recognizer = mock(OnlineRecognizer.class);
    private final AsrWebSocketUpgrade upgrade = new AsrWebSocketUpgrade(service);

    private void text(String json) throws Throwable {
        when(request.getFrameOpcode()).thenReturn(1); // WebSocket.OPCODE_TEXT
        when(request.getPayload()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        upgrade.handle(request, response);
    }

    @Test
    void sendsReadyOnHandshake() {
        upgrade.onHandShake(request, response);
        verify(response).sendTextMessage(contains("\"type\":\"ready\""));
    }

    @Test
    void startCreatesStreamAndAcks() throws Throwable {
        when(service.createStream()).thenReturn(stream);
        text("{\"type\":\"start\"}");
        verify(response).sendTextMessage(contains("\"type\":\"started\""));
    }

    @Test
    void unknownControlFrameEmitsError() throws Throwable {
        text("{\"type\":\"whatever\"}");
        verify(response).sendTextMessage(contains("\"type\":\"error\""));
    }

    @Test
    void stopInputFinishedEmitsFinalAndReleasesStream() throws Throwable {
        when(service.createStream()).thenReturn(stream);
        when(service.getRecognizer()).thenReturn(recognizer);
        when(recognizer.isReady(stream)).thenReturn(false);
        when(recognizer.getResult(stream)).thenReturn(new OnlineRecognizerResult(
            "请简单介绍一下自己", new String[0], new float[0], new float[0]));
        text("{\"type\":\"start\"}");
        text("{\"type\":\"stop\"}");
        verify(stream).inputFinished();
        verify(response).sendTextMessage(contains("\"type\":\"final\""));
        verify(stream).release();
    }

    @Test
    void binaryFrameDecodesAndPublishesFinalWhenEndpointDetected() throws Throwable {
        when(service.createStream()).thenReturn(stream);
        when(service.getRecognizer()).thenReturn(recognizer);
        when(recognizer.isReady(stream)).thenReturn(false);
        when(recognizer.getResult(stream)).thenReturn(new OnlineRecognizerResult(
            "请简单介绍一下自己", new String[0], new float[0], new float[0]));
        when(recognizer.isEndpoint(stream)).thenReturn(true);
        when(request.getFrameOpcode()).thenReturn(2); // WebSocket.OPCODE_BINARY
        when(request.getPayload()).thenReturn(new byte[] {0x00, 0x00});
        upgrade.handle(request, response);
        verify(stream).acceptWaveform(argThat(samples -> samples.length == 1 && samples[0] == 0.0f), eq(16000));
        verify(response).sendTextMessage(contains("\"type\":\"final\""));
        verify(recognizer).reset(stream);
    }

    @Test
    void closeControlFrameClosesConnection() throws Throwable {
        text("{\"type\":\"close\"}");
        verify(response).close();
        verify(service, never()).createStream();
    }

    @Test
    void emptyBinaryFrameIsIgnored() throws Throwable {
        when(service.createStream()).thenReturn(stream);
        when(request.getFrameOpcode()).thenReturn(2);
        when(request.getPayload()).thenReturn(new byte[0]);
        upgrade.handle(request, response);
        verify(service, never()).createStream();
        verify(response, never()).sendTextMessage(contains("\"type\":\"error\""));
    }

    @Test
    void binaryFrameBeforeStartAutoCreatesStream() throws Throwable {
        when(service.createStream()).thenReturn(stream);
        when(service.getRecognizer()).thenReturn(recognizer);
        when(recognizer.isReady(stream)).thenReturn(false);
        when(recognizer.getResult(stream)).thenReturn(new OnlineRecognizerResult(
            "请简单介绍一下自己", new String[0], new float[0], new float[0]));
        when(request.getFrameOpcode()).thenReturn(2);
        when(request.getPayload()).thenReturn(new byte[] {0x00, 0x00});
        upgrade.handle(request, response);
        verify(service).createStream();
        verify(stream).acceptWaveform(argThat(samples -> samples.length == 1), eq(16000));
    }

    @Test
    void unknownControlFrameErrorMessageContainsType() throws Throwable {
        text("{\"type\":\"bogus\"}");
        verify(response).sendTextMessage(contains("bogus"));
    }
}
