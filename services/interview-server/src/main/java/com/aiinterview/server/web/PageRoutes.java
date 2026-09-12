package com.aiinterview.server.web;

import tech.smartboot.feat.router.Router;

/** 页面路由：已迁移页面统一回落 web/dist 的 SPA 入口（客户端路由接管）。 */
public final class PageRoutes {

    private PageRoutes() {
    }

    public static void register(Router router) {
        // 公开页
        router.route("/", (ctx) -> SpaSupport.serveIndex(ctx.Response));
        router.route("/loginView", (ctx) -> SpaSupport.serveIndex(ctx.Response));
        router.route("/login", (ctx) -> SpaSupport.serveIndex(ctx.Response));
        router.route("/registerView", (ctx) -> SpaSupport.serveIndex(ctx.Response));
        router.route("/register", (ctx) -> SpaSupport.serveIndex(ctx.Response));

        // 应用内页面（鉴权：未登录 302 到 /login）
        page(router, "/applicant/workspace");
        page(router, "/applicant/interview-plans");
        page(router, "/applicant/interview-plans/:planId");
        page(router, "/applicant/copilot");
        page(router, "/applicant/mock-interview");
        page(router, "/applicant/interview-workspace");
        page(router, "/applicant/resumes");
        page(router, "/applicant/resume_manage");
        page(router, "/applicant/resume-optimize");
        page(router, "/applicant/knowledge");
        page(router, "/applicant/reviews");
        page(router, "/applicant/reviews/:reviewId");
        page(router, "/applicant/profile");

        // 旧入口收敛（SPA 下 URL 原样交给客户端路由；无 SPA 产物时 302 兜底）
        redirect(router, "/applicant/dashboard", "/applicant/workspace");
        redirect(router, "/applicant/sim_interview", "/applicant/mock-interview");
        redirect(router, "/applicant/interview", "/applicant/interview-plans");
        redirect(router, "/applicant/personal_center", "/applicant/profile");
        redirect(router, "/applicant/resume", "/applicant/resumes");
        redirect(router, "/applicant/reports", "/applicant/reviews");
        redirect(router, "/applicant/analyze_resume", "/applicant/resumes");
        redirect(router, "/applicant/resume_report", "/applicant/resumes");
        redirect(router, "/applicant/upload_resume", "/applicant/resumes");
        redirect(router, "/applicant/interview_manage", "/applicant/interview-plans");
        redirect(router, "/applicant/avatar", "/applicant/mock-interview");
        redirect(router, "/applicant/interviewindex", "/applicant/mock-interview");
        redirect(router, "/applicant/TestConfig", "/applicant/mock-interview");
    }

    /** 鉴权页面：SPA 产物存在时回落 index.html。 */
    private static void page(Router router, String path) {
        router.route(path, (ctx) -> {
            if (!AuthRoutes.requireApplicant(ctx.Request, ctx.Response)) {
                return;
            }
            SpaSupport.serveIndex(ctx.Response);
        });
    }

    private static void redirect(Router router, String from, String to) {
        router.route(from, (ctx) -> {
            if (SpaSupport.available()) {
                SpaSupport.serveIndex(ctx.Response);
                return;
            }
            AuthRoutes.redirect(ctx.Response, to);
        });
    }
}
