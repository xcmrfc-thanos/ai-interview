package com.aiinterview.server;

import com.aiinterview.server.svc.Llm;
import tech.smartboot.feat.ai.chat.ChatModel;
import tech.smartboot.feat.ai.chat.entity.Message;

import java.util.Arrays;

/**
 * 基于 OpenAI 兼容接口的流式回答服务（替代 Python answer_service 核心）。
 * 自优化计划 T2.4 起为 {@link Llm} 的薄封装：SYSTEM_PROMPT 拼进消息列表后走 llm.chat，
 * 聚合/超时/迟到回调丢弃等逻辑统一在 Llm 维护（此前与 Llm 重复约 40 行）。
 */
public final class AnswerService {

    private static final String SYSTEM_PROMPT =
        "你是 AI 面试回答助手。基于面试官问题与给定上下文，输出 3-5 条回答要点和一段简洁参考回答。";

    private final Llm llm;

    public AnswerService(AppConfig config) {
        this(new Llm());
    }

    /** 包可见：测试注入 mock Llm（内部持有 mock ChatModel）。 */
    AnswerService(Llm llm) {
        this.llm = llm;
    }

    /** 非流式入口：聚合流式片段后一次性返回；超时抛 TimeoutException 并丢弃迟到回调。 */
    public String answer(String question, String context) throws Exception {
        return llm.chat(Arrays.asList(
            Message.ofSystem(SYSTEM_PROMPT),
            Message.ofSystem("上下文（简历/JD 摘要）：\n" + context),
            Message.ofUser(question)
        ), 0.7);
    }
}
