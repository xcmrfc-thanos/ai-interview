package com.aiinterview.server.svc;

import com.aiinterview.server.copilot.GenerationRegistry;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.ai.chat.entity.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Copilot 流式回答服务：解析 NDJSON 并实时推送 WS 事件（对应 Python answer_service.py）。
 */
public final class CopilotAnswerService {

    static final String SYSTEM_PROMPT =
        "你是个人求职者的实时面试回答助手。\n"
            + "只可使用给定简历事实、岗位信息、最近对话和知识片段；不得虚构项目、职责、数字或技术经历。\n"
            + "逐行输出 NDJSON，不要使用 Markdown 代码块：\n"
            + "1. {\"type\":\"answer_points\",\"answer_points\":[\"3 至 5 条简短要点\"]}\n"
            + "2. 多行 {\"type\":\"answer_delta\",\"text\":\"参考回答片段\"}\n"
            + "3. {\"type\":\"completed\",\"follow_up\":\"追问准备\",\"knowledge_item_ids\":[1,2]}\n"
            + "若简历没有实践，明确使用“理论理解”或“如果由我设计”的口径。";

    private static final int DELTA_PIECE_SIZE = 4;

    /** WS 事件发射回调。 */
    public interface Emitter {
        void emit(String event, JSONObject payload);
    }

    private CopilotAnswerService() {
    }

    /**
     * 流式生成 Copilot 回答并推送事件。
     *
     * @return 聚合后的结果 JSON（要点、参考回答、追问）
     */
    public static JSONObject generate(
        Llm llm,
        long sessionId,
        long turnId,
        long generationId,
        GenerationRegistry registry,
        String contextText,
        Emitter emitter
    ) throws Exception {
        JSONObject result = emptyResult();
        StringBuilder raw = new StringBuilder();
        StringBuilder buffer = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean structuredSeen = new java.util.concurrent.atomic.AtomicBoolean(false);

        List<Message> messages = Arrays.asList(
            Message.ofSystem(SYSTEM_PROMPT),
            Message.ofUser(contextText == null ? "" : contextText)
        );

        llm.streamChat(messages, 0.3, chunk -> {
            if (!registry.isActive(generationId)) {
                return;
            }
            raw.append(chunk);
            buffer.append(chunk);
            int newline;
            while ((newline = buffer.indexOf("\n")) >= 0) {
                String line = buffer.substring(0, newline);
                buffer.delete(0, newline + 1);
                if (applyLine(sessionId, turnId, generationId, registry, line, result, emitter)) {
                    structuredSeen.set(true);
                }
            }
        });

        if (!registry.isActive(generationId)) {
            result.put("cancelled", true);
            return result;
        }
        if (buffer.toString().trim().length() > 0) {
            if (applyLine(sessionId, turnId, generationId, registry, buffer.toString(), result, emitter)) {
                structuredSeen.set(true);
            }
        }
        if (!structuredSeen.get()) {
            result.put("reference_answer", raw.toString().trim());
            result.put("error_code", "STRUCTURED_OUTPUT_FALLBACK");
            if (result.getString("reference_answer").length() > 0) {
                emitDeltaPieces(sessionId, turnId, generationId, registry, result.getString("reference_answer"), emitter);
            }
        }
        JSONObject completed = new JSONObject();
        completed.put("session_id", sessionId);
        completed.put("turn_id", turnId);
        completed.put("generation_id", generationId);
        completed.put("answer_points", result.getJSONArray("answer_points"));
        completed.put("reference_answer", result.getString("reference_answer"));
        completed.put("follow_up", result.getString("follow_up"));
        completed.put("error_code", result.getString("error_code"));
        emitter.emit("answer_completed", completed);
        return result;
    }

    private static boolean applyLine(
        long sessionId,
        long turnId,
        long generationId,
        GenerationRegistry registry,
        String line,
        JSONObject result,
        Emitter emitter
    ) {
        JSONObject event = AnswerStreamParser.parseLine(line);
        if (event == null) {
            return false;
        }
        if (!registry.isActive(generationId)) {
            return true;
        }
        String type = event.getString("type");
        if ("answer_points".equals(type)) {
            JSONArray points = event.getJSONArray("answer_points");
            List<String> normalized = new ArrayList<>();
            if (points != null) {
                for (int i = 0; i < points.size(); i++) {
                    String point = normalizePoint(points.getString(i));
                    if (!point.isEmpty()) {
                        normalized.add(point);
                    }
                }
            }
            result.put("answer_points", normalized);
            JSONObject payload = new JSONObject();
            payload.put("session_id", sessionId);
            payload.put("turn_id", turnId);
            payload.put("generation_id", generationId);
            payload.put("answer_points", normalized);
            emitter.emit("answer_started", payload);
        } else if ("answer_delta".equals(type)) {
            String text = event.getString("text");
            if (text == null) {
                text = "";
            }
            String reference = result.getString("reference_answer");
            if (reference == null) {
                reference = "";
            }
            result.put("reference_answer", reference + text);
            emitDeltaPieces(sessionId, turnId, generationId, registry, text, emitter);
        } else if ("completed".equals(type)) {
            result.put("follow_up", event.getString("follow_up") == null ? "" : event.getString("follow_up"));
            return true;
        }
        return true;
    }

    /** 将 delta 文本分片推送；逐字平滑展示由前端 use-smooth-text 完成，服务端不做人为延迟。 */
    private static void emitDeltaPieces(
        long sessionId,
        long turnId,
        long generationId,
        GenerationRegistry registry,
        String text,
        Emitter emitter
    ) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int start = 0; start < text.length(); start += DELTA_PIECE_SIZE) {
            if (!registry.isActive(generationId)) {
                return;
            }
            String piece = text.substring(start, Math.min(start + DELTA_PIECE_SIZE, text.length()));
            JSONObject payload = new JSONObject();
            payload.put("session_id", sessionId);
            payload.put("turn_id", turnId);
            payload.put("generation_id", generationId);
            payload.put("text", piece);
            emitter.emit("answer_delta", payload);
        }
    }

    private static JSONObject emptyResult() {
        JSONObject result = new JSONObject();
        result.put("answer_points", new JSONArray());
        result.put("reference_answer", "");
        result.put("follow_up", "");
        result.put("error_code", null);
        result.put("cancelled", false);
        return result;
    }

    private static String normalizePoint(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^\\s*\\d+\\s*[.、)]\\s*", "").trim();
    }
}
