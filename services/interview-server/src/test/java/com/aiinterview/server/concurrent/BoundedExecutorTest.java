package com.aiinterview.server.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedExecutorTest {

    @Test
    void rejectsWhenQueueFull() throws InterruptedException {
        BoundedExecutor executor = new BoundedExecutor(1, 1, "test-worker");
        CountDownLatch blocker = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                blocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        executor.execute(() -> { });
        assertEquals(0, executor.rejectedCount());
        assertFalse(executor.tryExecute(() -> { }));
        assertEquals(1, executor.rejectedCount());
        blocker.countDown();
    }

    @Test
    void executesTasksWhenCapacityAvailable() {
        BoundedExecutor executor = new BoundedExecutor(2, 4, "test-worker");
        AtomicInteger counter = new AtomicInteger();
        assertTrue(executor.tryExecute(counter::incrementAndGet));
        assertTrue(executor.tryExecute(counter::incrementAndGet));
        assertEquals(0, executor.rejectedCount());
    }
}
