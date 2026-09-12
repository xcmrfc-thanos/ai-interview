package com.aiinterview.server.svc;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.db.ResumeDao;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.ai.chat.entity.Message;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 简历后台异步 AI 分析（对应 Python _run_resume_analysis）。 */
public final class ResumeAnalyzer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResumeAnalyzer.class);

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "resume-analyzer");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Llm llm;

    private ResumeAnalyzer() {
    }

    public static void init(AppConfig config) {
        llm = new Llm();
    }

    public static void start(long resumeId) {
        EXECUTOR.submit(() -> analyze(resumeId));
    }

    private static void analyze(long resumeId) {
        JSONObject payload;
        try {
            Map<String, Object> resume = ResumeDao.findById(resumeId);
            if (resume == null) {
                return;
            }
            payload = parsePayload(resume.get("parsed_data"));
            payload.put("analysis_status", "analyzing");
            payload.put("analysis_error", "");
            ResumeDao.updateParsedData(resumeId, payload.toJSONString());
            String text = payload.getString("extracted_text");
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalStateException("本地未提取到可分析的文本");
            }
            String completion = llm.chat(Arrays.asList(
                Message.ofSystem("请将简历整理为准确的结构化 JSON。"),
                Message.ofUser(text)
            ), 0.3);
            payload.put("ai_analysis", extractJson(completion));
            payload.put("status", "ready");
            payload.put("analysis_status", "completed");
        } catch (Exception error) {
            log.error("AI 简历分析失败 resumeId={}", resumeId, error);
            payload = new JSONObject();
            payload.put("status", "uploaded");
            payload.put("analysis_status", "failed");
            payload.put("analysis_error", "AI 简历分析失败，请稍后重试");
        }
        try {
            ResumeDao.updateParsedData(resumeId, payload.toJSONString());
        } catch (Exception ignored) {
            // 简历可能已被删除
        }
    }

    private static JSONObject parsePayload(Object value) {
        if (value instanceof String) {
            try {
                return JSONObject.parseObject((String) value);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
        if (value instanceof Map) {
            return new JSONObject((Map<String, Object>) value);
        }
        return new JSONObject();
    }

    /** 从 LLM 输出提取 JSON 对象（容错 Markdown 代码块/前后说明）。 */
    private static Object extractJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return JSONObject.parseObject(trimmed.substring(start, end + 1));
            } catch (Exception ignored) {
                // fall through
            }
        }
        return trimmed;
    }
}
