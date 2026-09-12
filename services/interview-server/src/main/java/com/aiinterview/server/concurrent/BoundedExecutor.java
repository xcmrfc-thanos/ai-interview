package com.aiinterview.server.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界线程池：队列满时拒绝任务，禁止 CallerRuns 回退到 I/O 线程。
 */
public final class BoundedExecutor {

    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCount = new AtomicLong();

    /**
     * @param threads 固定工作线程数
     * @param queueSize 有界队列容量
     * @param threadNamePrefix 线程名前缀
     */
    public BoundedExecutor(int threads, int queueSize, String threadNamePrefix) {
        int poolSize = Math.max(1, threads);
        int capacity = Math.max(1, queueSize);
        this.executor = new ThreadPoolExecutor(
            poolSize,
            poolSize,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity),
            runnable -> {
                Thread thread = new Thread(runnable, threadNamePrefix);
                thread.setDaemon(true);
                return thread;
            },
            (runnable, pool) -> {
                rejectedCount.incrementAndGet();
                throw new RejectedExecutionException("SERVER_BUSY");
            }
        );
    }

    /** 提交任务；队列满时抛出 RejectedExecutionException。 */
    public void execute(Runnable task) {
        executor.execute(task);
    }

    /** 尝试提交任务，满队列时返回 false 且不抛异常。 */
    public boolean tryExecute(Runnable task) {
        try {
            execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    /** 累计被拒绝的任务次数。 */
    public long rejectedCount() {
        return rejectedCount.get();
    }

    /** 当前队列中等待执行的任务数。 */
    public int queueDepth() {
        return executor.getQueue().size();
    }
}
