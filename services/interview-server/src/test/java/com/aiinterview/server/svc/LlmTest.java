package com.aiinterview.server.svc;

import org.junit.jupiter.api.Test;
import tech.smartboot.feat.ai.chat.ChatModel;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class LlmTest {

    private static Map<String, String> env() {
        return new HashMap<>();
    }

    @Test
    void rebuildsChatModelWhenEffectiveConfigChanges() {
        Map<String, Object> row = new HashMap<>();
        row.put("base_url", "https://a.example.com/v1");
        row.put("model", "m-a");
        LlmConfigProvider provider = new LlmConfigProvider(env(), () -> row);
        Llm llm = new Llm(provider, 5);
        ChatModel first = llm.resolveChatModel();

        row.put("base_url", "https://b.example.com/v1");
        row.put("model", "m-b");
        provider.invalidate();
        ChatModel second = llm.resolveChatModel();
        assertNotSame(first, second);
    }

    @Test
    void keepsChatModelWhenConfigUnchanged() {
        Map<String, Object> row = new HashMap<>();
        row.put("base_url", "https://a.example.com/v1");
        row.put("model", "m-a");
        LlmConfigProvider provider = new LlmConfigProvider(env(), () -> row);
        Llm llm = new Llm(provider, 5);
        assertSame(llm.resolveChatModel(), llm.resolveChatModel());
    }
}
