package com.aiinterview.server.svc;

import com.aiinterview.server.copilot.GenerationRegistry;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import tech.smartboot.feat.ai.chat.ChatModel;
import tech.smartboot.feat.ai.chat.ChatStreamListener;
import tech.smartboot.feat.ai.chat.entity.ChatResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CopilotAnswerServiceTest {

    @Test
    void streamsAnswerEventsInOrder() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        doAnswer(invocation -> {
            ChatStreamListener listener = invocation.getArgument(2);
            listener.onStreamResponse("{\"type\":\"answer_points\",\"answer_points\":[\"要点一\"]}\n");
            listener.onStreamResponse("{\"type\":\"answer_delta\",\"text\":\"参考\"}\n");
            listener.onStreamResponse("{\"type\":\"completed\",\"follow_up\":\"追问\"}\n");
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            listener.onCompletion(response);
            return null;
        }).when(chatModel).chatStream(anyList(), any(), any(ChatStreamListener.class));

        Llm llm = new Llm(chatModel, 5);
        GenerationRegistry registry = new GenerationRegistry();
        long generationId = registry.begin();
        List<String> events = new ArrayList<>();

        CopilotAnswerService.generate(
            llm, 1L, 10L, generationId, registry,
            "面试官问题：测试\n岗位要求：Java",
            (event, payload) -> events.add(event)
        );

        assertTrue(events.indexOf("answer_started") >= 0);
        assertTrue(events.indexOf("answer_delta") > events.indexOf("answer_started"));
        assertTrue(events.contains("answer_completed"));
    }

    @Test
    void cancelledGenerationStopsEmitting() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        doAnswer(invocation -> {
            ChatStreamListener listener = invocation.getArgument(2);
            listener.onStreamResponse("{\"type\":\"answer_points\",\"answer_points\":[\"要点\"]}\n");
            listener.onStreamResponse("{\"type\":\"answer_delta\",\"text\":\"不应出现\"}\n");
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            listener.onCompletion(response);
            return null;
        }).when(chatModel).chatStream(anyList(), any(), any(ChatStreamListener.class));

        Llm llm = new Llm(chatModel, 5);
        GenerationRegistry registry = new GenerationRegistry();
        long generationId = registry.begin();
        registry.cancel();

        JSONObject result = CopilotAnswerService.generate(
            llm, 1L, 10L, generationId, registry, "上下文",
            (event, payload) -> { }
        );
        assertEquals(true, result.getBoolean("cancelled"));
    }
}
