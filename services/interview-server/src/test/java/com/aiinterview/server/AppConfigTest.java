package com.aiinterview.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigTest {

    @TempDir
    File tempDir;

    @Test
    void backendGatewayForcesGateway() {
        Map<String, String> env = new HashMap<>();
        env.put("ASR_BACKEND", "gateway");
        env.put("ASR_PROVIDER", "in_process");
        assertEquals("gateway", resolve(env));
    }

    @Test
    void backendBuiltinForcesInProcess() {
        Map<String, String> env = new HashMap<>();
        env.put("ASR_BACKEND", "builtin");
        env.put("ASR_PROVIDER", "gateway");
        assertEquals("in_process", resolve(env));
    }

    @Test
    void backendAutoResolvesByLocalModelDir() {
        File models = new File(tempDir, "models");
        File online = new File(models, "online-model");
        online.mkdirs();

        Map<String, String> env = new HashMap<>();
        env.put("ASR_BACKEND", "auto");
        assertEquals("in_process",
            AppConfig.resolveAsrProviderForTest(env, models.getPath(), "online-model"));
        assertEquals("gateway",
            AppConfig.resolveAsrProviderForTest(env, new File(tempDir, "missing").getPath(), "online-model"));
    }

    @Test
    void emptyBackendFallsBackToLegacyProvider() {
        Map<String, String> env = new HashMap<>();
        assertEquals("in_process", resolve(env));
        env.put("ASR_PROVIDER", "GATEWAY");
        assertEquals("gateway", resolve(env));
    }

    @Test
    void unknownBackendFails() {
        Map<String, String> env = new HashMap<>();
        env.put("ASR_BACKEND", "wat");
        assertThrows(IllegalStateException.class, () -> resolve(env));
    }

    private static String resolve(Map<String, String> env) {
        return AppConfig.resolveAsrProviderForTest(env, "third_party/mica-voice/models", "x-asr-zh-en-chunk-960ms");
    }
}
