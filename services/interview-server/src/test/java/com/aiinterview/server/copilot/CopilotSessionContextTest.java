package com.aiinterview.server.copilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotSessionContextTest {

    @Test
    void buildLlmContextIncludesQuestionAndJobDescription() {
        CopilotSessionContext context = new CopilotSessionContext(1L, "Java 后端", "{\"name\":\"张三\"}");
        String text = context.buildLlmContext("请介绍一下自己");
        assertTrue(text.contains("请介绍一下自己"));
        assertTrue(text.contains("Java 后端"));
        assertTrue(text.contains("张三"));
    }
}
