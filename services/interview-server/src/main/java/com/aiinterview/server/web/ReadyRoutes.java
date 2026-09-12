package com.aiinterview.server.web;

import com.aiinterview.server.asr.AsrProviders;
import com.aiinterview.server.asr.AsrReadiness;
import com.aiinterview.server.db.Db;
import com.aiinterview.server.observability.ServerMetrics;
import com.alibaba.fastjson2.JSONObject;
import tech.smartboot.feat.core.common.HttpStatus;
import tech.smartboot.feat.router.Router;

/** 就绪探针与进程指标路由（/api/ready、/api/metrics）。 */
public final class ReadyRoutes {

    private ReadyRoutes() {
    }

    /** 注册观测路由。 */
    public static void register(Router router) {
        router.route("/api/ready", (ctx) -> handleReady(ctx.Response));
        router.route("/api/metrics", (ctx) -> handleMetrics(ctx.Response));
    }

    /** 就绪检查：数据库可 ping 且 ASR 模型已预热加载。 */
    private static void handleReady(tech.smartboot.feat.core.server.HttpResponse response) throws Exception {
        boolean dbOk = Db.ping();
        boolean asrOk = AsrReadiness.isReady();
        JSONObject body = new JSONObject();
        body.put("ready", dbOk && asrOk);
        body.put("db", dbOk);
        body.put("asr", asrOk);
        body.put("asr_provider", AsrReadiness.mode());
        if (!dbOk || !asrOk) {
            response.setHttpStatus(HttpStatus.SERVICE_UNAVAILABLE);
        }
        response.write(body.toJSONString());
    }

    /** 返回进程内指标快照（队列深度、拒绝次数、Copilot 连接数）。 */
    private static void handleMetrics(tech.smartboot.feat.core.server.HttpResponse response) throws Exception {
        response.write(ServerMetrics.snapshot().toJSONString());
    }
}
