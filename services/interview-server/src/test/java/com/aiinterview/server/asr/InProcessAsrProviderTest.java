package com.aiinterview.server.asr;

import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import net.dreamlu.mica.voice.asr.OnlineAsrService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InProcessAsrProviderTest {

    @Test
    void acceptPcmDecodesOnWorkerThread() throws Exception {
        OnlineAsrService service = mock(OnlineAsrService.class);
        OnlineStream stream = mock(OnlineStream.class);
        OnlineRecognizer recognizer = mock(OnlineRecognizer.class);
        when(service.createStream()).thenReturn(stream);
        when(service.getRecognizer()).thenReturn(recognizer);
        when(recognizer.isReady(stream)).thenReturn(false);
        when(recognizer.getResult(stream)).thenReturn(new OnlineRecognizerResult(
            "你好", new String[0], new float[0], new float[0]));
        when(recognizer.isEndpoint(stream)).thenReturn(false);

        List<AsrResult> results = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        InProcessAsrProvider provider = new InProcessAsrProvider(service);
        AsrSession session = provider.openSession(result -> {
            results.add(result);
            latch.countDown();
        });

        session.acceptPcm(new byte[] {0x00, 0x00});
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, results.size());
        assertEquals("partial", results.get(0).type());
        assertEquals("你好", results.get(0).text());
        verify(stream).acceptWaveform(any(float[].class), eq(16000));
        session.close();
    }
}
