package com.aiinterview.server.asr;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.TranscriptRules;
import com.aiinterview.server.concurrent.BoundedExecutor;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 远程 mica-voice-gateway ASR：通过 WebSocket 转发 PCM 并接收 partial/final。
 */
public final class GatewayAsrProvider implements AsrProvider {

    private static final int PCM_QUEUE_SIZE = 16;

    private final AppConfig config;

    public GatewayAsrProvider(AppConfig config) {
        this.config = config;
    }

    @Override
    public AsrSession openSession(Consumer<AsrResult> listener) {
        return new GatewayAsrSession(config, listener);
    }

    /** 探测网关是否可握手（用于 /api/ready）。 */
    public static boolean probeReachable(AppConfig config) {
        URI uri = URI.create(config.asrGatewayUrl());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                ok.set(true);
                latch.countDown();
                close();
            }

            @Override
            public void onMessage(String message) {
                if (GatewayAsrProtocol.isControlAck(message)) {
                    ok.set(true);
                    latch.countDown();
                    close();
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                latch.countDown();
            }

            @Override
            public void onError(Exception ex) {
                latch.countDown();
            }
        };
        try {
            client.connect();
            latch.await(config.asrGatewayConnectTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            client.close();
        }
        return ok.get();
    }

    private static final class GatewayAsrSession implements AsrSession {

        private final AppConfig config;
        private final Consumer<AsrResult> listener;
        private final BoundedExecutor sender;
        private final URI uri;

        private volatile WebSocketClient client;
        private volatile boolean closed;
        private volatile boolean started;
        private String lastTranscriptText = "";
        private String lastTranscriptType = "";

        GatewayAsrSession(AppConfig config, Consumer<AsrResult> listener) {
            this.config = config;
            this.listener = listener;
            this.uri = URI.create(config.asrGatewayUrl());
            this.sender = new BoundedExecutor(1, PCM_QUEUE_SIZE, "gateway-asr-send");
            connect();
        }

        /** 建立到网关的 WebSocket 并发送 config/start 控制帧。 */
        private void connect() {
            if (closed) {
                return;
            }
            WebSocketClient next = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send("{\"type\":\"config\"}");
                }

                @Override
                public void onMessage(String message) {
                    handleGatewayMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    started = false;
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    listener.accept(new AsrResult("error", "网关 ASR 连接异常"));
                }
            };
            client = next;
            next.connect();
        }

        /** 处理网关控制帧与转写结果。 */
        private void handleGatewayMessage(String message) {
            JSONObjectSafe type = JSONObjectSafe.readType(message);
            if ("config-ack".equals(type.value) || "ready".equals(type.value)) {
                if (client != null && client.isOpen()) {
                    client.send("{\"type\":\"start\"}");
                }
                return;
            }
            if ("started".equals(type.value)) {
                started = true;
                return;
            }
            AsrResult result = GatewayAsrProtocol.parseTextMessage(message);
            if (result == null) {
                return;
            }
            if ("error".equals(result.type())) {
                listener.accept(result);
                return;
            }
            publish(result.type(), result.text());
        }

        private void publish(String type, String text) {
            if (!TranscriptRules.shouldPublishTranscript(lastTranscriptType, lastTranscriptText, type, text)) {
                return;
            }
            String normalized = text == null ? "" : text.trim();
            listener.accept(new AsrResult(type, normalized));
            lastTranscriptText = normalized;
            lastTranscriptType = type;
            if ("final".equals(type)) {
                lastTranscriptText = "";
                lastTranscriptType = "";
            }
        }

        /** 断线后在配置延迟后尝试重连。 */
        private void scheduleReconnect() {
            if (closed) {
                return;
            }
            Thread reconnect = new Thread(() -> {
                try {
                    Thread.sleep(config.asrGatewayReconnectDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!closed) {
                    connect();
                }
            }, "gateway-asr-reconnect");
            reconnect.setDaemon(true);
            reconnect.start();
        }

        @Override
        public void acceptPcm(byte[] pcm) {
            if (closed || pcm == null || pcm.length == 0) {
                return;
            }
            byte[] frame = pcm.clone();
            if (!sender.tryExecute(() -> sendPcm(frame))) {
                listener.accept(new AsrResult("error", "网关音频发送繁忙，请稍后重试"));
            }
        }

        /** 在发送线程将 PCM 二进制帧写入网关 WS。 */
        private void sendPcm(byte[] frame) {
            WebSocketClient current = client;
            if (current == null || !current.isOpen() || !started) {
                return;
            }
            current.send(frame);
        }

        @Override
        public void close() {
            closed = true;
            started = false;
            WebSocketClient current = client;
            if (current != null && current.isOpen()) {
                try {
                    current.send("{\"type\":\"stop\"}");
                } catch (RuntimeException ignored) {
                    // 连接已关闭时忽略
                }
                current.close();
            }
            client = null;
        }
    }

    /** 轻量读取 JSON type 字段，避免重复解析逻辑散落。 */
    private static final class JSONObjectSafe {
        private final String value;

        private JSONObjectSafe(String value) {
            this.value = value;
        }

        private static JSONObjectSafe readType(String message) {
            try {
                com.alibaba.fastjson2.JSONObject payload = com.alibaba.fastjson2.JSONObject.parseObject(message);
                return new JSONObjectSafe(payload == null ? null : payload.getString("type"));
            } catch (RuntimeException ignored) {
                return new JSONObjectSafe(null);
            }
        }
    }
}
