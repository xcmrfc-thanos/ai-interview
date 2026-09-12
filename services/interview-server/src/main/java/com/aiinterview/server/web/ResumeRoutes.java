package com.aiinterview.server.web;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.db.ResumeDao;
import com.aiinterview.server.db.UserDao;
import com.aiinterview.server.svc.ResumeAnalyzer;
import com.aiinterview.server.svc.ResumeExtractor;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.common.multipart.Part;
import tech.smartboot.feat.router.Router;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 简历路由：上传/列表/状态（对应 routes/route_resume.py）。 */
public final class ResumeRoutes {

    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(java.util.Arrays.asList(
        ".docx", ".doc", ".txt", ".pdf"));

    private static AppConfig config;

    private ResumeRoutes() {
    }

    public static void init(AppConfig cfg) {
        config = cfg;
    }

    public static void register(Router router) {
        RouteSupport.route(router, "/upload_resume", "POST", (ctx) -> handleUpload(ctx.Request, ctx.Response));
        // 契约路径：POST /api/resumes（上传）与 GET /api/resumes（列表）
        RouteSupport.route(router, "/resumes", "POST", (ctx) -> handleUpload(ctx.Request, ctx.Response));
        RouteSupport.route(router, "/resumes", "GET", (ctx) -> handleList(ctx.Request, ctx.Response, false));
        RouteSupport.route(router, "/get_resumes", "GET", (ctx) -> handleList(ctx.Request, ctx.Response, false));
        RouteSupport.route(router, "/get_resumesbyUser", "GET", (ctx) -> handleList(ctx.Request, ctx.Response, true));
        RouteSupport.route(router, "/resumes/:resumeId/status", "GET", (ctx) -> handleStatus(ctx.Request, ctx.pathParam("resumeId"), ctx.Response));
        RouteSupport.route(router, "/uploads/:filename", "GET", (ctx) -> handleServeFile(ctx.Request, ctx.pathParam("filename"), ctx.Response));
    }

    private static void handleUpload(tech.smartboot.feat.core.server.HttpRequest request,
                                     tech.smartboot.feat.core.server.HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Part filePart = null;
        String storageName = null;
        java.util.Collection<Part> parts = request.getParts();
        for (Part part : parts) {
            if ("file".equals(part.getName())) {
                filePart = part;
            } else if ("storage_name".equals(part.getName())) {
                storageName = readPartText(part);
            }
        }
        if (filePart == null || filePart.getSubmittedFileName() == null) {
            RouteSupport.json(response, 400, "{\"success\":false,\"message\":\"没有文件部分\"}");
            return;
        }
        if (storageName == null || storageName.trim().isEmpty()) {
            RouteSupport.json(response, 400, "{\"success\":false,\"message\":\"没有提供文件名\"}");
            return;
        }
        String originalName = filePart.getSubmittedFileName();
        String ext = extension(originalName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            RouteSupport.json(response, 400, "{\"success\":false,\"message\":\"仅支持 PDF 或 DOCX 文件\"}");
            return;
        }
        String safeName = secureName(storageName.trim()) + ext;
        File target = new File(config.uploadDir(), safeName);
        try (InputStream in = filePart.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!target.isFile() || target.length() == 0) {
            RouteSupport.json(response, 500, "{\"success\":false,\"message\":\"文件保存失败\"}");
            return;
        }
        JSONObject extraction = ResumeExtractor.extract(target);
        Map<String, Object> applicant = UserDao.findApplicantByUserId(session.userId);
        if (applicant == null) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"申请人资料不存在\"}");
            return;
        }
        long applicantId = ((Number) applicant.get("applicant_id")).longValue();
        long resumeId = ResumeDao.create(applicantId, target.getAbsolutePath(), safeName,
            extraction.toJSONString());
        ResumeAnalyzer.start(resumeId);
        JSONObject body = new JSONObject();
        body.put("success", true);
        body.put("message", "文件已上传，本地关键词已提取，AI 分析在后台进行");
        body.put("file_url", target.getAbsolutePath());
        body.put("resume_id", resumeId);
        body.put("resume_data", extraction);
        body.put("status", extraction.getString("status"));
        body.put("analysis_status", "pending");
        RouteSupport.json(response, 201, body);
    }

    private static void handleList(tech.smartboot.feat.core.server.HttpRequest request,
                                   tech.smartboot.feat.core.server.HttpResponse response,
                                   boolean withReport) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> applicant = UserDao.findApplicantByUserId(session.userId);
        if (applicant == null) {
            RouteSupport.json(response, 200, "{\"success\":true,\"resumes\":[]}");
            return;
        }
        long applicantId = ((Number) applicant.get("applicant_id")).longValue();
        List<Map<String, Object>> resumes = ResumeDao.listByApplicant(applicantId);
        JSONArray list = new JSONArray();
        for (Map<String, Object> resume : resumes) {
            JSONObject item = new JSONObject();
            item.put("id", resume.get("resume_id"));
            item.put("resume_id", resume.get("resume_id"));
            item.put("filename", resume.get("filename"));
            item.put("upload_date", String.valueOf(resume.get("upload_date")));
            item.put("file_url", resume.get("file_url"));
            JSONObject payload = RouteSupport.parsePayload(resume.get("parsed_data"));
            item.put("status", payload.getString("status"));
            item.put("analysis_status", payload.getString("analysis_status"));
            item.put("analysis_error", payload.getString("analysis_error"));
            item.put("keywords", payload.getJSONArray("keywords"));
            if (withReport) {
                item.put("report", ResumeDao.hasReport(((Number) resume.get("resume_id")).longValue()));
            }
            list.add(item);
        }
        JSONObject body = new JSONObject();
        body.put("success", true);
        body.put("resumes", list);
        RouteSupport.json(response, 200, body);
    }

    private static void handleStatus(tech.smartboot.feat.core.server.HttpRequest request, String resumeIdParam,
                                     tech.smartboot.feat.core.server.HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        long resumeId = parseLong(resumeIdParam, -1);
        Map<String, Object> resume = ResumeDao.findForUser(resumeId, session.userId);
        if (resume == null) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"简历不存在\"}");
            return;
        }
        JSONObject payload = RouteSupport.parsePayload(resume.get("parsed_data"));
        JSONObject body = new JSONObject();
        body.put("success", true);
        body.put("resume_id", resumeId);
        body.put("status", payload.getString("status"));
        body.put("analysis_status", payload.getString("analysis_status"));
        body.put("analysis_error", payload.getString("analysis_error"));
        body.put("keywords", payload.getJSONArray("keywords"));
        RouteSupport.json(response, 200, body);
    }

    private static void handleServeFile(tech.smartboot.feat.core.server.HttpRequest request, String filename,
                                        tech.smartboot.feat.core.server.HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"简历不存在\"}");
            return;
        }
        // feat 的 pathParam 返回未解码的 URI 分段，中文文件名需 URL 解码
        try {
            filename = java.net.URLDecoder.decode(filename, "UTF-8");
        } catch (Exception e) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"简历不存在\"}");
            return;
        }
        // 归属校验：只允许访问自己简历关联的文件（含转换产物 .md/.txt/.pdf）
        Map<String, Object> applicant = UserDao.findApplicantByUserId(session.userId);
        if (applicant == null) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"简历不存在\"}");
            return;
        }
        long applicantId = ((Number) applicant.get("applicant_id")).longValue();
        Set<String> allowed = new HashSet<>();
        for (Map<String, Object> resume : ResumeDao.listByApplicant(applicantId)) {
            String fileUrl = String.valueOf(resume.get("file_url") == null ? "" : resume.get("file_url"));
            java.io.File base = new java.io.File(fileUrl);
            if (!base.getName().isEmpty()) {
                allowed.add(base.getName());
                String name = base.getName();
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    String stem = name.substring(0, dot);
                    for (String suffix : new String[] {".md", ".txt", ".pdf"}) {
                        allowed.add(stem + suffix);
                    }
                }
            }
        }
        if (!allowed.contains(filename)) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"简历不存在\"}");
            return;
        }
        File file = new File(config.uploadDir(), filename);
        if (!file.isFile()) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"简历不存在\"}");
            return;
        }
        response.setHeader("Content-Type", contentType(filename));
        response.write(Files.readAllBytes(file.toPath()));
    }

    // ---- 工具 ----

    private static String readPartText(Part part) throws Exception {
        try (InputStream in = part.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String secureName(String name) {
        return name.replaceAll("[^\\w\\u4e00-\\u9fa5.-]", "_");
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String contentType(String filename) {
        String ext = extension(filename).toLowerCase();
        if (".pdf".equals(ext)) {
            return "application/pdf";
        }
        if (".docx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (".md".equals(ext)) {
            return "text/markdown; charset=utf-8";
        }
        return "text/plain; charset=utf-8";
    }
}
