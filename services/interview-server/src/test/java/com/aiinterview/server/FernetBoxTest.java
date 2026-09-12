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
        assertEquals("********", FernetBox.mask("short"));   // 与 Python mask_secret 一致
        assertEquals("", FernetBox.mask(""));
        assertEquals("", FernetBox.mask(null));
    }
}
