package com.aiinterview.server.svc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.ai.chat.entity.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

/** 准备包生成（对应 services/preparation_service.py 的核心流程）。 */
public final class PreparationService {

    private static final String SYSTEM_PROMPT =
        "你是资深面试准备专家。基于岗位 JD、简历事实和技术标签，输出结构化准备包 JSON。"
            + "只输出 JSON，不要任何其他文字。";

    private static final String[] PACK_FIELDS = {
        "intro_30", "intro_60", "intro_90", "highlights", "project_followups", "risk_points",
        "frequent_questions", "star_stories", "review_topics", "source_evidence",
    };

    private final Llm llm;

    public PreparationService(Llm llm) {
        this.llm = llm;
    }

    /** 生成准备包内容（不含版本/指纹等元数据）。 */
    public JSONObject generate(Map<String, Object> plan, JSONObject resumePayload) {
        String jobDescription = str(plan.get("job_description"));
        String extraRequirements = str(plan.get("extra_requirements"));
        String resumeFacts = resumePayload == null ? "{}" : String.valueOf(resumePayload.getJSONObject("facts"));
        String techTags = String.valueOf(plan.get("tech_tags") == null ? "[]" : plan.get("tech_tags"));

        String userPrompt = "<job_description>" + jobDescription + "</job_description>\n"
            + "<extra_requirements>" + extraRequirements + "</extra_requirements>\n"
            + "<resume_facts>" + resumeFacts + "</resume_facts>\n"
            + "<tech_tags>" + techTags + "</tech_tags>\n"
            + "请输出 JSON：{\"intro_30\":\"30秒自我介绍\",\"intro_60\":\"60秒自我介绍\",\"intro_90\":\"90秒自我介绍\","
            + "\"highlights\":[\"亮点1\"],\"project_followups\":[\"项目追问\"],\"risk_points\":[\"风险点\"],"
            + "\"frequent_questions\":[\"高频问题\"],\"star_stories\":[\"STAR故事\"],\"review_topics\":[\"复习主题\"],"
            + "\"source_evidence\":[\"事实依据\"],\"unverified_claims\":[\"无法核实的内容\"]}";
        try {
            String completion = llm.chat(Arrays.asList(Message.ofSystem(SYSTEM_PROMPT), Message.ofUser(userPrompt)), 0.3);
            JSONObject payload = extractJson(completion);
            JSONObject result = new JSONObject();
            for (String field : PACK_FIELDS) {
                result.put(field, payload.get(field));
            }
            boolean needsReview = payload.getJSONArray("unverified_claims") != null
                && !payload.getJSONArray("unverified_claims").isEmpty();
            result.put("needs_review", needsReview);
            result.put("source_fingerprint", sourceFingerprint(jobDescription, resumeFacts, extraRequirements));
            return result;
        } catch (Exception e) {
            throw new RuntimeException("准备包生成失败", e);
        }
    }

    public static String sourceFingerprint(String jobDescription, String resumeFacts, String extraRequirements) {
        try {
            String content = "{\"jd\":" + JSON.toJSONString(jobDescription) + ",\"resume\":"
                + JSON.toJSONString(resumeFacts) + ",\"extra\":" + JSON.toJSONString(extraRequirements) + "}";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "fingerprint-error";
        }
    }

    public static JSONObject extractJson(String text) {
        if (text == null) {
            throw new IllegalArgumentException("LLM 返回为空");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return JSONObject.parseObject(text.substring(start, end + 1));
            } catch (Exception ignored) {
                // fall through
            }
        }
        throw new IllegalArgumentException("LLM 输出不是合法 JSON");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
