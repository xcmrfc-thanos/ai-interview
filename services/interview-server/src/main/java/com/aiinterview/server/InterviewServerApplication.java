package com.aiinterview.server;

import com.aiinterview.server.asr.AsrProviders;
import com.aiinterview.server.asr.AsrReadiness;
import com.aiinterview.server.db.Db;
import com.aiinterview.server.db.DbWriteExecutor;
import com.aiinterview.server.db.MyBatis;
import com.aiinterview.server.svc.Llm;
import com.aiinterview.server.svc.ResumeAnalyzer;
import net.dreamlu.mica.voice.asr.OnlineAsrService;
import com.aiinterview.server.web.AuthRoutes;
import com.aiinterview.server.web.CopilotRoutes;
import com.aiinterview.server.web.CopilotWs;
import com.aiinterview.server.web.KnowledgeRoutes;
import com.aiinterview.server.web.MockRoutes;
import com.aiinterview.server.web.PageRoutes;
import com.aiinterview.server.web.PlanRoutes;
import com.aiinterview.server.web.ProfileRoutes;
import com.aiinterview.server.web.ReadyRoutes;
import com.aiinterview.server.web.ResumeRoutes;
import com.aiinterview.server.web.ReviewRoutes;
import com.aiinterview.server.web.SettingsRoutes;
import tech.smartboot.feat.Feat;
import tech.smartboot.feat.core.server.HttpServer;
import tech.smartboot.feat.core.server.handler.HttpStaticResourceHandler;
import tech.smartboot.feat.router.Router;

public class InterviewServerApplication {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InterviewServerApplication.class);

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        config.validate();
        Db.init(config);
        MyBatis.init(config);
        DbWriteExecutor.init();
        AsrReadiness.init(config);
        new java.io.File(config.uploadDir()).mkdirs();

        // 静态资源缓存：feat 拦截器不覆盖 fallback 静态处理器，这里在 fallback 外层统一加头
        // /vendor/ 内容稳定可 immutable；/static/ 引用未版本化（无 ?v=），只做短期缓存保证安全修复可达
        final HttpStaticResourceHandler resources = new HttpStaticResourceHandler(opt -> opt.baseDir("classpath:static"));
        Router router = new Router(new tech.smartboot.feat.core.server.HttpHandler() {
            @Override
            public void handle(tech.smartboot.feat.core.server.HttpRequest request,
                               java.util.concurrent.CompletableFuture<Void> future) throws Throwable {
                String uri = request.getRequestURI();
                if (uri.startsWith("/assets/")) {
                    // web/dist 构建产物（classpath:static/app）
                    com.aiinterview.server.web.SpaSupport.serveAsset(request, request.getResponse());
                    future.complete(null);
                    return;
                }
                if (uri.startsWith("/vendor/")) {
                    request.getResponse().setHeader("Cache-Control", "public, max-age=31536000, immutable");
                } else if (uri.startsWith("/static/")) {
                    request.getResponse().setHeader("Cache-Control", "public, max-age=3600");
                }
                resources.handle(request, future);
            }

            @Override
            public void handle(tech.smartboot.feat.core.server.HttpRequest request) throws Throwable {
                resources.handle(request);
            }
        });
        router.route("/api/health", (ctx) ->
            ctx.Response.write("{\"status\":\"ok\"}")
        );
        ReadyRoutes.register(router);
        if (config.isInProcessAsr()) {
            router.route("/mica/voice/ws/online-asr", (ctx) ->
                ctx.Request.upgrade(new AsrWebSocketUpgrade(AsrEngine.getInstance().onlineAsr(config)))
            );
        } else {
            router.route("/mica/voice/ws/online-asr", (ctx) -> {
                ctx.Response.setHttpStatus(tech.smartboot.feat.core.common.HttpStatus.NOT_FOUND);
                ctx.Response.write("{\"error\":\"in-process ASR disabled (ASR_PROVIDER=gateway)\"}");
            });
        }
        AuthRoutes.configure(config.cookieSecure());
        AuthRoutes.register(router);
        PageRoutes.register(router);
        ResumeRoutes.init(config);
        ResumeRoutes.register(router);
        PlanRoutes.init(config);
        PlanRoutes.initExecutors(config);
        PlanRoutes.register(router);
        KnowledgeRoutes.register(router);
        MockRoutes.init(config);
        MockRoutes.register(router);
        CopilotRoutes.register(router);
        CopilotWs.init(config);
        OnlineAsrService inProcessAsr = config.isInProcessAsr()
            ? AsrEngine.getInstance().onlineAsr(config) : null;
        AsrProviders.init(config, inProcessAsr);
        router.route("/ws/copilot", (ctx) -> {
            // 升级前鉴权（WebSocketUpgrade.onHandShake 在 101 之后才触发，无法在那里拒绝握手）
            com.aiinterview.server.web.SessionStore.Session session = AuthRoutes.current(ctx.Request);
            if (session == null) {
                ctx.Response.setHttpStatus(tech.smartboot.feat.core.common.HttpStatus.UNAUTHORIZED);
                ctx.Response.write("{\"success\":false,\"message\":\"用户未登录\"}");
                return;
            }
            ctx.Request.upgrade(new CopilotWs(AsrProviders.copilot(), config,
                new Llm(), session.userId));
        });
        ReviewRoutes.init(config);
        ReviewRoutes.register(router);
        ProfileRoutes.init(config);
        ProfileRoutes.register(router);
        SettingsRoutes.register(router);
        ResumeAnalyzer.init(config);

        HttpServer server = Feat.httpServer().httpHandler(router);
        server.listen(config.host(), config.port());

        // ASR 模型预热：仅 in_process 模式在后台预加载本地模型。
        if (config.isInProcessAsr()) {
            Thread asrWarmup = new Thread(() -> {
                try {
                    long t0 = System.currentTimeMillis();
                    AsrEngine.getInstance().onlineAsr(config);
                    log.info("ASR 模型预热完成，用时 {}ms", System.currentTimeMillis() - t0);
                } catch (Throwable e) {
                    log.warn("ASR 模型预热失败（将在首次连接时懒加载）", e);
                }
            }, "asr-warmup");
            asrWarmup.setDaemon(true);
            asrWarmup.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AsrEngine.getInstance().close();
            server.shutdown();
        }));
        log.info("interview-server listening on http://{}:{}", config.host(), config.port());
        log.info("ASR provider: {}", config.asrProvider());
        if (config.isInProcessAsr()) {
            log.info("WS ASR: ws://localhost:{}/mica/voice/ws/online-asr", config.port());
        } else {
            log.info("Gateway ASR: {}", config.asrGatewayUrl());
        }
    }
}
