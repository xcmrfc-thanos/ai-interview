package com.aiinterview.server.copilot;

import com.aiinterview.server.db.CopilotDao;
import com.aiinterview.server.db.PlanDao;
import com.aiinterview.server.db.ResumeDao;

import java.util.Map;

/**
 * Copilot 会话级上下文：启动时一次加载岗位与简历摘要，回答生成期间不再重复查库。
 */
public final class CopilotSessionContext {

    private final long planId;
    private final String jobDescription;
    private final String resumeSummary;

    CopilotSessionContext(long planId, String jobDescription, String resumeSummary) {
        this.planId = planId;
        this.jobDescription = jobDescription == null ? "" : jobDescription;
        this.resumeSummary = resumeSummary == null ? "" : resumeSummary;
    }

    /**
     * 从数据库加载会话关联的计划与简历摘要。
     */
    public static CopilotSessionContext load(long sessionId, long userId) {
        Map<String, Object> session = CopilotDao.findSession(sessionId, userId);
        if (session == null) {
            return empty();
        }
        long planId = ((Number) session.get("plan_id")).longValue();
        Map<String, Object> plan = PlanDao.findPlanAnyUser(planId);
        Object resumeId = plan == null ? null : plan.get("resume_id");
        Map<String, Object> resume = resumeId == null ? null : ResumeDao.findById(((Number) resumeId).longValue());
        String jd = plan == null ? "" : String.valueOf(plan.get("job_description"));
        String resumeData = resume == null ? "" : String.valueOf(resume.get("parsed_data"));
        return new CopilotSessionContext(planId, jd, resumeData);
    }

    /** 构造 LLM 用户上下文文本。 */
    public String buildLlmContext(String question) {
        return "面试官问题：" + (question == null ? "" : question)
            + "\n岗位要求：" + jobDescription
            + "\n简历摘要：" + resumeSummary;
    }

    public long planId() {
        return planId;
    }

    public String jobDescription() {
        return jobDescription;
    }

    public String resumeSummary() {
        return resumeSummary;
    }

    private static CopilotSessionContext empty() {
        return new CopilotSessionContext(0L, "", "");
    }
}
