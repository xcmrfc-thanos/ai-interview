package com.aiinterview.server.svc;

import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.ai.chat.entity.Message;

import java.util.Arrays;

/** Copilot 回答生成：final 转写 → 回答要点/参考回答/追问（对应 services/answer_service.py 核心）。 */
public final class LlmAnswer {

    private static final String SYSTEM_PROMPT =
        "你是 AI 面试回答助手。基于面试官问题与给定上下文，输出结构化回答建议 JSON。";

    private LlmAnswer() {
    }

    public static JSONObject generate(Llm llm, String question, String context) throws Exception {
        String userPrompt = "<question>" + question + "</question>\n"
            + "<context>" + context + "</context>\n"
            + "输出 JSON：{\"answer_points\":[\"3-5条回答要点\"],\"reference_answer\":\"简洁参考回答段落\","
            + "\"follow_up\":\"可能的追问或留空\"}";
        String completion = llm.chat(Arrays.asList(
            Message.ofSystem(SYSTEM_PROMPT),
            Message.ofSystem("上下文（简历/JD 摘要）：\n" + (context == null ? "" : context)),
            Message.ofUser(question)
        ), 0.3);
        return PreparationService.extractJson(completion);
    }
}
