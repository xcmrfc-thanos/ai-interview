package com.aiinterview.server.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite PRAGMA 设置：文件级在 Db.init 执行一次，连接级在每次 open 时应用。 */
public final class SqlitePragmas {

    private SqlitePragmas() {
    }

    /**
     * 应用文件级 PRAGMA（WAL / synchronous），启动时执行一次。
     * 每条 PRAGMA 须单独 Statement 并关闭，否则 journal_mode 返回的结果集会阻塞后续语句。
     */
    public static void applyFilePragmas(Connection connection) throws SQLException {
        executePragma(connection, "PRAGMA journal_mode=WAL");
        executePragma(connection, "PRAGMA synchronous=NORMAL");
    }

    /** 为新建连接应用 busy_timeout 与外键约束。 */
    public static void applyConnectionPragmas(Connection connection) throws SQLException {
        executePragma(connection, "PRAGMA busy_timeout=5000");
        executePragma(connection, "PRAGMA foreign_keys=ON");
    }

    /** 执行单条 PRAGMA 并关闭 Statement（含可能的结果集）。 */
    private static void executePragma(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
