package com.aiinterview.server.db;

import com.aiinterview.server.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * SQLite 初始化 + 原生连接（仅剩 init/open；查询层已全部收编到 MyBatis mapper，见优化计划 T2.5）。
 */
public final class Db {

    private static String url;

    private Db() {
    }

    public static void init(AppConfig config) {
        url = "jdbc:sqlite:" + config.dbPath();
        // 确保数据库文件存在（若缺失则按 Python 侧 schema 重建空库）
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = open()) {
                SqlitePragmas.applyFilePragmas(conn);
                SqlitePragmas.applyConnectionPragmas(conn);
            }
        } catch (Exception e) {
            throw new IllegalStateException("SQLite 初始化失败: " + config.dbPath(), e);
        }
    }

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(url);
    }

    /** 轻量 ping：验证数据库文件可读。 */
    public static boolean ping() {
        try (Connection connection = open();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery("SELECT 1")) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (Exception ignored) {
            return false;
        }
    }
}
