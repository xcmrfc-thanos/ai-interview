# web 前端

React 19 + Vite + Tailwind v4。前端是独立工程：开发在 5173，打包产物 `dist/` 交给 nginx（或后端托管）。

## 端口架构（统一约定）

| 端口 | 服务 | 说明 |
|---|---|---|
| **18081** | 对外后端 API（py Flask，`SERVER_PORT` 可调） | 前端所有 `/api`、`/ws` 的唯一目标 |
| 18080 | mica-voice-gateway（ASR 解码） | 后端内部依赖，前端永不直连 |
| 5173 | vite dev 前端开发服务 | 仅开发用 |

- **开发**：`npm run dev` → 访问 http://localhost:5173，`/api`、`/ws` 由 vite 代理转发到 18081（`vite.config.ts`，可用 `VITE_BACKEND_URL` 覆盖）。
- **打包**：`npm run build` → `dist/`，产物为同源相对路径，不携带后端地址。

## 生产部署（nginx 反代，保持同源）

```nginx
server {
    listen 80;
    root /opt/ai-interview/web/dist;
    index index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:18081;
        proxy_set_header Host $host;
    }
    location /ws/ {                       # 实时辅助 WebSocket
        proxy_pass http://127.0.0.1:18081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 300s;
    }
    location /uploads/ { proxy_pass http://127.0.0.1:18081; }
    location / { try_files $uri /index.html; }
}
```

注意：会话基于 Cookie，浏览器同源策略要求前端与 `/api` 同源（即必须走反代），不要让页面跨源直连后端端口。
（`VITE_API_BASE` + 后端 `CORS_ORIGINS` 仅用于 token 认证场景的实验。）
