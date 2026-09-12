package com.aiinterview.server.web;

import com.aiinterview.server.db.PasswordHash;
import com.aiinterview.server.db.UserDao;
import tech.smartboot.feat.core.common.Cookie;
import tech.smartboot.feat.core.common.HttpStatus;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;

import java.util.Map;

/** 认证路由：POST /api/login、POST /api/register、GET /logout、GET /check_role。 */
public final class AuthRoutes {

    public static final String SESSION_COOKIE = "ai_session";

    private static volatile boolean cookieSecure = false;

    private AuthRoutes() {
    }

    /** 由启动入口注入：COOKIE_SECURE=true 时登录 cookie 带 Secure 属性。 */
    public static void configure(boolean secure) {
        cookieSecure = secure;
    }

    public static void register(tech.smartboot.feat.router.Router router) {
        // 登录
        router.route("/api/login", "POST", (ctx) -> handleLogin(ctx.Request, ctx.Response));
        // 注册
        router.route("/api/register", "POST", (ctx) -> handleRegister(ctx.Request, ctx.Response));
        // 登出（JSON 契约路径 + 旧页面路径）
        router.route("/api/logout", "POST", (ctx) -> handleLogout(ctx.Request, ctx.Response));
        router.route("/logout", (ctx) -> handleLogout(ctx.Request, ctx.Response));
        // 角色检查（JSON 契约路径 + 旧路径）
        router.route("/api/check_role", (ctx) -> handleCheckRole(ctx.Request, ctx.Response));
        router.route("/check_role", (ctx) -> handleCheckRole(ctx.Request, ctx.Response));
    }

    /** JSON 契约请求（Content-Type: application/json）返回 JSON；否则保持旧页面重定向行为。 */
    private static boolean isJsonRequest(HttpRequest request) {
        String contentType = request.getHeader("Content-Type");
        return contentType != null && contentType.toLowerCase().contains("application/json");
    }

    private static com.alibaba.fastjson2.JSONObject bodyJson(HttpRequest request) {
        try {
            String body = cn.hutool.core.io.IoUtil.readUtf8(request.getInputStream());
            if (body == null || body.trim().isEmpty()) {
                return new com.alibaba.fastjson2.JSONObject();
            }
            return com.alibaba.fastjson2.JSONObject.parseObject(body);
        } catch (Exception e) {
            return new com.alibaba.fastjson2.JSONObject();
        }
    }

    private static String param(HttpRequest request, com.alibaba.fastjson2.JSONObject json, String key) {
        if (json != null && json.containsKey(key)) {
            return nvl(json.getString(key));
        }
        return nvl(request.getParameter(key));
    }

    private static void handleLogin(HttpRequest request, HttpResponse response) throws Throwable {
        boolean json = isJsonRequest(request);
        com.alibaba.fastjson2.JSONObject body = json ? bodyJson(request) : null;
        String email = param(request, body, "email");
        String password = param(request, body, "password");
        Map<String, Object> user = email.isEmpty() ? null : UserDao.findByEmail(email);
        if (user == null || !PasswordHash.verify(str(user.get("password")), password)) {
            if (json) {
                RouteSupport.error(response, 401, "邮箱或密码错误");
                return;
            }
            redirect(response, "/loginView?message=" + encode("邮箱或密码错误"));
            return;
        }
        // 弱哈希升级（明文/历史格式 → scrypt）
        String stored = str(user.get("password"));
        if (PasswordHash.needsUpgrade(stored)) {
            UserDao.updatePassword(((Number) user.get("user_id")).longValue(), PasswordHash.hash(password));
        }
        long userId = ((Number) user.get("user_id")).longValue();
        String role = str(user.get("role"));
        // 企业端暂未在 interview-server 实现：明确提示且不下发会话 cookie，
        // 避免 company 账号登录后被 requireApplicant 静默弹回登录页的死循环
        if (!"applicant".equals(role)) {
            if (json) {
                RouteSupport.error(response, 403, "企业端暂未开放，请使用求职者账号登录");
                return;
            }
            redirect(response, "/loginView?message=" + encode("企业端暂未开放，请使用求职者账号登录"));
            return;
        }
        String fullName = "";
        Map<String, Object> applicant = UserDao.findApplicantByUserId(userId);
        fullName = applicant == null ? "" : str(applicant.get("full_name"));
        String token = SessionStore.create(userId, role, fullName);
        // 记住登录（remember=1）→ Max-Age=7 天；否则会话级 cookie（关浏览器失效）
        String remember = param(request, body, "remember");
        long maxAge = "1".equals(remember) ? SessionStore.SESSION_TTL_SECONDS : -1;
        response.setHeader("Set-Cookie", sessionCookie(token, maxAge));
        if (json) {
            RouteSupport.ok(response, "{\"success\":true,\"role\":\"applicant\"}");
            return;
        }
        // 企业端暂未在 interview-server 实现，统一进入求职者工作台
        redirect(response, "/applicant/workspace");
    }

    private static void handleRegister(HttpRequest request, HttpResponse response) throws Throwable {
        boolean json = isJsonRequest(request);
        com.alibaba.fastjson2.JSONObject body = json ? bodyJson(request) : null;
        String email = param(request, body, "email");
        String password = param(request, body, "password");
        String confirm = param(request, body, "confirm_password");
        String role = param(request, body, "role");
        if (!"applicant".equals(role) && !"company".equals(role)) {
            role = "applicant";
        }
        if (!password.equals(confirm)) {
            if (json) {
                RouteSupport.error(response, 400, "两次输入的密码不一致");
                return;
            }
            redirect(response, "/registerView?message=" + encode("密码不一致，请重新输入。"));
            return;
        }
        if (UserDao.findByEmail(email) != null) {
            if (json) {
                RouteSupport.error(response, 409, "该邮箱已被注册，请使用其他邮箱");
                return;
            }
            redirect(response, "/registerView?message=" + encode("该邮箱已被注册，请使用其他邮箱。"));
            return;
        }
        long userId = UserDao.createUser(email, PasswordHash.hash(password), role);
        if ("applicant".equals(role)) {
            String fullName = param(request, body, "full_name");
            String gender = param(request, body, "gender");
            String birthdate = param(request, body, "birthdate");
            String educationLevel = param(request, body, "education_level");
            int workYears = parseInt(param(request, body, "work_years"), 0);
            String expectedPosition = param(request, body, "expected_position");
            int expectedSalary = parseInt(param(request, body, "expected_salary"), 0);
            UserDao.createApplicantWithProfile(userId, fullName, gender, birthdate, educationLevel, workYears,
                expectedPosition, expectedSalary);
        }
        if (json) {
            RouteSupport.json(response, 201, "{\"success\":true}");
            return;
        }
        redirect(response, "/loginView?message=" + encode("注册成功，请登录。"));
    }

    private static void handleLogout(HttpRequest request, HttpResponse response) throws Throwable {
        Cookie cookie = cookie(request, SESSION_COOKIE);
        if (cookie != null) {
            SessionStore.destroy(cookie.getValue());
        }
        response.setHeader("Set-Cookie", sessionCookie("", 0));
        if (isJsonRequest(request) || request.getRequestURI().startsWith("/api/")) {
            RouteSupport.ok(response, "{\"success\":true}");
            return;
        }
        redirect(response, "/");
    }

    /** 会话 cookie 头：HttpOnly + SameSite=Lax（防 CSRF），COOKIE_SECURE=true 时追加 Secure。
     *  maxAgeSeconds < 0 → 会话级 cookie（不写 Max-Age）；0 → 立即过期；>0 → 指定秒数。 */
    private static String sessionCookie(String value, long maxAgeSeconds) {
        String maxAge = maxAgeSeconds < 0 ? "" : "; Max-Age=" + maxAgeSeconds;
        return SESSION_COOKIE + "=" + value + "; Path=/" + maxAge
            + "; HttpOnly; SameSite=Lax" + (cookieSecure ? "; Secure" : "");
    }

    private static void handleCheckRole(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = current(request);
        if (session == null) {
            response.write("{\"success\":false,\"message\":\"用户未登录\",\"role\":null}");
            return;
        }
        response.write("{\"success\":true,\"role\":\"" + session.role + "\"}");
    }

    // ---- 工具 ----

    public static SessionStore.Session current(HttpRequest request) {
        Cookie cookie = cookie(request, SESSION_COOKIE);
        return cookie == null ? null : SessionStore.get(cookie.getValue());
    }

    /** 页面鉴权：未登录/非求职者重定向到登录页并带原因。 */
    public static boolean requireApplicant(HttpRequest request, HttpResponse response) throws Throwable {
        SessionStore.Session session = current(request);
        if (session != null && "applicant".equals(session.role)) {
            return true;
        }
        redirect(response, "/loginView?message=" + encode(
            session == null ? "请先登录" : "当前账号类型无权访问该页面，请使用求职者账号登录"));
        return false;
    }

    public static Cookie cookie(HttpRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    public static void redirect(HttpResponse response, String location) throws Throwable {
        response.setHttpStatus(HttpStatus.FOUND);
        response.setHeader("Location", location);
    }

    public static void notFound(HttpResponse response) throws Throwable {
        response.setHttpStatus(HttpStatus.NOT_FOUND);
        response.write("{\"success\":false,\"message\":\"资源不存在\"}");
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(nvl(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String encode(String value) throws Throwable {
        return java.net.URLEncoder.encode(value, "UTF-8");
    }
}
