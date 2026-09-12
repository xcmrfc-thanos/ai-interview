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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/** MyBatis 初始化：SQLite 数据源 + 注解 mapper（无 Spring/feat-cloud，保持 Router 架构）。 */
public final class MyBatis {

    private static SqlSessionFactory factory;

    private MyBatis() {
    }

    public static void init(AppConfig config) {
        init(config.dbPath());
    }

    /** 指定数据库路径初始化（测试可直接指向临时库；生产由 AppConfig 调用）。 */
    public static void init(String dbPath) {
        DataSource dataSource = new SqliteDataSource(dbPath);
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

    /** 简单 DataSource：每请求新建连接（SQLite 本地文件，开销可接受）。 */
    private static final class SqliteDataSource implements DataSource {
        private final String url;

        SqliteDataSource(String dbPath) {
            this.url = "jdbc:sqlite:" + dbPath;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = DriverManager.getConnection(url);
            SqlitePragmas.applyConnectionPragmas(conn);
            return conn;
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
