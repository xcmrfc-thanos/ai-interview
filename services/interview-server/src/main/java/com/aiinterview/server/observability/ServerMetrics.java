package com.aiinterview.server.observability;

import com.aiinterview.server.concurrent.BoundedExecutor;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 进程内指标快照：线程池队列深度、拒绝次数、Copilot 连接数与事件计数。 */
public final class ServerMetrics {

    private static final ConcurrentHashMap<String, BoundedExecutor> executors = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private static final AtomicInteger copilotConnections = new AtomicInteger();

    private ServerMetrics() {
    }

    /** 注册有界线程池以便 /api/metrics 采集。 */
    public static void registerExecutor(String name, BoundedExecutor executor) {
        if (name != null && executor != null) {
            executors.put(name, executor);
        }
    }

    /** Copilot WebSocket 连接建立时递增。 */
    public static void copilotConnected() {
        copilotConnections.incrementAndGet();
    }

    /** Copilot WebSocket 连接关闭时递减。 */
    public static void copilotDisconnected() {
        copilotConnections.updateAndGet(value -> Math.max(0, value - 1));
    }

    /** 递增命名计数器（Copilot 管道各阶段事件）。 */
    public static void incrementCounter(String name) {
        if (name != null && !name.isEmpty()) {
            counters.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
        }
    }

    /** 生成当前指标 JSON 快照。 */
    public static JSONObject snapshot() {
        JSONObject root = new JSONObject();
        root.put("copilot_connections", copilotConnections.get());
        JSONObject counterView = new JSONObject();
        for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
            counterView.put(entry.getKey(), entry.getValue().get());
        }
        root.put("counters", counterView);
        JSONObject pools = new JSONObject();
        for (Map.Entry<String, BoundedExecutor> entry : executors.entrySet()) {
            BoundedExecutor pool = entry.getValue();
            JSONObject item = new JSONObject();
            item.put("queue_depth", pool.queueDepth());
            item.put("rejected_total", pool.rejectedCount());
            pools.put(entry.getKey(), item);
        }
        root.put("executors", pools);
        return root;
    }
}
