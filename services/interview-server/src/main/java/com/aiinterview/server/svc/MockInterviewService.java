package com.aiinterview.server.svc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.ai.chat.entity.Message;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 模拟面试服务：题目大纲生成 + 逐题四维评分（对应 services/mock_interview_service.py）。 */
public final class MockInterviewService {

    private static final List<String> SCORE_FIELDS = Arrays.asList(
        "technical_ability", "learning_ability", "team_collaboration", "problem_solving", "communication_expression");

    private final Llm llm;

    public MockInterviewService(Llm llm) {
        this.llm = llm;
    }

    /** 生成题目大纲（8 题，覆盖自我介绍/简历项目/JD 技术项/场景题/行为题）。 */
    public JSONArray generateOutline(Map<String, Object> plan, JSONObject resumePayload) {
        String jobDescription = str(plan.get("job_description"));
        String extraRequirements = str(plan.get("extra_requirements"));
        String level = str(plan.get("level"));
        String techTags = String.valueOf(plan.get("tech_tags") == null ? "[]" : plan.get("tech_tags"));
        String resumeFacts = resumePayload == null ? "{}" : String.valueOf(resumePayload.getJSONObject("facts"));
        String userPrompt = "<job_description>" + jobDescription + "</job_description>\n"
            + "<extra_requirements>" + extraRequirements + "</extra_requirements>\n"
            + "<level>" + level + "</level>\n"
            + "<tech_tags>" + techTags + "</tech_tags>\n"
            + "<resume_facts>" + resumeFacts + "</resume_facts>\n"
            + "请生成 8 道模拟面试题，覆盖自我介绍、简历项目、JD 技术项、场景题和行为题。"
            + "输出 JSON：{\"questions\":[{\"category\":\"分类\",\"question\":\"问题\",\"reference_points\":[\"要点\"]}]}";
        try {
            String completion = llm.chat(Arrays.asList(
                Message.ofSystem("你是资深面试官，输出结构化题目大纲 JSON。"),
                Message.ofUser(userPrompt)), 0.4);
            JSONObject payload = PreparationService.extractJson(completion);
            JSONArray questions = payload.getJSONArray("questions");
            if (questions == null || questions.isEmpty()) {
                throw new RuntimeException("题目大纲生成为空");
            }
            return questions;
        } catch (Exception e) {
            throw new RuntimeException("题目生成失败", e);
        }
    }

    /** 逐题评分：reference_points/scores(0-100 四维)/strengths/improvements。 */
    public JSONObject evaluateAnswer(String question, String answer, JSONObject resumePayload,
                                     String jobDescription) {
        String resumeFacts = resumePayload == null ? "{}" : String.valueOf(resumePayload.getJSONObject("facts"));
        String userPrompt = "<question>" + question + "</question>\n"
            + "<answer>" + answer + "</answer>\n"
            + "<resume_facts>" + resumeFacts + "</resume_facts>\n"
            + "<job_description>" + jobDescription + "</job_description>\n"
            + "请评估该回答并输出 JSON：{\"reference_points\":[\"参考答案要点\"],"
            + "\"scores\":{\"technical_ability\":0-100,\"learning_ability\":0-100,\"team_collaboration\":0-100,"
            + "\"problem_solving\":0-100,\"communication_expression\":0-100},"
            + "\"strengths\":[\"优点\"],\"improvements\":[\"改进建议\"]}";
        try {
            String completion = llm.chat(Arrays.asList(
                Message.ofSystem("你是严格但建设性的面试评分官，输出结构化评分 JSON。"),
                Message.ofUser(userPrompt)), 0.2);
            JSONObject payload = PreparationService.extractJson(completion);
            JSONObject scores = new JSONObject();
            JSONObject raw = payload.getJSONObject("scores");
            if (raw != null) {
                for (String field : SCORE_FIELDS) {
                    Object value = raw.get(field);
                    Number number = value instanceof Number ? (Number) value : null;
                    if (number == null) {
                        scores.put(field, 0);
                    } else {
                        scores.put(field, Math.max(0, Math.min(100, number.doubleValue())));
                    }
                }
            }
            JSONObject result = new JSONObject();
            result.put("reference_points", payload.getJSONArray("reference_points"));
            result.put("scores", scores);
            result.put("strengths", payload.getJSONArray("strengths"));
            result.put("improvements", payload.getJSONArray("improvements"));
            result.put("retryable", false);
            return result;
        } catch (Exception e) {
            JSONObject result = new JSONObject();
            result.put("retryable", true);
            result.put("message", "评分解析失败，可重试");
            return result;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
