package com.aiinterview.server.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmConfigDaoTest {

    @TempDir
    Path tempDir;
    private String db;

    @BeforeEach
    void setUp() throws Exception {
        db = tempDir.resolve("test.db").toString();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE llm_config (id INTEGER PRIMARY KEY, provider TEXT NOT NULL DEFAULT '', "
                + "base_url TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', "
                + "copilot_model TEXT NOT NULL DEFAULT '', think_model TEXT NOT NULL DEFAULT '', "
                + "api_key_enc TEXT, updated_at DATETIME NOT NULL)");
        }
        MyBatis.init(db);
    }

    @Test
    void findReturnsNullWhenNoRow() {
        assertNull(LlmConfigDao.find());
    }

    @Test
    void saveThenFindRoundtrips() {
        LlmConfigDao.save("siliconflow", "https://api.example.com/v1", "m1", "m2", "m3", "enc-token");
        Map<String, Object> row = LlmConfigDao.find();
        assertEquals("siliconflow", row.get("provider"));
        assertEquals("m2", row.get("copilot_model"));
        assertEquals("enc-token", row.get("api_key_enc"));
    }

    @Test
    void saveTwiceUpdatesSingleRow() throws Exception {
        LlmConfigDao.save("a", "u1", "m1", "", "", "t1");
        LlmConfigDao.save("b", "u2", "m2", "", "", "t2");
        Map<String, Object> row = LlmConfigDao.find();
        assertEquals("b", row.get("provider"));
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM llm_config")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void saveAllowsNullApiKeyEnc() {
        LlmConfigDao.save("p", "u", "m", "", "", null);
        Map<String, Object> row = LlmConfigDao.find();
        assertNull(row.get("api_key_enc"));
    }
}
