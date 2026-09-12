# Java 侧模型管理（LLM 设置）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 interview-server（Java）完整实现模型管理——读写 `llm_config` 表（Fernet 加密互操作）、提供 `GET/PUT /api/settings/llm`、LLM 调用保存后立即生效，与 Python 主仓行为对齐。

**Architecture:** 新增 `FernetBox`（BouncyCastle 实现 Fernet，与 Python `cryptography.Fernet` 互操作）、`LlmConfigMapper/Dao`（读写 `llm_config` 表）、`LlmConfigProvider`（env 默认 + DB 覆盖 + TTL 缓存 + `invalidate()`）、`LlmSettingsService`（GET/PUT 核心逻辑）、`SettingsRoutes`（薄 HTTP 层）；`Llm` 改为从 provider 动态解析配置，配置变化时重建 FeatAI `ChatModel`。

**Tech Stack:** Java 8、Maven、FeatAI（tech.smartboot.feat）、MyBatis 注解 mapper、SQLite（sqlite-jdbc）、BouncyCastle（bcprov-jdk18on 1.78.1，已在 pom）、JUnit 5.10.2 + Mockito 4.11.0、fastjson2。

**参考规格：** `docs/superpowers/specs/2026-09-07-java-llm-settings-design.md`

**约定：** 所有命令在 `services/interview-server` 目录下执行（先 `cd services/interview-server`）。测试内 API Key 一律用 `unittest-dummy-*` 前缀，非真实凭据。Python 侧互操作固定对：`secret="unit-test-secret"`、明文 `"unittest-dummy-key-1234567890"`。

---

### Task 1: FernetBox（与 Python cryptography.Fernet 互操作的加密盒）+ 测试

**Files:**
- Create: `src/main/java/com/aiinterview/server/FernetBox.java`
- Test: `src/test/java/com/aiinterview/server/FernetBoxTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/aiinterview/server/FernetBoxTest.java`：

```java
package com.aiinterview.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FernetBoxTest {

    /** Python cryptography.Fernet 预生成（secret=unit-test-secret，明文=unittest-dummy-key-1234567890）。 */
    private static final String PYTHON_TOKEN =
        "gAAAAABqnmb9DISiibp-p8feUc3LieVSeG7BULp6JNUNMO_dRHTAIw0-5gl5rQbnL3tWfxUXwJZNVwIUAj-FEdBYTd-fC5UNey9MQgJDf0xEJ4gen7ARvyw=";

    @Test
    void encryptDecryptRoundtrip() {
        FernetBox box = new FernetBox("unit-test-secret");
        String token = box.encrypt("unittest-dummy-key-1234567890");
        assertNotEquals("unittest-dummy-key-1234567890", token);
        assertEquals("unittest-dummy-key-1234567890", box.decrypt(token));
    }

    @Test
    void decryptsPythonProducedToken() {
        FernetBox box = new FernetBox("unit-test-secret");
        assertEquals("unittest-dummy-key-1234567890", box.decrypt(PYTHON_TOKEN));
    }

    @Test
    void rotatedKeyFailsToDecrypt() {
        assertNull(new FernetBox("rotated-secret").decrypt(PYTHON_TOKEN));
    }

    @Test
    void decryptNullSafe() {
        FernetBox box = new FernetBox("unit-test-secret");
        assertNull(box.decrypt(null));
        assertNull(box.decrypt(""));
        assertNull(box.decrypt("not-a-token"));
    }

    @Test
    void maskShowsLastFour() {
        assertEquals("****7890", FernetBox.mask("unittest-dummy-key-1234567890"));
        assertEquals("********", FernetBox.mask("short"));   // 与 Python mask_secret 一致（短串 8 星）
        assertEquals("", FernetBox.mask(""));
        assertEquals("", FernetBox.mask(null));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=FernetBoxTest test`
Expected: 编译失败（`FernetBox` 不存在）。

- [ ] **Step 3: 实现 FernetBox**

创建 `src/main/java/com/aiinterview/server/FernetBox.java`：

```java
package com.aiinterview.server;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Fernet 对称加密盒：与 Python cryptography.Fernet 完全互操作。
 * 密钥派生：base64url(sha256(secret)) 解码为 32 字节后，前 16 字节为 HMAC 签名密钥、
 * 后 16 字节为 AES-128 加密密钥（与 cryptography.Fernet 的 _get_keys 分配一致）。
 * token 格式：base64url(0x80 || 8B 大端时间戳 || 16B IV || AES-128-CBC+PKCS7 密文 || 32B HMAC-SHA256)。
 */
public final class FernetBox {

    private static final int IV_LENGTH = 16;
    private static final int HMAC_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] encryptionKey;
    private final byte[] signingKey;

    public FernetBox(String secret) {
        byte[] derived = sha256(secret.getBytes(StandardCharsets.UTF_8));
        signingKey = new byte[16];
        encryptionKey = new byte[16];
        System.arraycopy(derived, 0, signingKey, 0, 16);
        System.arraycopy(derived, 16, encryptionKey, 0, 16);
    }

    /** 从 env 解析密钥：CONFIG_ENCRYPTION_KEY 优先，缺失回退 APP_SECRET_KEY；均缺失返回 null。 */
    public static FernetBox fromEnv() {
        String secret = firstNonBlank(System.getenv("CONFIG_ENCRYPTION_KEY"),
            System.getenv("APP_SECRET_KEY"));
        return secret == null ? null : new FernetBox(secret);
    }

    /** 加密明文为 Fernet token；异常返回 null。 */
    public String encrypt(String plain) {
        try {
            byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            byte[] ciphertext = aesCbc(true, plainBytes, iv);
            byte[] payload = new byte[1 + 8 + IV_LENGTH + ciphertext.length];
            payload[0] = (byte) 0x80;
            long timestamp = System.currentTimeMillis() / 1000;
            for (int i = 0; i < 8; i++) {
                payload[1 + i] = (byte) (timestamp >>> (8 * (7 - i)));
            }
            System.arraycopy(iv, 0, payload, 9, IV_LENGTH);
            System.arraycopy(ciphertext, 0, payload, 9 + IV_LENGTH, ciphertext.length);
            byte[] hmac = hmacSha256(payload);
            byte[] tokenBytes = new byte[payload.length + HMAC_LENGTH];
            System.arraycopy(payload, 0, tokenBytes, 0, payload.length);
            System.arraycopy(hmac, 0, tokenBytes, payload.length, HMAC_LENGTH);
            return Base64.getUrlEncoder().encodeToString(tokenBytes);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解密 Fernet token；token 空、密钥缺失或不匹配（密钥轮换/损坏）时返回 null，不抛异常。 */
    public String decrypt(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(token);
            if (tokenBytes.length < 1 + 8 + IV_LENGTH + HMAC_LENGTH || tokenBytes[0] != (byte) 0x80) {
                return null;
            }
            int payloadLength = tokenBytes.length - HMAC_LENGTH;
            byte[] payload = Arrays.copyOfRange(tokenBytes, 0, payloadLength);
            byte[] hmac = Arrays.copyOfRange(tokenBytes, payloadLength, tokenBytes.length);
            if (!MessageDigest.isEqual(hmac, hmacSha256(payload))) {
                return null;
            }
            byte[] iv = Arrays.copyOfRange(payload, 9, 9 + IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(payload, 9 + IV_LENGTH, payloadLength);
            byte[] plainBytes = aesCbc(false, ciphertext, iv);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 掩码展示：保留末 4 位；长度 < 8 返回 ****；空返回空串（与 Python mask_secret 对齐）。 */
    public static String mask(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        String tail = plain.length() >= 8 ? plain.substring(plain.length() - 4) : "****";
        return "****" + tail;
    }

    private byte[] aesCbc(boolean encrypt, byte[] input, byte[] iv) throws Exception {
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
            new CBCBlockCipher(new AESEngine()), new PKCS7Padding());
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(encryptionKey), iv));
        byte[] output = new byte[cipher.getOutputSize(input.length)];
        int len = cipher.processBytes(input, 0, input.length, output, 0);
        len += cipher.doFinal(output, len);
        return len == output.length ? output : Arrays.copyOf(output, len);
    }

    private byte[] hmacSha256(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        return (b != null && !b.trim().isEmpty()) ? b.trim() : null;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=FernetBoxTest test`
Expected: BUILD SUCCESS，5 tests passed（含 `decryptsPythonProducedToken` 互操作验证）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aiinterview/server/FernetBox.java src/test/java/com/aiinterview/server/FernetBoxTest.java
git commit -m "feat(settings): FernetBox——BouncyCastle 实现 Fernet 加密/解密/掩码，与 Python cryptography.Fernet 互操作"
```

---

### Task 2: LlmConfigMapper + LlmConfigDao + MyBatis 可注入 init + 真实 SQLite 测试

**Files:**
- Create: `src/main/java/com/aiinterview/server/db/LlmConfigMapper.java`
- Create: `src/main/java/com/aiinterview/server/db/LlmConfigDao.java`
- Modify: `src/main/java/com/aiinterview/server/db/MyBatis.java`（`init` 拆出 `init(String dbPath)` 重载 + 注册 mapper）
- Test: `src/test/java/com/aiinterview/server/db/LlmConfigDaoTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/aiinterview/server/db/LlmConfigDaoTest.java`：

```java
package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=LlmConfigDaoTest test`
Expected: 编译失败（`LlmConfigMapper`/`LlmConfigDao` 不存在，`MyBatis.init(String)` 不存在）。

- [ ] **Step 3: 实现**

创建 `src/main/java/com/aiinterview/server/db/LlmConfigMapper.java`：

```java
package com.aiinterview.server.db;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/** llm_config 表（id=1 单例，主仓 Python 模型配置写入）mapper。 */
public interface LlmConfigMapper {

    @Select("SELECT id, provider, base_url, model, copilot_model, think_model, api_key_enc, updated_at "
        + "FROM llm_config WHERE id = 1")
    Map<String, Object> findRow();

    @Update("INSERT INTO llm_config (id, provider, base_url, model, copilot_model, think_model, api_key_enc, updated_at) "
        + "VALUES (1, #{provider}, #{baseUrl}, #{model}, #{copilotModel}, #{thinkModel}, #{apiKeyEnc}, datetime('now')) "
        + "ON CONFLICT(id) DO UPDATE SET provider = excluded.provider, base_url = excluded.base_url, "
        + "model = excluded.model, copilot_model = excluded.copilot_model, think_model = excluded.think_model, "
        + "api_key_enc = excluded.api_key_enc, updated_at = datetime('now')")
    int upsert(@Param("provider") String provider, @Param("baseUrl") String baseUrl,
               @Param("model") String model, @Param("copilotModel") String copilotModel,
               @Param("thinkModel") String thinkModel, @Param("apiKeyEnc") String apiKeyEnc);
}
```

创建 `src/main/java/com/aiinterview/server/db/LlmConfigDao.java`：

```java
package com.aiinterview.server.db;

import org.apache.ibatis.session.SqlSession;

import java.util.Map;

/** llm_config 表 DAO（id=1 单例）。 */
public final class LlmConfigDao {

    private LlmConfigDao() {
    }

    /** 读取 id=1 行；无行返回 null。 */
    public static Map<String, Object> find() {
        try (SqlSession session = MyBatis.open()) {
            return session.getMapper(LlmConfigMapper.class).findRow();
        }
    }

    /** upsert id=1 行；apiKeyEnc 可为 null（清除密钥）。 */
    public static void save(String provider, String baseUrl, String model,
                            String copilotModel, String thinkModel, String apiKeyEnc) {
        try (SqlSession session = MyBatis.open()) {
            session.getMapper(LlmConfigMapper.class)
                .upsert(provider, baseUrl, model, copilotModel, thinkModel, apiKeyEnc);
        }
    }
}
```

修改 `src/main/java/com/aiinterview/server/db/MyBatis.java`：

将 `init(AppConfig config)` 拆出可注入路径的重载，并在 mapper 注册处追加 `LlmConfigMapper`：

```java
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
```

（删除原 `init(AppConfig config)` 的方法体，只保留转发；`import com.aiinterview.server.AppConfig;` 保留。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=LlmConfigDaoTest test`
Expected: BUILD SUCCESS，4 tests passed（真实临时 SQLite 往返/覆盖/单行/null 密钥）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aiinterview/server/db/LlmConfigMapper.java src/main/java/com/aiinterview/server/db/LlmConfigDao.java src/main/java/com/aiinterview/server/db/MyBatis.java src/test/java/com/aiinterview/server/db/LlmConfigDaoTest.java
git commit -m "feat(settings): LlmConfigMapper/Dao 读写 llm_config 表（id=1 upsert），MyBatis.init 支持注入 dbPath 便于测试"
```

---

### Task 3: EffectiveConfig + LlmConfigProvider（env 默认 + DB 覆盖 + TTL 缓存）+ 测试

**Files:**
- Create: `src/main/java/com/aiinterview/server/svc/EffectiveConfig.java`
- Create: `src/main/java/com/aiinterview/server/svc/LlmConfigProvider.java`
- Test: `src/test/java/com/aiinterview/server/svc/LlmConfigProviderTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/aiinterview/server/svc/LlmConfigProviderTest.java`：

```java
package com.aiinterview.server.svc;

import com.aiinterview.server.FernetBox;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmConfigProviderTest {

    private static Map<String, String> env(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void envDefaultsWhenNoDbRow() {
        LlmConfigProvider provider = new LlmConfigProvider(env(), () -> null);
        EffectiveConfig cfg = provider.effective();
        assertEquals("siliconflow", cfg.provider);
        assertEquals("https://api.siliconflow.cn/v1", cfg.baseUrl);
        assertEquals("THUDM/GLM-Z1-9B-0414", cfg.model);
        assertEquals("THUDM/GLM-Z1-9B-0414", cfg.copilotModel);
        assertEquals("THUDM/GLM-Z1-9B-0414", cfg.thinkModel);
        assertEquals("none", cfg.apiKeySource);
        assertNull(cfg.apiKey);
    }

    @Test
    void envApiKeyMarksEnvironment() {
        LlmConfigProvider provider = new LlmConfigProvider(env("LLM_API_KEY", "sk-env"), () -> null);
        assertEquals("sk-env", provider.effective().apiKey);
        assertEquals("environment", provider.effective().apiKeySource);
    }

    @Test
    void arkProviderUsesArkDefaults() {
        LlmConfigProvider provider = new LlmConfigProvider(env("LLM_PROVIDER", "ark"), () -> null);
        EffectiveConfig cfg = provider.effective();
        assertEquals("https://ark.cn-beijing.volces.com/api/coding/v3", cfg.baseUrl);
        assertEquals("deepseek-v4-flash", cfg.copilotModel);
    }

    @Test
    void dbRowOverridesEnvFieldByField() {
        String enc = new FernetBox("unit-test-secret").encrypt("unittest-dummy-key-1234567890");
        Map<String, Object> row = new HashMap<>();
        row.put("provider", "siliconflow");
        row.put("base_url", "https://db.example.com/v1");
        row.put("model", "db-model");
        row.put("copilot_model", "");
        row.put("think_model", "");
        row.put("api_key_enc", enc);
        LlmConfigProvider provider = new LlmConfigProvider(env("LLM_MODEL", "env-model"), () -> row);
        EffectiveConfig cfg = provider.effective();
        assertEquals("https://db.example.com/v1", cfg.baseUrl);
        assertEquals("db-model", cfg.model);
        assertEquals("env-model", cfg.copilotModel);   // DB 空字段回落 env 推导值
        assertEquals("unittest-dummy-key-1234567890", cfg.apiKey);
        assertEquals("database", cfg.apiKeySource);
    }

    @Test
    void invalidateForcesReread() {
        Map<String, Object> row = new HashMap<>();
        row.put("model", "m1");
        LlmConfigProvider provider = new LlmConfigProvider(env(), () -> row);
        assertEquals("m1", provider.effective().model);
        row.put("model", "m2");
        assertEquals("m1", provider.effective().model);   // TTL 缓存内
        provider.invalidate();
        assertEquals("m2", provider.effective().model);   // 失效后重读
    }

    @Test
    void undecryptableKeyFallsBackToEnv() {
        Map<String, Object> row = new HashMap<>();
        row.put("api_key_enc", "garbage-not-fernet");
        LlmConfigProvider provider = new LlmConfigProvider(env("LLM_API_KEY", "sk-env"), () -> row);
        assertEquals("sk-env", provider.effective().apiKey);
        assertEquals("environment", provider.effective().apiKeySource);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=LlmConfigProviderTest test`
Expected: 编译失败（`EffectiveConfig`/`LlmConfigProvider` 不存在）。

- [ ] **Step 3: 实现**

创建 `src/main/java/com/aiinterview/server/svc/EffectiveConfig.java`：

```java
package com.aiinterview.server.svc;

/** LLM 生效配置快照（env 默认 + llm_config 表逐字段覆盖后）。 */
public final class EffectiveConfig {

    public final String provider;
    public final String baseUrl;
    public final String model;
    public final String copilotModel;
    public final String thinkModel;
    /** 解密后的 API Key；未配置时为 null。 */
    public final String apiKey;
    /** database（读库覆盖）| environment（.env）| none。 */
    public final String apiKeySource;

    EffectiveConfig(String provider, String baseUrl, String model, String copilotModel,
                    String thinkModel, String apiKey, String apiKeySource) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.copilotModel = copilotModel;
        this.thinkModel = thinkModel;
        this.apiKey = apiKey;
        this.apiKeySource = apiKeySource;
    }
}
```

创建 `src/main/java/com/aiinterview/server/svc/LlmConfigProvider.java`：

```java
package com.aiinterview.server.svc;

import com.aiinterview.server.FernetBox;
import com.aiinterview.server.db.LlmConfigDao;

import java.util.Map;
import java.util.function.Supplier;

/** LLM 生效配置提供器：.env 默认 + llm_config 表逐字段覆盖（对应 Python utils/llm_config.py）。 */
public final class LlmConfigProvider {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmConfigProvider.class);

    public static final String DEFAULT_MODEL = "THUDM/GLM-Z1-9B-0414";
    public static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";
    public static final String ARK_DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/coding/v3";
    public static final String ARK_DEFAULT_COPILOT_MODEL = "deepseek-v4-flash";

    private static final long TTL_NANOS = 5_000_000_000L;

    private static volatile LlmConfigProvider instance = new LlmConfigProvider();

    private final Map<String, String> env;
    private final Supplier<Map<String, Object>> rowSource;
    private volatile EffectiveConfig cache;
    private volatile long cacheAtNanos;

    private LlmConfigProvider() {
        this(System.getenv(), LlmConfigDao::find);
    }

    /** 测试注入：自定义 env 与行来源（包内可见）。 */
    LlmConfigProvider(Map<String, String> env, Supplier<Map<String, Object>> rowSource) {
        this.env = env;
        this.rowSource = rowSource;
    }

    public static LlmConfigProvider instance() {
        return instance;
    }

    /** 生效配置；5s TTL 缓存内不重复读库。 */
    public EffectiveConfig effective() {
        EffectiveConfig cached = cache;
        if (cached != null && System.nanoTime() - cacheAtNanos < TTL_NANOS) {
            return cached;
        }
        EffectiveConfig computed = compute();
        cache = computed;
        cacheAtNanos = System.nanoTime();
        return computed;
    }

    /** 配置保存后调用：使下次 effective() 重新读库（对应 Python invalidate_llm_config_cache）。 */
    public void invalidate() {
        cache = null;
    }

    private EffectiveConfig compute() {
        String provider = firstNonBlank(env.get("LLM_PROVIDER"), "siliconflow").toLowerCase();
        String model = firstNonBlank(env.get("LLM_MODEL"), DEFAULT_MODEL);
        String baseUrl;
        String copilotModel;
        if ("ark".equals(provider)) {
            baseUrl = firstNonBlank(env.get("LLM_BASE_URL"), ARK_DEFAULT_BASE_URL);
            copilotModel = firstNonBlank(env.get("LLM_COPILOT_MODEL"), ARK_DEFAULT_COPILOT_MODEL);
        } else {
            baseUrl = firstNonBlank(env.get("LLM_BASE_URL"), DEFAULT_BASE_URL);
            copilotModel = firstNonBlank(env.get("LLM_COPILOT_MODEL"), model);
        }
        String thinkModel = firstNonBlank(env.get("LLM_THINK_MODEL"), model);
        String apiKey = env.get("LLM_API_KEY");
        String apiKeySource = (apiKey != null && !apiKey.trim().isEmpty()) ? "environment" : "none";

        Map<String, Object> row = null;
        try {
            row = rowSource.get();
        } catch (Exception e) {
            log.warn("读取 llm_config 失败，回退 .env 配置: {}", e.toString());
        }
        if (row != null) {
            provider = firstNonBlank(str(row.get("provider")), provider);
            baseUrl = firstNonBlank(str(row.get("base_url")), baseUrl);
            model = firstNonBlank(str(row.get("model")), model);
            copilotModel = firstNonBlank(str(row.get("copilot_model")), copilotModel);
            thinkModel = firstNonBlank(str(row.get("think_model")), thinkModel);
            String decrypted = decrypt(str(row.get("api_key_enc")));
            if (decrypted != null) {
                apiKey = decrypted;
            }
            if (decrypted != null || hasAnyDbTextValue(row)) {
                apiKeySource = "database";
            }
        }
        return new EffectiveConfig(provider, baseUrl, model, copilotModel, thinkModel, apiKey, apiKeySource);
    }

    private String decrypt(String apiKeyEnc) {
        if (apiKeyEnc == null || apiKeyEnc.isEmpty()) {
            return null;
        }
        FernetBox box = FernetBox.fromEnv();
        return box == null ? null : box.decrypt(apiKeyEnc);
    }

    private static boolean hasAnyDbTextValue(Map<String, Object> row) {
        return firstNonBlank(str(row.get("provider")), null) != null
            || firstNonBlank(str(row.get("base_url")), null) != null
            || firstNonBlank(str(row.get("model")), null) != null
            || firstNonBlank(str(row.get("copilot_model")), null) != null
            || firstNonBlank(str(row.get("think_model")), null) != null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=LlmConfigProviderTest test`
Expected: BUILD SUCCESS，6 tests passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aiinterview/server/svc/EffectiveConfig.java src/main/java/com/aiinterview/server/svc/LlmConfigProvider.java src/test/java/com/aiinterview/server/svc/LlmConfigProviderTest.java
git commit -m "feat(settings): LlmConfigProvider——env 默认 + llm_config 表逐字段覆盖 + TTL 缓存与 invalidate（对齐 Python utils/llm_config.py）"
```

---

### Task 4: LlmSettingsService（GET/PUT 核心逻辑）+ 真实 SQLite 测试

**Files:**
- Create: `src/main/java/com/aiinterview/server/svc/LlmSettingsService.java`
- Test: `src/test/java/com/aiinterview/server/svc/LlmSettingsServiceTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/aiinterview/server/svc/LlmSettingsServiceTest.java`：

```java
package com.aiinterview.server.svc;

import com.aiinterview.server.FernetBox;
import com.aiinterview.server.db.LlmConfigDao;
import com.aiinterview.server.db.MyBatis;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSettingsServiceTest {

    @TempDir
    Path tempDir;
    private final FernetBox box = new FernetBox("unit-test-secret");
    private static final String DUMMY_KEY = "unittest-dummy-key-1234567890";

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

    private static JSONObject savePayload() {
        JSONObject payload = new JSONObject();
        payload.put("provider", "SiliconFlow");
        payload.put("base_url", "https://api.example.com/v1");
        payload.put("model", "test-model");
        payload.put("api_key", DUMMY_KEY);
        return payload;
    }

    @Test
    void getConfigEmptyWhenNoRow() {
        JSONObject config = LlmSettingsService.getConfig(box);
        assertEquals("", config.getString("provider"));
        assertFalse(config.getBooleanValue("has_api_key"));
        assertEquals("none", config.getString("api_key_source"));
        assertTrue(config.containsKey("updated_at"));
    }

    @Test
    void saveThenReadMasksAndHidesPlaintext() {
        LlmSettingsService.update(savePayload(), box);

        JSONObject config = LlmSettingsService.getConfig(box);
        assertEquals("siliconflow", config.getString("provider"));   // provider 转小写
        assertEquals("test-model", config.getString("model"));
        assertTrue(config.getBooleanValue("has_api_key"));
        assertEquals("****7890", config.getString("api_key_masked"));
        assertEquals("database", config.getString("api_key_source"));
        assertFalse(config.toJSONString().contains(DUMMY_KEY));       // 明文不回显

        Map<String, Object> row = LlmConfigDao.find();
        assertNotEquals(DUMMY_KEY, row.get("api_key_enc"));           // 落库为密文
        assertEquals(DUMMY_KEY, box.decrypt(String.valueOf(row.get("api_key_enc"))));
    }

    @Test
    void blankApiKeyKeepsExisting() {
        LlmSettingsService.update(savePayload(), box);
        JSONObject payload = new JSONObject();
        payload.put("model", "new-model");
        LlmSettingsService.update(payload, box);

        JSONObject config = LlmSettingsService.getConfig(box);
        assertEquals("new-model", config.getString("model"));
        assertEquals("****7890", config.getString("api_key_masked")); // 密钥保留
    }

    @Test
    void clearApiKeyRemoves() {
        LlmSettingsService.update(savePayload(), box);
        JSONObject payload = new JSONObject();
        payload.put("clear_api_key", true);
        LlmSettingsService.update(payload, box);

        JSONObject config = LlmSettingsService.getConfig(box);
        assertFalse(config.getBooleanValue("has_api_key"));
        assertEquals("none", config.getString("api_key_source"));
    }

    @Test
    void invalidBaseUrlRejected() {
        JSONObject payload = new JSONObject();
        payload.put("base_url", "ftp://bad");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LlmSettingsService.update(payload, box));
        assertTrue(ex.getMessage().contains("http(s)"));
    }

    @Test
    void missingEncryptionKeyRejectsPlaintextSave() {
        JSONObject payload = new JSONObject();
        payload.put("api_key", DUMMY_KEY);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LlmSettingsService.update(payload, null));
        assertTrue(ex.getMessage().contains("CONFIG_ENCRYPTION_KEY"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=LlmSettingsServiceTest test`
Expected: 编译失败（`LlmSettingsService` 不存在）。

- [ ] **Step 3: 实现**

创建 `src/main/java/com/aiinterview/server/svc/LlmSettingsService.java`：

```java
package com.aiinterview.server.svc;

import com.aiinterview.server.FernetBox;
import com.aiinterview.server.db.LlmConfigDao;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;

/** 模型设置读写（对应 Python routes/route_settings.py 的 GET/PUT 逻辑）。 */
public final class LlmSettingsService {

    private static final String[] TEXT_FIELDS = {"provider", "base_url", "model", "copilot_model", "think_model"};

    private LlmSettingsService() {
    }

    /** GET 响应 config 载荷：掩码回显，明文/密文不出现。 */
    public static JSONObject getConfig() {
        return getConfig(FernetBox.fromEnv());
    }

    /** 包内重载：测试可注入 FernetBox。 */
    static JSONObject getConfig(FernetBox box) {
        JSONObject payload = new JSONObject();
        payload.put("provider", "");
        payload.put("base_url", "");
        payload.put("model", "");
        payload.put("copilot_model", "");
        payload.put("think_model", "");
        payload.put("has_api_key", false);
        payload.put("api_key_masked", "");
        payload.put("api_key_source", "none");
        payload.put("updated_at", null);
        Map<String, Object> row = null;
        try {
            row = LlmConfigDao.find();
        } catch (Exception ignored) {
            // 表缺失等：按未配置处理
        }
        if (row == null) {
            return payload;
        }
        payload.put("provider", nz(row.get("provider")));
        payload.put("base_url", nz(row.get("base_url")));
        payload.put("model", nz(row.get("model")));
        payload.put("copilot_model", nz(row.get("copilot_model")));
        payload.put("think_model", nz(row.get("think_model")));
        String key = decryptQuietly(box, nz(row.get("api_key_enc")));
        boolean hasKey = key != null && !key.isEmpty();
        payload.put("has_api_key", hasKey);
        payload.put("api_key_masked", FernetBox.mask(key));
        payload.put("api_key_source", hasKey ? "database" : "none");
        payload.put("updated_at", row.get("updated_at"));
        return payload;
    }

    /** PUT：校验并写入；校验失败抛 IllegalArgumentException(message)（路由层转 400）。 */
    public static void update(JSONObject payload) {
        update(payload, FernetBox.fromEnv());
    }

    /** 包内重载：测试可注入 FernetBox（null = 无加密密钥）。 */
    static void update(JSONObject payload, FernetBox box) {
        Map<String, Object> row;
        try {
            row = LlmConfigDao.find();
        } catch (Exception e) {
            row = null;
        }
        String provider = row == null ? "" : nz(row.get("provider"));
        String baseUrl = row == null ? "" : nz(row.get("base_url"));
        String model = row == null ? "" : nz(row.get("model"));
        String copilotModel = row == null ? "" : nz(row.get("copilot_model"));
        String thinkModel = row == null ? "" : nz(row.get("think_model"));
        String apiKeyEnc = row == null ? null : nz(row.get("api_key_enc"));

        for (String field : TEXT_FIELDS) {
            if (!payload.containsKey(field)) {
                continue;
            }
            String value = nz(payload.get(field)).trim();
            if ("base_url".equals(field) && !value.isEmpty()
                && !value.toLowerCase().startsWith("http://")
                && !value.toLowerCase().startsWith("https://")) {
                throw new IllegalArgumentException("Base URL 必须以 http(s):// 开头");
            }
            if ("provider".equals(field)) {
                value = value.toLowerCase();
            }
            switch (field) {
                case "provider":
                    provider = value;
                    break;
                case "base_url":
                    baseUrl = value;
                    break;
                case "model":
                    model = value;
                    break;
                case "copilot_model":
                    copilotModel = value;
                    break;
                case "think_model":
                    thinkModel = value;
                    break;
                default:
                    break;
            }
        }

        if (isTruthy(payload.get("clear_api_key"))) {
            apiKeyEnc = null;
        }
        String apiKey = nz(payload.get("api_key")).trim();
        if (!apiKey.isEmpty()) {
            if (box == null) {
                throw new IllegalArgumentException(
                    "未配置加密密钥（.env 需设置 CONFIG_ENCRYPTION_KEY 或 APP_SECRET_KEY），拒绝明文保存 API Key");
            }
            String token = box.encrypt(apiKey);
            if (token == null) {
                throw new IllegalArgumentException("API Key 加密失败，请检查加密密钥配置");
            }
            apiKeyEnc = token;
        }

        LlmConfigDao.save(provider, baseUrl, model, copilotModel, thinkModel, apiKeyEnc);
        LlmConfigProvider.instance().invalidate();
    }

    private static boolean isTruthy(Object value) {
        return value != null && (Boolean.TRUE.equals(value)
            || (value instanceof String && ("1".equals(value)
                || "true".equalsIgnoreCase((String) value))));
    }

    private static String decryptQuietly(FernetBox box, String token) {
        if (token == null || token.isEmpty() || box == null) {
            return null;
        }
        return box.decrypt(token);
    }

    private static String nz(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=LlmSettingsServiceTest test`
Expected: BUILD SUCCESS，6 tests passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aiinterview/server/svc/LlmSettingsService.java src/test/java/com/aiinterview/server/svc/LlmSettingsServiceTest.java
git commit -m "feat(settings): LlmSettingsService——GET 掩码回显 / PUT 加密落库、留空保留、clear 清除、无密钥 400（对齐 Python route_settings.py）"
```

---

### Task 5: SettingsRoutes + InterviewServerApplication 注册 + 401 测试

**Files:**
- Create: `src/main/java/com/aiinterview/server/web/SettingsRoutes.java`
- Modify: `src/main/java/com/aiinterview/server/InterviewServerApplication.java`（注册路由）
- Test: `src/test/java/com/aiinterview/server/web/SettingsRoutesTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/aiinterview/server/web/SettingsRoutesTest.java`：

```java
package com.aiinterview.server.web;

import com.aiinterview.server.db.MyBatis;
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=SettingsRoutesTest test`
Expected: 编译失败（`SettingsRoutes` 不存在）。

- [ ] **Step 3: 实现**

创建 `src/main/java/com/aiinterview/server/web/SettingsRoutes.java`：

```java
package com.aiinterview.server.web;

import com.aiinterview.server.svc.LlmSettingsService;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;
import tech.smartboot.feat.router.Router;

/** 模型设置路由：GET/PUT /api/settings/llm（登录保护，契约与 Python route_settings.py 对齐）。 */
public final class SettingsRoutes {

    private SettingsRoutes() {
    }

    public static void register(Router router) {
        RouteSupport.route(router, "/api/settings/llm", "GET", (ctx) -> handleGet(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/api/settings/llm", "PUT", (ctx) -> handlePut(ctx.Request, ctx.Response));
    }

    static void handleGet(HttpRequest request, HttpResponse response) throws Throwable {
        if (RouteSupport.require(request, response) == null) {
            return;
        }
        JSONObject body = new JSONObject();
        body.put("success", true);
        body.put("config", LlmSettingsService.getConfig());
        RouteSupport.ok(response, body.toJSONString());
    }

    static void handlePut(HttpRequest request, HttpResponse response) throws Throwable {
        if (RouteSupport.require(request, response) == null) {
            return;
        }
        JSONObject payload = RouteSupport.parseBody(request);
        try {
            LlmSettingsService.update(payload);
        } catch (IllegalArgumentException e) {
            RouteSupport.error(response, 400, e.getMessage());
            return;
        }
        RouteSupport.ok(response, "{\"success\":true}");
    }
}
```

修改 `src/main/java/com/aiinterview/server/InterviewServerApplication.java`：在 `ProfileRoutes.register(router);` 之后追加：

```java
        ProfileRoutes.init(config);
        ProfileRoutes.register(router);
        SettingsRoutes.register(router);
```

（如 `ProfileRoutes` 不存在该行则加在 `ReviewRoutes.register(router);` 之后；确保 `SettingsRoutes` 在 `router` 上注册且位于 `server.listen(...)` 之前。
注意：还需在文件顶部 import 区补 `import com.aiinterview.server.web.SettingsRoutes;`，否则编译报「找不到符号 SettingsRoutes」。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=SettingsRoutesTest test`
Expected: BUILD SUCCESS，2 tests passed（未登录 GET/PUT 均 401）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aiinterview/server/web/SettingsRoutes.java src/main/java/com/aiinterview/server/InterviewServerApplication.java src/test/java/com/aiinterview/server/web/SettingsRoutesTest.java
git commit -m "feat(settings): SettingsRoutes——GET/PUT /api/settings/llm（登录保护 401，校验失败 400），注册进 InterviewServerApplication"
```

---

### Task 6: Llm 动态配置改造 + 6 处消费方切换 + LlmTest

**Files:**
- Modify: `src/main/java/com/aiinterview/server/svc/Llm.java`（provider 构造 + `resolveChatModel()` 动态重建）
- Modify: `src/main/java/com/aiinterview/server/InterviewServerApplication.java:106`（`new Llm(config)` → `new Llm()`）
- Modify: `src/main/java/com/aiinterview/server/AnswerService.java:22`
- Modify: `src/main/java/com/aiinterview/server/web/PlanRoutes.java:48`
- Modify: `src/main/java/com/aiinterview/server/web/MockRoutes.java:28`
- Modify: `src/main/java/com/aiinterview/server/web/ReviewRoutes.java:32`
- Modify: `src/main/java/com/aiinterview/server/svc/ResumeAnalyzer.java:30`
- Test: `src/test/java/com/aiinterview/server/svc/LlmTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/aiinterview/server/svc/LlmTest.java`：

```java
package com.aiinterview.server.svc;

import org.junit.jupiter.api.Test;
import tech.smartboot.feat.ai.chat.ChatModel;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmTest {

    private static EffectiveConfig cfg(String baseUrl, String model, String apiKey) {
        return new EffectiveConfig("siliconflow", baseUrl, model, model, model, apiKey, "database");
    }

    @Test
    void rebuildsChatModelWhenEffectiveConfigChanges() {
        LlmConfigProvider provider = mock(LlmConfigProvider.class);
        when(provider.effective()).thenReturn(
            cfg("https://a.example.com/v1", "m-a", "k-a"),
            cfg("https://b.example.com/v1", "m-b", "k-b"));

        Llm llm = new Llm(provider, 5);
        ChatModel first = llm.resolveChatModel();
        ChatModel second = llm.resolveChatModel();
        assertNotSame(first, second);
    }

    @Test
    void keepsChatModelWhenConfigUnchanged() {
        LlmConfigProvider provider = mock(LlmConfigProvider.class);
        when(provider.effective()).thenReturn(cfg("https://a.example.com/v1", "m-a", "k-a"));

        Llm llm = new Llm(provider, 5);
        assertSame(llm.resolveChatModel(), llm.resolveChatModel());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=LlmTest test`
Expected: 编译失败（`Llm(LlmConfigProvider, long)` 构造与 `resolveChatModel()` 不存在）。

- [ ] **Step 3: 实现**

修改 `src/main/java/com/aiinterview/server/svc/Llm.java`：

删除 `import com.aiinterview.server.AppConfig;`，新增 `import java.util.Objects;`。将类首部字段与构造替换为：

```java
public final class Llm {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Llm.class);

    private final long timeoutSeconds;
    /** 配置提供器；测试注入固定 ChatModel 时为 null。 */
    private final LlmConfigProvider provider;
    /** 测试注入的固定 ChatModel（不走 provider）。 */
    private final ChatModel fixedChatModel;
    private volatile ChatModel chatModel;
    private volatile String chatBaseUrl;
    private volatile String chatModelName;
    private volatile String chatApiKey;

    /** 生产构造：配置来自 LlmConfigProvider（env + llm_config 表，保存后立即生效）。 */
    public Llm() {
        this(LlmConfigProvider.instance(), llmTimeoutFromEnv());
    }

    public Llm(LlmConfigProvider provider, long timeoutSeconds) {
        this.provider = provider;
        this.timeoutSeconds = timeoutSeconds;
        this.fixedChatModel = null;
    }

    /** 测试注入 mock ChatModel 与超时（AnswerServiceTest / CopilotWsControlTest 使用）。 */
    public Llm(ChatModel chatModel, long timeoutSeconds) {
        this.provider = null;
        this.fixedChatModel = chatModel;
        this.timeoutSeconds = timeoutSeconds;
    }

    private static long llmTimeoutFromEnv() {
        try {
            return Math.max(5, Math.min(Long.parseLong(System.getenv("LLM_TIMEOUT_SECONDS")), 120));
        } catch (Exception e) {
            return 45;
        }
    }

    /** 解析当前生效的 ChatModel：配置变化时重建（包内可见，供 LlmTest 验证）。 */
    ChatModel resolveChatModel() {
        if (fixedChatModel != null) {
            return fixedChatModel;
        }
        EffectiveConfig cfg = provider.effective();
        ChatModel current = chatModel;
        if (current != null && chatBaseUrl != null && chatModelName != null
            && chatBaseUrl.equals(cfg.baseUrl) && chatModelName.equals(cfg.model)
            && Objects.equals(chatApiKey, cfg.apiKey)) {
            return current;
        }
        ChatModel created = FeatAI.chatModel(opts -> opts
            .baseUrl(cfg.baseUrl)
            .model(cfg.model)
            .apiKey(cfg.apiKey));
        chatModel = created;
        chatBaseUrl = cfg.baseUrl;
        chatModelName = cfg.model;
        chatApiKey = cfg.apiKey;
        return created;
    }
```

删除原 `public Llm(AppConfig config) { ... }` 构造。

将 `chat(...)` 与 `streamChat(...)` 内两处 `chatModel.chatStream(...)` 改为 `resolveChatModel().chatStream(...)`。

（保留原 `messagesToString`/`getMessageContent`/`truncate` 私有方法不动。）

然后修改 6 处消费方，全部 `new Llm(config)` → `new Llm()`：

- `src/main/java/com/aiinterview/server/InterviewServerApplication.java` L106：`new Llm(config), session.userId));` → `new Llm(), session.userId));`
- `src/main/java/com/aiinterview/server/AnswerService.java` L22：`this(new Llm(config));` → `this(new Llm());`
- `src/main/java/com/aiinterview/server/web/PlanRoutes.java` L48：`new PreparationService(new Llm(config));` → `new PreparationService(new Llm());`
- `src/main/java/com/aiinterview/server/web/MockRoutes.java` L28：`new MockInterviewService(new Llm(config));` → `new MockInterviewService(new Llm());`
- `src/main/java/com/aiinterview/server/web/ReviewRoutes.java` L32：`llm = new Llm(config);` → `llm = new Llm();`
- `src/main/java/com/aiinterview/server/svc/ResumeAnalyzer.java` L30：`llm = new Llm(config);` → `llm = new Llm();`

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=LlmTest,AnswerServiceTest,CopilotWsControlTest test`
Expected: BUILD SUCCESS（LlmTest 2 passed；既有 AnswerServiceTest / CopilotWsControlTest 不受影响，仍使用 `new Llm(ChatModel, long)` 测试构造）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aiinterview/server/svc/Llm.java src/main/java/com/aiinterview/server/InterviewServerApplication.java src/main/java/com/aiinterview/server/AnswerService.java src/main/java/com/aiinterview/server/web/PlanRoutes.java src/main/java/com/aiinterview/server/web/MockRoutes.java src/main/java/com/aiinterview/server/web/ReviewRoutes.java src/main/java/com/aiinterview/server/svc/ResumeAnalyzer.java src/test/java/com/aiinterview/server/svc/LlmTest.java
git commit -m "feat(settings): Llm 动态配置——每次调用解析 LlmConfigProvider，配置变化重建 ChatModel；6 处消费方改 new Llm()"
```

---

### Task 7: 全量回归 + 收尾

**Files:** 无新文件（如全量测试暴露问题则修复）。

- [ ] **Step 1: 全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，全部测试通过（新增 4 个测试类 + 既有 65 个左右测试）。

- [ ] **Step 2: 确认工作区干净且提交完整**

Run: `git status --short`
Expected: 空输出；`git log --oneline -7` 显示 6 个 feat(settings) 提交。

- [ ] **Step 3: 更新设计文档状态**

修改 `docs/superpowers/specs/2026-09-07-java-llm-settings-design.md` 顶部 `状态` 行：
`> 状态：已获用户批准（方案 A：共享配置提供器 + 动态生效）` → `> 状态：已实现（方案 A），mvn test 全量通过`

Run: `git add docs/superpowers/specs/2026-09-07-java-llm-settings-design.md && git commit -m "docs: 标记 Java 模型管理设计为已实现"`

---

## 自审记录（writing-plans）

**Spec 覆盖核对：** FernetBox（Task 1）→ 规格组件 1；LlmConfigMapper/Dao（Task 2）→ 组件 2；LlmConfigProvider/EffectiveConfig（Task 3）→ 组件 3 与数据流；LlmSettingsService（Task 4）→ 组件 4 的 GET/PUT 核心逻辑；SettingsRoutes（Task 5）→ 组件 4 薄层与注册；Llm 改造与 6 处消费方（Task 6）→ 组件 5 与数据流；测试对应规格测试节（FernetBoxTest 互操作、Provider 优先级/缓存、Service 掩码/留空/clear/400、Routes 401、Llm 重建）。规格验收标准 1-4 均由对应 Task 覆盖；错误处理表逐项有测试或实现。

**占位符扫描：** 无 TBD/TODO/“类似上文”；每步含完整代码与精确命令。

**类型一致性：** `EffectiveConfig` 字段（provider/baseUrl/model/copilotModel/thinkModel/apiKey/apiKeySource）在 Provider/Llm/测试中同名同型；`FernetBox.mask/encrypt/decrypt`、`LlmConfigDao.find/save`、`LlmSettingsService.getConfig/update`、`Llm.resolveChatModel` 签名在全部 Task 中一致；6 处消费方统一 `new Llm()`。
