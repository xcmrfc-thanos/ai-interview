package com.aiinterview.server.svc;

import tech.smartboot.feat.ai.FeatAI;
import tech.smartboot.feat.ai.chat.ChatModel;
import tech.smartboot.feat.ai.chat.ChatStreamListener;
import tech.smartboot.feat.ai.chat.entity.ChatResponse;
import tech.smartboot.feat.ai.chat.entity.Message;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 统一 LLM 客户端：非流式聚合调用（对应 Python utils/llm_client.py）。 */
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

    /** 非流式对话，返回完整文本；超时抛 TimeoutException。
     *  注意限制：feat ChatModel.chatStream 返回 void 无可取消句柄，超时后底层 HTTP 连接可能仍在跑，
     *  这里只保证回调结果被丢弃（settled 标记）与调用方不被阻塞。 */
    public String chat(List<Message> messages, double temperature) throws Exception {
        // 记录请求开始时间
        long startTime = System.currentTimeMillis();
        
        // 打印请求日志（截断到500字）
        String requestLog = messagesToString(messages);
        log.info("【LLM请求】消息数:{}, 总字数:{}", messages.size(), requestLog.length());
        
        StringBuilder builder = new StringBuilder();
        CompletableFuture<Void> done = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean(false);
        resolveChatModel().chatStream(messages, null, new ChatStreamListener() {
            @Override
            public void onStreamResponse(String content) {
                if (!settled.get()) {
                    builder.append(content);
                }
            }

            @Override
            public void onCompletion(ChatResponse chatResponse) {
                if (!settled.get()) {
                    settled.set(true);
                    long elapsed = System.currentTimeMillis() - startTime;
                    String response = builder.toString();
                    // 尝试获取 token 使用量（兼容不同版本 API）
                    String tokenInfo = "";
                    try {
                        Object usage = chatResponse.getUsage();
                        if (usage != null) {
                            Object promptTokens = usage.getClass().getMethod("getPromptTokens").invoke(usage);
                            Object completionTokens = usage.getClass().getMethod("getCompletionTokens").invoke(usage);
                            tokenInfo = String.format(", 输入Token:%s, 输出Token:%s", promptTokens, completionTokens);
                        }
                    } catch (Exception ignored) {}
                    log.info("【LLM响应】延迟:{}ms{} 字数:{}", elapsed, tokenInfo, response.length());
                    done.complete(null);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (!settled.get()) {
                    settled.set(true);
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.error("【LLM错误】延迟:{}ms, 错误:{}", elapsed, throwable.getMessage());
                    done.completeExceptionally(throwable);
                }
            }
        });
        try {
            done.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            settled.set(true);
            log.warn("LLM 调用超时（{} 秒），丢弃迟到回调", timeoutSeconds);
            throw e;
        } catch (InterruptedException e) {
            settled.set(true);
            Thread.currentThread().interrupt();
            throw e;
        }
        return builder.toString();
    }

    /**
     * 流式对话：每个 token/chunk 回调 onChunk，完成后返回聚合文本。
     */
    public void streamChat(java.util.List<Message> messages, double temperature, java.util.function.Consumer<String> onChunk)
        throws Exception {
        long startTime = System.currentTimeMillis();
        String requestLog = messagesToString(messages);
        log.info("【LLM流式请求】消息数:{}, 总字数:{}", messages.size(), requestLog.length());

        StringBuilder builder = new StringBuilder();
        CompletableFuture<Void> done = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean(false);
        resolveChatModel().chatStream(messages, null, new ChatStreamListener() {
            @Override
            public void onStreamResponse(String content) {
                if (!settled.get() && content != null && !content.isEmpty()) {
                    builder.append(content);
                    onChunk.accept(content);
                }
            }

            @Override
            public void onCompletion(ChatResponse chatResponse) {
                if (!settled.get()) {
                    settled.set(true);
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("【LLM流式响应】延迟:{}ms 聚合字数:{}", elapsed, builder.length());
                    done.complete(null);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (!settled.get()) {
                    settled.set(true);
                    log.error("【LLM流式错误】延迟:{}ms, 错误:{}", System.currentTimeMillis() - startTime, throwable.getMessage());
                    done.completeExceptionally(throwable);
                }
            }
        });
        try {
            done.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            settled.set(true);
            log.warn("LLM 流式调用超时（{} 秒），丢弃迟到回调", timeoutSeconds);
            throw e;
        } catch (InterruptedException e) {
            settled.set(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    public String chat(java.util.List<Message> messages) throws Exception {
        return chat(messages, 0.7);
    }

    /** 将消息列表转为字符串 */
    private String messagesToString(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String content = getMessageContent(msg);
            sb.append("{role:").append(msg.getRole())
              .append(", content:").append(truncate(content, 200));
            if (i < messages.size() - 1) {
                sb.append("}, ");
            } else {
                sb.append("}");
            }
        }
        return sb.append("]").toString();
    }

    /** 兼容方式获取 Message 内容 */
    private String getMessageContent(Message msg) {
        try {
            // 尝试 text 字段
            java.lang.reflect.Field field = msg.getClass().getDeclaredField("text");
            field.setAccessible(true);
            Object val = field.get(msg);
            return val != null ? val.toString() : "";
        } catch (Exception e1) {
            try {
                // 尝试 getText 方法
                java.lang.reflect.Method method = msg.getClass().getMethod("getText");
                Object val = method.invoke(msg);
                return val != null ? val.toString() : "";
            } catch (Exception e2) {
                return "[无法获取内容]";
            }
        }
    }

    /** 字符串截断，超过maxLen则返回前maxLen字+省略号 */
    private String truncate(String str, int maxLen) {
        if (str == null) return "null";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...(共" + str.length() + "字)";
    }
}
