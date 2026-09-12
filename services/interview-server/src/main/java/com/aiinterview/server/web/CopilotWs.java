package com.aiinterview.server.web;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.asr.AsrProvider;
import com.aiinterview.server.asr.AsrResult;
import com.aiinterview.server.asr.AsrSession;
import com.aiinterview.server.copilot.CopilotAudioFrame;
import com.aiinterview.server.copilot.CopilotSessionContext;
import com.aiinterview.server.copilot.CopilotSessionRegistry;
import com.aiinterview.server.copilot.CopilotSessionRuntime;
import com.aiinterview.server.copilot.CopilotSessionState;
import com.aiinterview.server.copilot.GenerationRegistry;
import com.aiinterview.server.copilot.MicPcmBuffer;
import com.aiinterview.server.copilot.UtteranceAssembler;
import com.aiinterview.server.copilot.UtteranceDecision;
import com.aiinterview.server.copilot.VoiceProfileVerifier;
import com.aiinterview.server.concurrent.BoundedExecutor;
import com.aiinterview.server.observability.ServerMetrics;
import com.aiinterview.server.db.CopilotDao;
import com.aiinterview.server.svc.CopilotAnswerService;
import com.aiinterview.server.svc.Llm;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.common.codec.websocket.CloseReason;
import tech.smartboot.feat.core.server.WebSocketRequest;
import tech.smartboot.feat.core.server.WebSocketResponse;
import tech.smartboot.feat.core.server.upgrade.websocket.WebSocketUpgrade;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copilot 实时 WebSocket（/ws/copilot）：原生 WS 替代 Socket.IO。
 * 文本帧 = JSON 控制事件；二进制帧 = PCM16 S16LE/16kHz/mono 音频。
 */
public class CopilotWs extends WebSocketUpgrade {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CopilotWs.class);

    /** 回答生成线程池：有界队列，满时拒绝任务，禁止回落到 WS I/O 线程。 */
    private static volatile BoundedExecutor ANSWER_EXECUTOR;
    /** 会话结束排水：等待在途回答落库。 */
    private static volatile BoundedExecutor END_DRAIN_EXECUTOR;

    /** 启动时按 AppConfig 初始化（线程数默认 4，见 ANSWER_EXECUTOR_THREADS）。 */
    public static void init(com.aiinterview.server.AppConfig config) {
        int threads = Math.max(1, config.answerExecutorThreads());
        ANSWER_EXECUTOR = new BoundedExecutor(threads, 8, "copilot-answer");
        END_DRAIN_EXECUTOR = new BoundedExecutor(1, 4, "copilot-end-drain");
        ServerMetrics.registerExecutor("copilot-answer", ANSWER_EXECUTOR);
        ServerMetrics.registerExecutor("copilot-end-drain", END_DRAIN_EXECUTOR);
    }

    private final AsrProvider asrProvider;
    private final AppConfig config;
    private final Llm llm;
    private final long userId;

    private static final int MIC_VERIFY_BUFFER_BYTES = 16000 * 2 * 3;

    private final Map<String, AsrSession> asrSessions = new ConcurrentHashMap<>();
    private final Map<String, String> lastTranscriptTextBySource = new ConcurrentHashMap<>();
    private final Map<String, String> lastTranscriptTypeBySource = new ConcurrentHashMap<>();
    private WebSocketResponse connection;
    private WsEventSink eventSink;
    private Long sessionId;
    private CopilotSessionContext sessionContext;
    private CopilotSessionRuntime runtime;
    private long version = 0;
    private int turnNumber = 1;
    /** 采集来源/说话人（连接级状态，由控制帧驱动；volatile 因 handleTextMessage 与 ANSWER_EXECUTOR 并发读写）。 */
    private volatile String captureSource = "microphone";
    private volatile String captureMode = "auto";
    private volatile boolean voiceProfileAvailable;
    private volatile String speaker = "auto";
    private VoiceProfileVerifier voiceProfileVerifier;
    private final MicPcmBuffer micPcmBuffer = new MicPcmBuffer(MIC_VERIFY_BUFFER_BYTES);
    private final UtteranceAssembler utteranceAssembler = new UtteranceAssembler();
    private final GenerationRegistry generationRegistry = new GenerationRegistry();

    public CopilotWs(AsrProvider asrProvider, AppConfig config, Llm llm, long userId) {
        // WS 空闲销毁必须晚于 LLM 最长流式时长，否则慢回答会被 idle monitor 掐断
        super(Math.max(120_000L, config.llmTimeoutSeconds() * 1000L + 60_000L));
        this.asrProvider = asrProvider;
        this.config = config;
        this.llm = llm;
        this.userId = userId;
    }

    @Override
    public void onHandShake(WebSocketRequest request, WebSocketResponse response) {
        bindConnection(response);
        ServerMetrics.copilotConnected();
        send(response, "ready", null);
    }

    @Override
    public void handleTextMessage(WebSocketRequest request, WebSocketResponse response, String message) {
        bindConnection(response);
        JSONObject payload;
        try {
            payload = JSONObject.parseObject(message);
        } catch (RuntimeException e) {
            send(response, "error", "无效的控制帧");
            return;
        }
        if (payload == null) {
            send(response, "error", "无效的控制帧");
            return;
        }
        String event = String.valueOf(payload.get("event"));
        try {
            switch (event) {
                case "copilot_start":
                    handleStart(payload, response);
                    break;
                case "copilot_pause":
                    handlePause(response);
                    break;
                case "copilot_resume":
                    handleResume(response);
                    break;
                case "copilot_end":
                    handleEnd(response);
                    break;
                case "copilot_set_speaker":
                    handleSetSpeaker(payload, response);
                    break;
                default:
                    send(response, "error", "未知事件: " + event);
            }
        } catch (RuntimeException error) {
            log.error("控制帧处理失败", error);
            send(response, "error", "控制帧处理失败，请重试");
        }
    }

    @Override
    public void handleBinaryMessage(WebSocketRequest request, WebSocketResponse response, byte[] data) {
        bindConnection(response);
        if (data.length == 0 || sessionId == null || asrSessions.isEmpty()) {
            return;
        }
        if (runtime != null && !runtime.canAcceptAudio()) {
            return;
        }
        CopilotAudioFrame frame = CopilotAudioFrame.parse(data);
        if (frame.pcm().length == 0) {
            return;
        }
        if (frame.isV2()) {
            boolean accepted = runtime == null || runtime.acceptAudioSequence(frame.sequence());
            sendAudioAck(frame.sequence(), accepted, accepted ? null : "duplicate");
            if (!accepted) {
                ServerMetrics.incrementCounter("copilot.audio_duplicate");
                return;
            }
            ServerMetrics.incrementCounter("copilot.audio_accepted");
        }
        String audioSource = frame.isV2()
            ? CopilotWsPolicy.sourceFromFrameCode(frame.sourceCode())
            : captureSource;
        AsrSession target = asrSessions.get(audioSource);
        if (target == null && "display".equals(audioSource)) {
            target = asrSessions.get("mixed");
        }
        if (target == null) {
            target = asrSessions.values().stream().findFirst().orElse(null);
        }
        if (target != null) {
            if ("auto".equals(captureMode) && "microphone".equals(audioSource)) {
                micPcmBuffer.append(frame.pcm());
            }
            target.acceptPcm(frame.pcm());
        }
    }

    @Override
    public void onClose(WebSocketRequest request, WebSocketResponse response, CloseReason closeReason) {
        ServerMetrics.copilotDisconnected();
        if (runtime != null && runtime.state() != CopilotSessionState.ENDING
            && runtime.state() != CopilotSessionState.ENDED) {
            runtime.markDisconnected();
            CopilotSessionRegistry.keepForReconnect(runtime);
        }
        if (runtime == null || runtime.state() != CopilotSessionState.ENDING) {
            generationRegistry.cancel();
            release();
        }
    }

    /** 绑定当前连接的出站 sink（同一连接复用同一 response 实例）。 */
    private void bindConnection(WebSocketResponse response) {
        if (connection != response) {
            connection = response;
            eventSink = new WsEventSink(response);
        }
    }

    private void handleStart(JSONObject payload, WebSocketResponse response) {
        Long sid = payload.getLong("session_id");
        if (sid == null) {
            send(response, "error", "缺少 session_id");
            return;
        }
        Map<String, Object> session = CopilotDao.findSession(sid, userId);
        if (session == null) {
            send(response, "error", "无权访问该会话");
            return;
        }
        if ("ended".equals(String.valueOf(session.get("status")))) {
            send(response, "error", "会话已结束");
            return;
        }
        sessionId = sid;
        sessionContext = CopilotSessionContext.load(sid, userId);
        CopilotSessionRuntime existing = CopilotSessionRegistry.findReconnectable(sid, userId);
        boolean reconnected = existing != null;
        if (reconnected) {
            runtime = existing;
            runtime.markReconnected();
            turnNumber = runtime.turnNumber();
            version = runtime.transcriptVersion();
        } else {
            runtime = CopilotSessionRegistry.register(new CopilotSessionRuntime(sid, userId));
            turnNumber = 1;
            version = 0L;
        }
        runtime.transition(CopilotSessionState.RUNNING);
        runtime.resetAudioSequences();
        release();
        utteranceAssembler.reset();
        if (!reconnected) {
            generationRegistry.cancel();
        }
        captureMode = String.valueOf(payload.getOrDefault("mode", "auto"));
        closeVoiceProfileVerifier();
        micPcmBuffer.reset();
        voiceProfileVerifier = VoiceProfileVerifier.tryCreate(config, userId);
        voiceProfileAvailable = voiceProfileVerifier != null && voiceProfileVerifier.isEnrolled();
        JSONArray sources = payload.getJSONArray("sources");
        captureSource = CopilotWsPolicy.normalizeSource(sources);
        openAsrSessions(CopilotWsPolicy.resolveSessionSources(sources, captureMode));
        JSONObject captureEvent = new JSONObject();
        captureEvent.put("source", captureSource);
        captureEvent.put("mode", captureMode);
        captureEvent.put("voice_profile", voiceProfileAvailable);
        captureEvent.put("reconnected", reconnected);
        CopilotDao.updateSessionStatus(sid, "running");
        CopilotDao.createEvent(sid, "capture_started", 0, null, null, captureEvent.toJSONString());
        sendSessionReady(response, reconnected);
    }

    /** 发送 started/reconnected 并告知客户端使用 v2 音频协议。 */
    private void sendSessionReady(WebSocketResponse response, boolean reconnected) {
        JSONObject payload = new JSONObject();
        payload.put("event", reconnected ? "reconnected" : "started");
        payload.put("session_id", sessionId);
        payload.put("audio_protocol", CopilotAudioFrame.PROTOCOL_VERSION);
        sendJson(payload.toJSONString());
    }

    /** 发送 v2 音频帧 ACK（重复序列仍 ACK 以便客户端推进游标）。 */
    private void sendAudioAck(long sequence, boolean accepted, String reason) {
        if (sessionId == null) {
            return;
        }
        JSONObject ack = new JSONObject();
        ack.put("event", "audio_ack");
        ack.put("session_id", sessionId);
        ack.put("sequence", sequence);
        ack.put("accepted", accepted);
        if (reason != null) {
            ack.put("reason", reason);
        }
        sendJson(ack.toJSONString());
    }

    private void handlePause(WebSocketResponse response) {
        if (sessionId != null) {
            CopilotDao.updateSessionStatus(sessionId, "paused");
            if (runtime != null) {
                runtime.transition(CopilotSessionState.PAUSED);
            }
        }
        send(response, "paused", null);
    }

    private void handleResume(WebSocketResponse response) {
        if (sessionId != null) {
            CopilotDao.updateSessionStatus(sessionId, "running");
            if (runtime != null) {
                runtime.transition(CopilotSessionState.RUNNING);
            }
        }
        send(response, "resumed", null);
    }

    private void handleEnd(WebSocketResponse response) {
        if (sessionId == null) {
            send(response, "ended", null);
            return;
        }
        if (runtime == null || !runtime.beginEnding()) {
            send(response, "error", "会话正在结束");
            return;
        }
        JSONObject ending = new JSONObject();
        ending.put("event", "ending");
        ending.put("session_id", sessionId);
        sendJson(ending.toJSONString());

        Long sid = sessionId;
        CopilotSessionRuntime drainTarget = runtime;
        WebSocketResponse conn = connection;
        if (END_DRAIN_EXECUTOR != null) {
            END_DRAIN_EXECUTOR.execute(() -> finalizeSession(sid, drainTarget, conn));
        } else {
            finalizeSession(sid, drainTarget, conn);
        }
    }

    /** 结束排水：等待在途回答落库后标记会话结束。 */
    private void finalizeSession(Long sid, CopilotSessionRuntime drainTarget, WebSocketResponse conn) {
        try {
            drainTarget.awaitPendingAnswers(CopilotSessionRuntime.DEFAULT_END_DRAIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        CopilotDao.updateSessionStatus(sid, "ended");
        drainTarget.transition(CopilotSessionState.ENDED);
        CopilotSessionRegistry.remove(sid);
        generationRegistry.cancel();
        release();
        if (conn != null) {
            send(conn, "ended", null);
        }
    }

    /** 音频来源/说话人切换（auto/local/remote → null/candidate/interviewer），仅记录事件并回执。 */
    private void handleSetSpeaker(com.alibaba.fastjson2.JSONObject payload, WebSocketResponse response) {
        Long sid = payload.getLong("session_id");
        String speaker = payload.getString("speaker");
        if (sid == null || sessionId == null || !sid.equals(sessionId)) {
            send(response, "error", "会话不匹配");
            return;
        }
        String normalized = "candidate".equals(speaker) ? "candidate"
            : "interviewer".equals(speaker) ? "interviewer" : "auto";
        this.speaker = normalized;
        JSONObject eventPayload = new JSONObject();
        eventPayload.put("speaker", normalized);
        CopilotDao.createEvent(sid, "speaker_changed", 0, null, null, eventPayload.toJSONString());
        JSONObject ack = new JSONObject();
        ack.put("event", "speaker_changed");
        ack.put("session_id", sid);
        ack.put("speaker", normalized);
        ack.put("source", externalSource(captureSource));
        sendJson(ack.toJSONString());
    }

    /** 为每路音频来源打开独立 ASR 会话（对齐 Python 双路采集）。 */
    private void openAsrSessions(List<String> audioSources) {
        releaseAsrSessions();
        lastTranscriptTextBySource.clear();
        lastTranscriptTypeBySource.clear();
        List<String> sources = audioSources == null || audioSources.isEmpty()
            ? CopilotWsPolicy.defaultSourcesForMode(captureMode)
            : audioSources;
        for (String source : sources) {
            asrSessions.put(source, asrProvider.openSession(result -> handleAsrResult(source, result)));
        }
        if (asrSessions.isEmpty()) {
            asrSessions.put("mixed", asrProvider.openSession(result -> handleAsrResult("mixed", result)));
        }
    }

    /** ASR worker 线程回调：转写或错误事件。 */
    private void handleAsrResult(String audioSource, AsrResult result) {
        if (connection == null || eventSink == null) {
            return;
        }
        if ("error".equals(result.type())) {
            send(connection, "error", result.text());
            return;
        }
        publishTranscript(audioSource, result.type(), result.text());
    }

    private void publishTranscript(String audioSource, String type, String text) {
        String lastType = lastTranscriptTypeBySource.getOrDefault(audioSource, "");
        String lastText = lastTranscriptTextBySource.getOrDefault(audioSource, "");
        if (!com.aiinterview.server.TranscriptRules.shouldPublishTranscript(lastType, lastText, type, text)) {
            return;
        }
        String normalized = text == null ? "" : text.trim();
        if (runtime != null) {
            runtime.bumpTranscriptVersion();
            version = runtime.transcriptVersion();
        } else {
            version++;
        }
        JSONObject payload = new JSONObject();
        payload.put("event", "transcript_" + type);
        payload.put("session_id", sessionId);
        payload.put("text", normalized);
        payload.put("version", version);
        payload.put("source", externalSource(audioSource));
        Boolean voiceProfileMatch = resolveVoiceProfileMatch(audioSource);
        String effectiveSpeaker = CopilotWsPolicy.resolveSpeakerForAudioSource(
            audioSource, captureMode, voiceProfileMatch, speaker);
        payload.put("speaker", effectiveSpeaker);
        if (voiceProfileMatch != null) {
            payload.put("voice_match", voiceProfileMatch);
        }
        sendJson(payload.toJSONString());
        lastTranscriptTextBySource.put(audioSource, normalized);
        lastTranscriptTypeBySource.put(audioSource, type);
        if ("final".equals(type) && !normalized.isEmpty()) {
            handleFinalTranscript(normalized, effectiveSpeaker);
        }
    }

    /** final 转写：始终展示；仅面试官完整话轮才触发 LLM。 */
    private void handleFinalTranscript(String normalized, String effectiveSpeaker) {
        UtteranceDecision decision = utteranceAssembler.acceptFinal(
            normalized, effectiveSpeaker, System.currentTimeMillis());
        if (!decision.isComplete()) {
            return;
        }
        JSONObject utterancePayload = new JSONObject();
        utterancePayload.put("event", "utterance_completed");
        utterancePayload.put("session_id", sessionId);
        utterancePayload.put("text", decision.text());
        utterancePayload.put("speaker", effectiveSpeaker);
        utterancePayload.put("reason", decision.reason());
        sendJson(utterancePayload.toJSONString());

        long turnId = CopilotDao.beginTurn(sessionId, turnNumber++, decision.text());
        if (runtime != null) {
            runtime.setTurnNumber(turnNumber);
        }
        long generationId = generationRegistry.begin();
        generateAnswer(decision.text(), turnId, generationId);
    }

    /** 话轮完成后的 LLM 回答生成。 */
    private void generateAnswer(String question, long turnId, long generationId) {
        WebSocketResponse response = connection;
        if (response == null) {
            return;
        }
        JSONObject queued = new JSONObject();
        queued.put("event", "answer_queued");
        queued.put("session_id", sessionId);
        queued.put("turn_id", turnId);
        queued.put("generation_id", generationId);
        sendJson(queued.toJSONString());

        boolean accepted = ANSWER_EXECUTOR.tryExecute(() -> runAnswerGeneration(question, turnId, generationId));
        if (!accepted) {
            ServerMetrics.incrementCounter("copilot.answer_rejected");
            send(response, "error", "回答服务繁忙，请稍后重试");
        }
    }

    /** 在 copilot-answer 线程池执行流式 LLM 回答。 */
    private void runAnswerGeneration(String question, long turnId, long generationId) {
        if (runtime != null) {
            runtime.answerStarted();
        }
        WebSocketResponse response = connection;
        if (response == null) {
            if (runtime != null) {
                runtime.answerFinished();
            }
            return;
        }
        try {
            String contextText = sessionContext == null
                ? "面试官问题：" + question
                : sessionContext.buildLlmContext(question);

            CopilotAnswerService.Emitter emitter = (event, payload) -> {
                if (!generationRegistry.isActive(generationId)) {
                    return;
                }
                payload.put("event", event);
                sendJson(payload.toJSONString());
            };

            JSONObject result = CopilotAnswerService.generate(
                llm, sessionId, turnId, generationId, generationRegistry, contextText, emitter);

            if (!generationRegistry.isActive(generationId)) {
                return;
            }
            String pointsText = result.getJSONArray("answer_points") == null
                ? "[]" : result.getJSONArray("answer_points").toJSONString();
            CopilotDao.completeTurnAsync(
                turnId,
                "completed",
                question,
                pointsText,
                result.getString("reference_answer"),
                result.getString("follow_up")
            );
            ServerMetrics.incrementCounter("copilot.answer_completed");
        } catch (Exception e) {
            log.error("回答生成异常 sessionId={} turnId={}", sessionId, turnId, e);
            ServerMetrics.incrementCounter("copilot.answer_failed");
            if (generationRegistry.isActive(generationId)) {
                send(response, "error", "回答生成失败，请稍后重试");
            }
        } finally {
            if (runtime != null) {
                runtime.answerFinished();
            }
        }
    }

    /** 自动模式麦克风轨：基于最近 PCM 窗口做声纹比对。 */
    private Boolean resolveVoiceProfileMatch(String audioSource) {
        if (!"auto".equals(captureMode) || !"microphone".equals(audioSource)) {
            return null;
        }
        if (voiceProfileVerifier == null) {
            return null;
        }
        return voiceProfileVerifier.verifyRecentPcm(micPcmBuffer.snapshot());
    }

    private void closeVoiceProfileVerifier() {
        if (voiceProfileVerifier != null) {
            voiceProfileVerifier.close();
            voiceProfileVerifier = null;
        }
    }

    private void releaseAsrSessions() {
        for (AsrSession session : asrSessions.values()) {
            session.close();
        }
        asrSessions.clear();
        lastTranscriptTextBySource.clear();
        lastTranscriptTypeBySource.clear();
    }

    private void release() {
        closeVoiceProfileVerifier();
        micPcmBuffer.reset();
        releaseAsrSessions();
    }

    private void send(WebSocketResponse response, String type, String value) {
        JSONObject payload = new JSONObject();
        payload.put("event", type);
        if (sessionId != null) {
            payload.put("session_id", sessionId);
        }
        if (value != null) {
            // 契约 §6 统一 message 字段；text 为过渡期兼容保留
            payload.put("text", value);
            payload.put("message", value);
        }
        if (eventSink != null) {
            eventSink.send(payload.toJSONString());
        } else {
            new WsEventSink(response).send(payload.toJSONString());
        }
    }

    /** 契约 D3：出站事件来源统一命名为 system（内部会话键与 v2 帧源码保持 display 不变）。 */
    private static String externalSource(String source) {
        return "display".equals(source) ? "system" : source;
    }

    private void sendJson(String json) {
        if (eventSink != null) {
            eventSink.send(json);
        }
    }
}
