package com.aiinterview.server.svc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import tech.smartboot.feat.ai.chat.ChatModel;
import tech.smartboot.feat.ai.chat.ChatStreamListener;
import tech.smartboot.feat.ai.chat.entity.ChatResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class LlmAnswerTest {

    private Llm llmReturning(String completion) {
        ChatModel chatModel = mock(ChatModel.class);
        doAnswer(invocation -> {
            ChatStreamListener listener = invocation.getArgument(2);
            listener.onStreamResponse(completion);
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            listener.onCompletion(response);
            return null;
        }).when(chatModel).chatStream(anyList(), any(), any(ChatStreamListener.class));
        return new Llm(chatModel, 5);
    }

    @Test
    void generateParsesStructuredAnswerJson() throws Exception {
        Llm llm = llmReturning(
            "{\"answer_points\":[\"要点一\",\"要点二\"],"
                + "\"reference_answer\":\"参考回答正文\","
                + "\"follow_up\":\"可能的追问\"}"
        );

        JSONObject result = LlmAnswer.generate(llm, "请介绍一下自己", "岗位要求：Java\n简历摘要：{}");

        assertNotNull(result);
        JSONArray points = result.getJSONArray("answer_points");
        assertEquals(2, points.size());
        assertEquals("要点一", points.getString(0));
        assertEquals("参考回答正文", result.getString("reference_answer"));
        assertEquals("可能的追问", result.getString("follow_up"));
    }

    @Test
    void generateInvokesLlmWithThreeMessages() throws Exception {
        AtomicReference<Integer> messageCount = new AtomicReference<>(0);
        ChatModel chatModel = mock(ChatModel.class);
        doAnswer(invocation -> {
            messageCount.set(invocation.<java.util.List<?>>getArgument(0).size());
            ChatStreamListener listener = invocation.getArgument(2);
            listener.onStreamResponse("{\"answer_points\":[],\"reference_answer\":\"\",\"follow_up\":\"\"}");
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            listener.onCompletion(response);
            return null;
        }).when(chatModel).chatStream(anyList(), any(), any(ChatStreamListener.class));

        LlmAnswer.generate(new Llm(chatModel, 5), "测试问题", "上下文片段");
        assertEquals(3, messageCount.get().intValue());
    }
}
