package com.aiinterview.server;

import com.aiinterview.server.svc.Llm;
import org.junit.jupiter.api.Test;
import tech.smartboot.feat.ai.chat.ChatModel;
import tech.smartboot.feat.ai.chat.ChatStreamListener;
import tech.smartboot.feat.ai.chat.entity.ChatResponse;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AnswerServiceTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final AtomicReference<ChatStreamListener> listenerRef = new AtomicReference<>();

    private void captureListener() {
        doAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(2));
            return null;
        }).when(chatModel).chatStream(anyList(), any(), any(ChatStreamListener.class));
    }

    /** 等待 chatStream 被调用并返回捕获的 listener（answer 在独立线程运行）。 */
    private ChatStreamListener awaitListener() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (listenerRef.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (listenerRef.get() == null) {
            throw new IllegalStateException("chatStream 未被调用");
        }
        return listenerRef.get();
    }

    /** 在独立线程运行 answer 并收集结果/异常。 */
    private Thread runAnswer(AnswerService service, AtomicReference<String> result,
                             AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                result.set(service.answer("问题", "上下文"));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        return thread;
    }

    @Test
    void aggregatesStreamedContentIntoOneAnswer() throws Exception {
        captureListener();
        AnswerService service = new AnswerService(new Llm(chatModel, 5));
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread answerThread = runAnswer(service, result, failure);
        ChatStreamListener listener = awaitListener();
        listener.onStreamResponse("要");
        listener.onStreamResponse("点");
        listener.onStreamResponse("1");
        ChatResponse response = new ChatResponse();
        response.setSuccess(true);
        listener.onCompletion(response);
        answerThread.join(5000);
        assertEquals(null, failure.get(), "不应失败: " + failure.get());
        assertEquals("要点1", result.get());
    }

    @Test
    void propagatesListenerError() throws Exception {
        captureListener();
        AnswerService service = new AnswerService(new Llm(chatModel, 5));
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread answerThread = runAnswer(service, result, failure);
        ChatStreamListener listener = awaitListener();
        listener.onError(new RuntimeException("boom"));
        answerThread.join(5000);
        assertTrue(failure.get() != null, "onError 应传播为异常");
        String message = failure.get().getMessage();
        assertTrue(message != null && message.contains("boom"),
            "异常消息应包含 boom: " + message);
    }

    @Test
    void timesOutAndIgnoresLateCallbacks() throws Exception {
        captureListener();
        AnswerService service = new AnswerService(new Llm(chatModel, 1));
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread answerThread = runAnswer(service, result, failure);
        ChatStreamListener listener = awaitListener();
        answerThread.join(8000);
        assertTrue(failure.get() instanceof TimeoutException,
            "应抛出 TimeoutException: " + failure.get());
        // 迟到的回调不得抛异常，也不得污染结果
        listener.onStreamResponse("迟到的内容");
        ChatResponse response = new ChatResponse();
        response.setSuccess(true);
        listener.onCompletion(response);
        assertEquals(null, result.get());
    }
}
