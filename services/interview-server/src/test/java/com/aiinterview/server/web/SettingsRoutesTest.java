package com.aiinterview.server.web;

import com.aiinterview.server.db.MyBatis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingsRoutesTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        String db = tempDir.resolve("test.db").toString();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE llm_config (id INTEGER PRIMARY KEY, provider TEXT NOT NULL DEFAULT '', "
                + "base_url TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', "
                + "copilot_model TEXT NOT NULL DEFAULT '', think_model TEXT NOT NULL DEFAULT '', "
                + "api_key_enc TEXT, updated_at DATETIME NOT NULL)");
        }
        MyBatis.init(db);
    }

    @AfterEach
    void releasePool() {
        // 池连接持有 test.db 文件句柄，须先释放才能让 @TempDir 清理成功
        MyBatis.shutdown();
    }

    @Test
    void getWithoutLoginReturns401() throws Throwable {
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        when(request.getCookies()).thenReturn(null);
        SettingsRoutes.handleGet(request, response);
        verify(response).setHttpStatus(tech.smartboot.feat.core.common.HttpStatus.valueOf(401));
    }

    @Test
    void putWithoutLoginReturns401() throws Throwable {
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        when(request.getCookies()).thenReturn(null);
        SettingsRoutes.handlePut(request, response);
        verify(response).setHttpStatus(tech.smartboot.feat.core.common.HttpStatus.valueOf(401));
    }
}
