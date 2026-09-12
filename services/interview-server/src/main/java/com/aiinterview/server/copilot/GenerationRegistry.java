package com.aiinterview.server.copilot;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 管理 Copilot 回答 generation 序号，用于取消后屏蔽迟到 LLM 回调。
 */
public final class GenerationRegistry {

    private final AtomicLong sequence = new AtomicLong();
    private volatile long activeGeneration;

    /** 开始新的 generation 并使其成为当前活跃代。 */
    public long begin() {
        long id = sequence.incrementAndGet();
        activeGeneration = id;
        return id;
    }

    /** 取消当前 generation（递增序号使旧任务失效）。 */
    public void cancel() {
        activeGeneration = sequence.incrementAndGet();
    }

    /** 判断给定 generation 是否仍为活跃代。 */
    public boolean isActive(long generationId) {
        return generationId == activeGeneration;
    }
}
