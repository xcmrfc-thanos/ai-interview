package com.aiinterview.server.db;

import com.aiinterview.server.concurrent.BoundedExecutor;
import com.aiinterview.server.observability.ServerMetrics;

/**
 * Copilot 相关 SQLite 写操作单线程调度，降低并发写锁竞争。
 */
public final class DbWriteExecutor {

    private static volatile BoundedExecutor executor;

    private DbWriteExecutor() {
    }

    /** 启动时初始化写队列（单 worker，有界队列 32）。 */
    public static void init() {
        executor = new BoundedExecutor(1, 32, "db-write");
        ServerMetrics.registerExecutor("db-write", executor);
    }

    /** 异步提交写任务；未初始化时在当前线程执行。 */
    public static void run(Runnable task) {
        if (executor == null) {
            task.run();
            return;
        }
        executor.execute(task);
    }

    /** 同步执行写任务并等待完成。 */
    public static void runAndWait(Runnable task) throws Exception {
        if (executor == null) {
            task.run();
            return;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Exception> error = new java.util.concurrent.atomic.AtomicReference<>();
        if (!executor.tryExecute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        })) {
            throw new java.util.concurrent.RejectedExecutionException("DB_WRITE_BUSY");
        }
        latch.await();
        if (error.get() != null) {
            throw error.get();
        }
    }
}
