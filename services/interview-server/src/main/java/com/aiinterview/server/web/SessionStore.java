package com.aiinterview.server.web;

import com.aiinterview.server.AppConfig;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端会话：cookie token -> 会话数据（内存，重启失效，与 Flask 随机密钥行为一致）。 */
public final class SessionStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 会话有效期（秒），登录 cookie 的 Max-Age 与此一致。 */
    public static final long SESSION_TTL_SECONDS = 7L * 24 * 3600;
    private static final long SESSION_TTL_MS = SESSION_TTL_SECONDS * 1000;

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    static {
        // 每小时清扫过期会话，避免废弃 token 永驻内存（daemon 线程不阻塞 JVM 退出）
        java.util.concurrent.ScheduledExecutorService sweeper =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "session-sweeper");
                thread.setDaemon(true);
                return thread;
            });
        sweeper.scheduleAtFixedRate(SessionStore::sweepExpired, 1, 1, java.util.concurrent.TimeUnit.HOURS);
    }

    private SessionStore() {
    }

    private static void sweepExpired() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> now - entry.getValue().createdAt > SESSION_TTL_MS);
    }

    public static String create(long userId, String role, String fullName) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SESSIONS.put(token, new Session(userId, role, fullName, System.currentTimeMillis()));
        return token;
    }

    public static Session get(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Session session = SESSIONS.get(token);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() - session.createdAt > SESSION_TTL_MS) {
            SESSIONS.remove(token);
            return null;
        }
        return session;
    }

    public static void destroy(String token) {
        if (token != null) {
            SESSIONS.remove(token);
        }
    }

    public static final class Session {
        public final long userId;
        public final String role;
        public final String fullName;
        final long createdAt;

        Session(long userId, String role, String fullName, long createdAt) {
            this.userId = userId;
            this.role = role;
            this.fullName = fullName;
            this.createdAt = createdAt;
        }
    }
}
