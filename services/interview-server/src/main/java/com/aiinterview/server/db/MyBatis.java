package com.aiinterview.server.db;

import com.aiinterview.server.AppConfig;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** MyBatis 初始化：SQLite 池化数据源 + 注解 mapper（无 Spring/feat-cloud，保持 Router 架构）。 */
public final class MyBatis {

    private static SqlSessionFactory factory;
    private static SqliteDataSource dataSource;

    private MyBatis() {
    }

    public static void init(AppConfig config) {
        init(config.dbPath());
    }

    /** 指定数据库路径初始化（测试可直接指向临时库；生产由 AppConfig 调用）。重建时关闭旧池连接。 */
    public static void init(String dbPath) {
        if (dataSource != null) {
            dataSource.closePool();
        }
        dataSource = new SqliteDataSource(dbPath);
        try {
            dataSource.openPool();
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 连接池初始化失败: " + dbPath, e);
        }
        TransactionFactory transactionFactory = new JdbcTransactionFactory();
        Environment environment = new Environment("sqlite", transactionFactory, dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(ResumeMapper.class);
        configuration.addMapper(PlanMapper.class);
        configuration.addMapper(MockMapper.class);
        configuration.addMapper(CopilotMapper.class);
        configuration.addMapper(ReviewMapper.class);
        configuration.addMapper(VoiceProfileMapper.class);
        configuration.addMapper(LlmConfigMapper.class);
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 关闭池并清空 factory（测试临时库清理需释放文件句柄时调用）。 */
    public static void shutdown() {
        if (dataSource != null) {
            dataSource.closePool();
            dataSource = null;
        }
        factory = null;
    }

    /** 打开会话（自动提交，SQLite 本地单写场景足够）。 */
    public static SqlSession open() {
        return factory.openSession(true);
    }

    /** 打开手动提交的会话（供事务使用）。 */
    public static SqlSession openTx() {
        return factory.openSession(false);
    }

    /**
     * 事务包装：work 内所有写操作共用同一连接，异常时整体回滚。
     * 注意：事务内只允许调用接受 SqlSession 的 DAO 重载，禁止再调 MyBatis.open()（会开新连接，SQLite 单写会锁）。
     */
    public static void tx(java.util.function.Consumer<SqlSession> work) {
        try (SqlSession session = openTx()) {
            try {
                work.accept(session);
                session.commit();
            } catch (RuntimeException e) {
                session.rollback();
                throw e;
            }
        }
    }

    /**
     * 池化 DataSource：固定容量连接池（默认 4，SQLITE_POOL_SIZE 可调 1~16），
     * getConnection 返回代理连接——close() 归还池而非真正关闭，省去每次建连 + PRAGMA 开销。
     * 池耗尽（5s 等待超时）降级为一次性直连，保证服务不因突发并发而失败。
     */
    private static final class SqliteDataSource implements DataSource {

        private static final int ACQUIRE_TIMEOUT_SECONDS = 5;

        private final String url;
        private final int poolSize;
        private final BlockingQueue<Connection> idle;

        SqliteDataSource(String dbPath) {
            this.url = "jdbc:sqlite:" + dbPath;
            int size = 4;
            try {
                size = Integer.parseInt(System.getenv().getOrDefault("SQLITE_POOL_SIZE", "4").trim());
            } catch (NumberFormatException ignored) {
                // 环境变量非法时使用默认值
            }
            this.poolSize = Math.max(1, Math.min(size, 16));
            this.idle = new ArrayBlockingQueue<>(this.poolSize);
        }

        /** 启动即建满池连接（每条连接应用一次连接级 PRAGMA）。 */
        void openPool() throws SQLException {
            for (int i = 0; i < poolSize; i++) {
                idle.add(newConnection());
            }
        }

        /** 关闭池：释放全部空闲连接（re-init 或关闭前调用，避免残留文件句柄）。 */
        void closePool() {
            Connection conn;
            while ((conn = idle.poll()) != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // 连接已失效：直接丢弃
                }
            }
        }

        private Connection newConnection() throws SQLException {
            Connection raw = DriverManager.getConnection(url);
            SqlitePragmas.applyConnectionPragmas(raw);
            return raw;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection raw = null;
            try {
                raw = idle.poll(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (raw != null) {
                try {
                    if (raw.isClosed()) {
                        raw = null; // 池内连接失效（如测试重建库路径）：走降级新建
                    }
                } catch (SQLException ignored) {
                    raw = null;
                }
            }
            if (raw == null) {
                return newConnection(); // 池耗尽降级：一次性连接，close 即真正关闭
            }
            return pooledProxy(raw);
        }

        /** 代理连接：close() 归还池；其余方法透传原始连接。 */
        private Connection pooledProxy(Connection raw) {
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        release(raw);
                        return null;
                    }
                    try {
                        return method.invoke(raw, args);
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException) {
                            throw (RuntimeException) cause;
                        }
                        if (cause instanceof SQLException) {
                            throw (SQLException) cause;
                        }
                        if (cause instanceof Error) {
                            throw (Error) cause;
                        }
                        throw new SQLException(cause);
                    }
                });
        }

        private void release(Connection raw) {
            try {
                if (raw.isClosed()) {
                    return; // 已真实关闭的连接不回池
                }
            } catch (SQLException ignored) {
                return;
            }
            if (!idle.offer(raw)) {
                try {
                    raw.close(); // 池满（异常路径重复归还）：真实关闭
                } catch (SQLException ignored) {
                }
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() { return null; }

        @Override
        public void setLogWriter(PrintWriter out) { }

        @Override
        public void setLoginTimeout(int seconds) { }

        @Override
        public int getLoginTimeout() { return 0; }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) { return null; }

        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
