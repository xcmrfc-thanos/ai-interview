package com.aiinterview.server.asr;

import com.aiinterview.server.Pcm16Decoder;
import com.aiinterview.server.TranscriptRules;
import com.aiinterview.server.concurrent.BoundedExecutor;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import net.dreamlu.mica.voice.asr.OnlineAsrService;

import java.util.function.Consumer;

/**
 * 进程内 ASR：每会话单 worker + 有界帧队列，decode 不阻塞 WebSocket I/O 线程。
 */
public final class InProcessAsrProvider implements AsrProvider {

    private static final int FRAME_QUEUE_SIZE = 16;

    private final OnlineAsrService asrService;

    public InProcessAsrProvider(OnlineAsrService asrService) {
        this.asrService = asrService;
    }

    @Override
    public AsrSession openSession(Consumer<AsrResult> listener) {
        return new InProcessAsrSession(asrService, listener);
    }

    private static final class InProcessAsrSession implements AsrSession {

        private final OnlineAsrService asrService;
        private final Consumer<AsrResult> listener;
        private final BoundedExecutor worker;
        private OnlineStream stream;
        private String lastTranscriptText = "";
        private String lastTranscriptType = "";
        private volatile boolean closed;

        InProcessAsrSession(OnlineAsrService asrService, Consumer<AsrResult> listener) {
            this.asrService = asrService;
            this.listener = listener;
            this.worker = new BoundedExecutor(1, FRAME_QUEUE_SIZE, "asr-session");
            this.stream = asrService.createStream();
        }

        @Override
        public void acceptPcm(byte[] pcm) {
            if (closed || pcm == null || pcm.length == 0) {
                return;
            }
            byte[] frame = pcm.clone();
            if (!worker.tryExecute(() -> decodeFrame(frame))) {
                listener.accept(new AsrResult("error", "音频处理繁忙，请稍后重试"));
            }
        }

        /** 在会话 worker 线程执行 native decode。 */
        private void decodeFrame(byte[] data) {
            if (closed || stream == null) {
                return;
            }
            try {
                stream.acceptWaveform(Pcm16Decoder.decode(data), 16000);
                while (asrService.getRecognizer().isReady(stream)) {
                    asrService.getRecognizer().decode(stream);
                }
                OnlineRecognizerResult result = asrService.getRecognizer().getResult(stream);
                boolean endpoint = asrService.getRecognizer().isEndpoint(stream);
                String type = endpoint ? "final" : "partial";
                publish(type, result.getText());
                if (endpoint) {
                    asrService.getRecognizer().reset(stream);
                    lastTranscriptText = "";
                    lastTranscriptType = "";
                }
            } catch (RuntimeException error) {
                listener.accept(new AsrResult("error", "转写异常，请重新开始"));
            }
        }

        private void publish(String type, String text) {
            if (!TranscriptRules.shouldPublishTranscript(lastTranscriptType, lastTranscriptText, type, text)) {
                return;
            }
            String normalized = text == null ? "" : text.trim();
            listener.accept(new AsrResult(type, normalized));
            lastTranscriptText = normalized;
            lastTranscriptType = type;
        }

        @Override
        public void close() {
            closed = true;
            if (stream != null) {
                stream.release();
                stream = null;
            }
        }
    }
}
