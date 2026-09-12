package com.aiinterview.server.copilot;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个 Copilot 会话的运行时状态：话轮序号、转写版本、断连恢复与结束排水。
 */
public final class CopilotSessionRuntime {

    /** 默认断连恢复窗口（毫秒）。 */
    public static final long DEFAULT_RECONNECT_WINDOW_MS = 30_000L;
    /** 结束会话时等待在途回答落库的上限（毫秒）。 */
    public static final long DEFAULT_END_DRAIN_TIMEOUT_MS = 5_000L;

    private final long sessionId;
    private final long userId;
    private final long reconnectWindowMs;
    private volatile CopilotSessionState state = CopilotSessionState.IDLE;
    private volatile int turnNumber = 1;
    private volatile long transcriptVersion = 0L;
    private volatile long disconnectedAtMs = 0L;
    private volatile long lastAcceptedAudioSequence = -1L;
    private final AtomicInteger inflightAnswers = new AtomicInteger();

    /** 生产环境构造：使用默认恢复/排水超时。 */
    public CopilotSessionRuntime(long sessionId, long userId) {
        this(sessionId, userId, DEFAULT_RECONNECT_WINDOW_MS);
    }

    /** 测试注入：自定义断连恢复窗口。 */
    CopilotSessionRuntime(long sessionId, long userId, long reconnectWindowMs) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.reconnectWindowMs = reconnectWindowMs;
    }

    /** 尝试迁移到新状态；非法迁移返回 false。 */
    public synchronized boolean transition(CopilotSessionState next) {
        if (next == null || next == state) {
            return next == state;
        }
        if (!isAllowedTransition(state, next)) {
            return false;
        }
        state = next;
        if (next == CopilotSessionState.ENDED || next == CopilotSessionState.ERROR) {
            disconnectedAtMs = 0L;
        }
        return true;
    }

    /** WebSocket 断开：进入短恢复窗口。 */
    public synchronized void markDisconnected() {
        if (state == CopilotSessionState.ENDED || state == CopilotSessionState.ENDING
            || state == CopilotSessionState.ERROR) {
            return;
        }
        disconnectedAtMs = System.currentTimeMillis();
        state = CopilotSessionState.RECONNECTING;
    }

    /** 重连成功：恢复运行态。 */
    public synchronized void markReconnected() {
        if (state != CopilotSessionState.RECONNECTING || !canReconnect()) {
            return;
        }
        disconnectedAtMs = 0L;
        state = CopilotSessionState.RUNNING;
    }

    /** 用户主动结束：进入排水态。 */
    public synchronized boolean beginEnding() {
        if (state == CopilotSessionState.ENDED || state == CopilotSessionState.ENDING) {
            return state == CopilotSessionState.ENDING;
        }
        if (state == CopilotSessionState.ERROR) {
            return false;
        }
        state = CopilotSessionState.ENDING;
        disconnectedAtMs = 0L;
        return true;
    }

    /** 是否仍处于断连恢复窗口内。 */
    public synchronized boolean canReconnect() {
        if (state != CopilotSessionState.RECONNECTING) {
            return false;
        }
        return System.currentTimeMillis() - disconnectedAtMs <= reconnectWindowMs;
    }

    /** 是否允许接收音频 PCM。 */
    public boolean canAcceptAudio() {
        return state == CopilotSessionState.RUNNING;
    }

    /** 记录一次在途 LLM 回答。 */
    public void answerStarted() {
        inflightAnswers.incrementAndGet();
    }

    /** 记录在途 LLM 回答完成。 */
    public void answerFinished() {
        inflightAnswers.updateAndGet(value -> Math.max(0, value - 1));
    }

    /** 是否仍有在途回答。 */
    public boolean hasPendingAnswers() {
        return inflightAnswers.get() > 0;
    }

    /** 阻塞等待在途回答完成或超时。 */
    public void awaitPendingAnswers(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (hasPendingAnswers() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
    }

    public long sessionId() {
        return sessionId;
    }

    public long userId() {
        return userId;
    }

    public CopilotSessionState state() {
        return state;
    }

    public int turnNumber() {
        return turnNumber;
    }

    public void setTurnNumber(int turnNumber) {
        this.turnNumber = Math.max(1, turnNumber);
    }

    public long transcriptVersion() {
        return transcriptVersion;
    }

    public void setTranscriptVersion(long transcriptVersion) {
        this.transcriptVersion = Math.max(0L, transcriptVersion);
    }

    public void bumpTranscriptVersion() {
        transcriptVersion++;
    }

    /** 重置音频序列跟踪（新连接或重连时）。 */
    public synchronized void resetAudioSequences() {
        lastAcceptedAudioSequence = -1L;
    }

    /**
     * 尝试接受 v2 音频序列；重复或乱序旧序列返回 false（幂等丢弃）。
     */
    public synchronized boolean acceptAudioSequence(long sequence) {
        if (sequence <= lastAcceptedAudioSequence) {
            return false;
        }
        lastAcceptedAudioSequence = sequence;
        return true;
    }

    public long lastAcceptedAudioSequence() {
        return lastAcceptedAudioSequence;
    }

    private static boolean isAllowedTransition(CopilotSessionState from, CopilotSessionState to) {
        switch (from) {
            case IDLE:
                return to == CopilotSessionState.RUNNING || to == CopilotSessionState.ERROR;
            case RUNNING:
                return to == CopilotSessionState.PAUSED || to == CopilotSessionState.RECONNECTING
                    || to == CopilotSessionState.ENDING || to == CopilotSessionState.ERROR;
            case PAUSED:
                return to == CopilotSessionState.RUNNING || to == CopilotSessionState.RECONNECTING
                    || to == CopilotSessionState.ENDING || to == CopilotSessionState.ERROR;
            case RECONNECTING:
                return to == CopilotSessionState.RUNNING || to == CopilotSessionState.ENDED
                    || to == CopilotSessionState.ERROR;
            case ENDING:
                return to == CopilotSessionState.ENDED || to == CopilotSessionState.ERROR;
            case ENDED:
            case ERROR:
            default:
                return false;
        }
    }
}
