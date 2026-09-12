package com.aiinterview.server.web;

import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * web/dist SPA 托管：运行时直接读取文件系统上的前端构建产物（目录解析顺序见
 * {@link #resolveDistDir()}），页面路由回落到 index.html（客户端路由接管），
 * /assets/* 由本类直接服务。产物不存在时页面路由返回 404
 * （请先在 web/ 执行 pnpm build）。
 *
 * 产物不进源码树、不进 target/，打包 JS 的扫描误报不会回流。
 */
public final class SpaSupport {

    private SpaSupport() {
    }

    private static volatile Path distDir;
    private static volatile boolean resolved = false;

    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

    static {
        CONTENT_TYPES.put(".html", "text/html; charset=utf-8");
        CONTENT_TYPES.put(".js", "text/javascript; charset=utf-8");
        CONTENT_TYPES.put(".mjs", "text/javascript; charset=utf-8");
        CONTENT_TYPES.put(".css", "text/css; charset=utf-8");
        CONTENT_TYPES.put(".json", "application/json; charset=utf-8");
        CONTENT_TYPES.put(".svg", "image/svg+xml");
        CONTENT_TYPES.put(".png", "image/png");
        CONTENT_TYPES.put(".jpg", "image/jpeg");
        CONTENT_TYPES.put(".jpeg", "image/jpeg");
        CONTENT_TYPES.put(".gif", "image/gif");
        CONTENT_TYPES.put(".ico", "image/x-icon");
        CONTENT_TYPES.put(".woff", "font/woff");
        CONTENT_TYPES.put(".woff2", "font/woff2");
        CONTENT_TYPES.put(".ttf", "font/ttf");
        CONTENT_TYPES.put(".map", "application/json; charset=utf-8");
        CONTENT_TYPES.put(".txt", "text/plain; charset=utf-8");
        CONTENT_TYPES.put(".webmanifest", "application/manifest+json");
    }

    /** 产物目录解析：WEB_DIST_DIR 环境变量优先，其次按常见工作目录推导。 */
    private static Path resolveDistDir() {
        String env = System.getenv("WEB_DIST_DIR");
        if (env != null && !env.trim().isEmpty()) {
            Path candidate = java.nio.file.Paths.get(env).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate.resolve("index.html"))) {
                return candidate;
            }
        }
        Path cwd = java.nio.file.Paths.get("").toAbsolutePath().normalize();
        // 候选：当前目录、上级、上两级（start.ps1 以 services/interview-server 为工作目录）
        for (Path base : new Path[]{cwd, cwd.getParent(), cwd.getParent() == null ? null : cwd.getParent().getParent()}) {
            if (base == null) {
                continue;
            }
            Path candidate = base.resolve("web").resolve("dist").normalize();
            if (Files.isRegularFile(candidate.resolve("index.html"))) {
                return candidate;
            }
        }
        return null;
    }

    private static Path distDir() {
        if (!resolved) {
            distDir = resolveDistDir();
            resolved = true;
        }
        return distDir;
    }

    /** SPA 产物是否已部署（web/dist/index.html 存在）。 */
    public static boolean available() {
        return distDir() != null;
    }

    /** 输出 SPA 入口页（客户端路由按 URL 自行分发）。 */
    public static void serveIndex(HttpResponse response) throws Throwable {
        Path dist = distDir();
        if (dist == null) {
            AuthRoutes.notFound(response);
            return;
        }
        response.setHeader("Content-Type", "text/html; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache");
        response.write(new String(Files.readAllBytes(dist.resolve("index.html")), java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 服务 /assets/* 前端构建资源；返回 false 表示未处理（调用方继续静态兜底）。 */
    public static boolean serveAsset(HttpRequest request, HttpResponse response) throws Throwable {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/assets/")) {
            return false;
        }
        Path dist = distDir();
        if (dist == null) {
            AuthRoutes.notFound(response);
            return true;
        }
        String rel = uri.substring("/assets/".length());
        Path target = dist.resolve("assets").resolve(rel).normalize();
        if (rel.trim().isEmpty() || !target.startsWith(dist) || !Files.isRegularFile(target)) {
            AuthRoutes.notFound(response);
            return true;
        }
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase();
        response.setHeader("Content-Type", CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"));
        response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        response.write(new String(Files.readAllBytes(target), java.nio.charset.StandardCharsets.ISO_8859_1));
        return true;
    }
}
