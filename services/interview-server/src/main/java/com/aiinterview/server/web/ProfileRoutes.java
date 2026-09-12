package com.aiinterview.server.web;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.db.PasswordHash;
import com.aiinterview.server.db.UserDao;
import com.aiinterview.server.db.VoiceProfileDao;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.common.multipart.Part;
import tech.smartboot.feat.router.Router;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 个人中心路由：/api/users 资料读写 + /api/users/voice-profile 本人语音上传/播放/删除。 */
public final class ProfileRoutes {

    private static final java.util.Set<String> AUDIO_EXTENSIONS = new java.util.HashSet<>(
        java.util.Arrays.asList(".m4a", ".mp3", ".mp4", ".ogg", ".wav", ".webm"));
    private static final long MAX_VOICE_BYTES = 10L * 1024 * 1024;

    private static AppConfig config;

    private ProfileRoutes() {
    }

    public static void init(AppConfig cfg) {
        config = cfg;
    }

    public static void register(Router router) {
        router.route("/api/users", "GET", (ctx) -> handleGetUser(ctx.Request, ctx.Response));
        router.route("/api/users/update", "POST", (ctx) -> handleUpdateUser(ctx.Request, ctx.Response));
        router.route("/api/users/voice-profile", "GET", (ctx) -> handleGetVoice(ctx.Request, ctx.Response));
        router.route("/api/users/voice-profile/audio", "GET", (ctx) -> handleVoiceAudio(ctx.Request, ctx.Response));
        router.route("/api/users/voice-profile", "POST", (ctx) -> handleSaveVoice(ctx.Request, ctx.Response));
        router.route("/api/users/voice-profile", "DELETE", (ctx) -> handleDeleteVoice(ctx.Request, ctx.Response));
    }

    // ---- /api/users ----

    private static void handleGetUser(tech.smartboot.feat.core.server.HttpRequest request,
                                      tech.smartboot.feat.core.server.HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> user = UserDao.findById(session.userId);
        Map<String, Object> applicant = UserDao.findApplicantByUserId(session.userId);
        if (user == null || applicant == null) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"用户不存在\"}");
            return;
        }
        JSONObject body = new JSONObject();
        body.put("success", true);
        JSONObject u = new JSONObject();
        u.put("full_name", str(applicant.get("full_name")));
        u.put("email", str(user.get("email")));
        u.put("phone", str(applicant.get("phone")));
        u.put("expected_position", str(applicant.get("expected_position")));
        u.put("expected_salary", applicant.get("expected_salary"));
        u.put("work_years", applicant.get("work_years"));
        body.put("user", u);
        RouteSupport.json(response, 200, body);
    }

    private static void handleUpdateUser(tech.smartboot.feat.core.server.HttpRequest request,
                                         tech.smartboot.feat.core.server.HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        JSONObject data = RouteSupport.parseBody(request);
        if (data == null) {
            RouteSupport.json(response, 400, "{\"success\":false,\"message\":\"无效请求\"}");
            return;
        }
        String fullName = str(data.get("full_name"));
        String email = str(data.get("email"));
        String phone = str(data.get("phone"));
        String position = str(data.get("expected_position"));
        int salary = data.getIntValue("expected_salary", 0);
        int workYears = data.getIntValue("work_years", 0);
        String password = data.getString("password");
        if (fullName.isEmpty() || email.isEmpty()) {
            RouteSupport.json(response, 400, "{\"success\":false,\"message\":\"姓名和邮箱不能为空\"}");
            return;
        }
        UserDao.updateEmail(session.userId, email);
        UserDao.updateApplicantProfile(session.userId, fullName, phone, position, salary, workYears);
        if (password != null && !password.isEmpty()) {
            UserDao.updatePassword(session.userId, PasswordHash.hash(password));
        }
        JSONObject body = new JSONObject();
        body.put("success", true);
        JSONObject u = new JSONObject();
        u.put("full_name", fullName);
        u.put("email", email);
        u.put("phone", phone);
        u.put("expected_position", position);
        u.put("expected_salary", salary);
        u.put("work_years", workYears);
        body.put("user", u);
        RouteSupport.json(response, 200, body);
    }

    // ---- voice profile ----

    private static void handleGetVoice(tech.smartboot.feat.core.server.HttpRequest request,
                                       tech.smartboot.feat.core.server.HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        RouteSupport.json(response, 200, "{\"success\":true,\"voice_profile\":" + payload(session.userId).toJSONString() + "}");
    }

    private static void handleVoiceAudio(tech.smartboot.feat.core.server.HttpRequest request,
                                         tech.smartboot.feat.core.server.HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> profile = VoiceProfileDao.findByUserId(session.userId);
        if (profile == null) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"尚未录入语音\"}");
            return;
        }
        File file = new File(voiceDir(), str(profile.get("storage_name")));
        if (!file.isFile()) {
            RouteSupport.json(response, 404, "{\"success\":false,\"message\":\"语音文件不存在\"}");
            return;
        }
        response.setContentType(str(profile.get("mime_type")));
        response.write(Files.readAllBytes(file.toPath()));
    }

    private static void handleSaveVoice(tech.smartboot.feat.core.server.HttpRequest request,
                                        tech.smartboot.feat.core.server.HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Part audioPart = null;
        Collection<Part> parts = request.getParts();
        if (parts != null) {
            for (Part part : parts) {
                if ("audio".equals(part.getName())) {
                    audioPart = part;
                }
            }
        }
        if (audioPart == null || audioPart.getSubmittedFileName() == null
            || audioPart.getSubmittedFileName().isEmpty()) {
            RouteSupport.json(response, 400, "{\"success\":false,\"message\":\"请选择或录制一段语音\"}");
            return;
        }
        String originalName = new File(audioPart.getSubmittedFileName()).getName().replace("\u0000", "");
        String suffix = extension(originalName).toLowerCase();
        if (!AUDIO_EXTENSIONS.contains(suffix)) {
            RouteSupport.json(response, 400, "{\"success\":false,\"message\":\"仅支持 wav、webm、ogg、mp3 或 m4a 音频\"}");
            return;
        }
        String storageName = "user_" + session.userId + "_" + UUID.randomUUID().toString().replace("-", "") + suffix;
        File target = new File(voiceDir(), storageName);
        try (InputStream in = audioPart.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!target.isFile() || target.length() == 0 || target.length() > MAX_VOICE_BYTES) {
            target.delete();
            RouteSupport.json(response, 400, "{\"success\":false,\"message\":\"语音文件需大于 0 且不超过 10MB\"}");
            return;
        }
        // 覆盖旧语音：先删库记录再写新记录，旧物理文件清理
        Map<String, Object> old = VoiceProfileDao.findByUserId(session.userId);
        if (old != null) {
            File oldFile = new File(voiceDir(), str(old.get("storage_name")));
            if (oldFile.isFile() && !oldFile.equals(target)) {
                oldFile.delete();
            }
        }
        VoiceProfileDao.upsert(session.userId, storageName,
            originalName.isEmpty() ? "voice-profile" + suffix : originalName,
            audioPart.getContentType() == null ? "application/octet-stream" : audioPart.getContentType());
        RouteSupport.json(response, 201, "{\"success\":true,\"voice_profile\":" + payload(session.userId).toJSONString() + "}");
    }

    private static void handleDeleteVoice(tech.smartboot.feat.core.server.HttpRequest request,
                                          tech.smartboot.feat.core.server.HttpResponse response) throws Throwable {
        SessionStore.Session session = RouteSupport.require(request, response);
        if (session == null) {
            return;
        }
        Map<String, Object> profile = VoiceProfileDao.findByUserId(session.userId);
        if (profile != null) {
            File file = new File(voiceDir(), str(profile.get("storage_name")));
            VoiceProfileDao.delete(session.userId);
            if (file.isFile()) {
                file.delete();
            }
        }
        RouteSupport.json(response, 200, "{\"success\":true,\"voice_profile\":" + payload(session.userId).toJSONString() + "}");
    }

    // ---- 工具 ----

    private static JSONObject payload(long userId) {
        Map<String, Object> profile = VoiceProfileDao.findByUserId(userId);
        JSONObject out = new JSONObject();
        if (profile == null) {
            out.put("available", false);
            out.put("audio_url", null);
            out.put("original_filename", null);
            out.put("updated_at", null);
        } else {
            out.put("available", true);
            out.put("audio_url", "/api/users/voice-profile/audio");
            out.put("original_filename", str(profile.get("original_filename")));
            out.put("updated_at", String.valueOf(profile.get("updated_at")));
        }
        return out;
    }

    private static File voiceDir() {
        File dir = new File(config.uploadDir(), "voice_profiles");
        dir.mkdirs();
        return dir;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
