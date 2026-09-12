package com.aiinterview.server.copilot;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 Copilot 会话运行时注册表：支持断连短窗口内恢复话轮序号与转写版本。
 */
public final class CopilotSessionRegistry {

    private static final ConcurrentHashMap<Long, CopilotSessionRuntime> runtimes = new ConcurrentHashMap<>();

    private CopilotSessionRegistry() {
    }

    /** 查找仍在恢复窗口内的运行时。 */
    public static CopilotSessionRuntime findReconnectable(long sessionId, long userId) {
        CopilotSessionRuntime runtime = runtimes.get(sessionId);
        if (runtime == null || runtime.userId() != userId || !runtime.canReconnect()) {
            purgeExpired(sessionId);
            return null;
        }
        return runtime;
    }

    /** 注册新的运行时（替换已过期或非恢复态条目）。 */
    public static CopilotSessionRuntime register(CopilotSessionRuntime runtime) {
        runtimes.put(runtime.sessionId(), runtime);
        return runtime;
    }

    /** 断连后保留运行时以供重连。 */
    public static void keepForReconnect(CopilotSessionRuntime runtime) {
        if (runtime == null) {
            return;
        }
        runtimes.put(runtime.sessionId(), runtime);
    }

    /** 会话结束后移除。 */
    public static void remove(long sessionId) {
        if (sessionId > 0L) {
            runtimes.remove(sessionId);
        }
    }

    /** 清理超出恢复窗口的条目。 */
    private static void purgeExpired(long sessionId) {
        CopilotSessionRuntime runtime = runtimes.get(sessionId);
        if (runtime != null && runtime.state() == CopilotSessionState.RECONNECTING && !runtime.canReconnect()) {
            runtimes.remove(sessionId);
        }
    }
}
