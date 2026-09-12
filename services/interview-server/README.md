# interview-server（Feat 集成服务）

单进程 Java 8 服务，替代旧的 Spring Boot mica-voice 网关（`services/mica-voice-gateway`），
作为 Flask 应用唯一的 AI 网关：WebSocket 在线 ASR（mica-voice-core）+ LLM 流式回答（feat-ai）
+ 内嵌静态页面。WebSocket 协议与旧网关 **100% 兼容，Python 侧零改动**。

## 运行要求

- JDK 8（`java -version` 验证）
- Maven 3（`mvn -version` 验证）
- ASR 模型目录（见下文"模型准备"）

## 启动

```powershell
powershell -File services/interview-server/start.ps1
```

启动后：

- `http://localhost:18081/` 首页落地页
- `http://localhost:18081/loginView`、`http://localhost:18081/registerView` 登录/注册页
  （`/login`、`/register` 为兼容别名；页面模板位于 `src/main/resources/templates/`）
- `http://localhost:18081/applicant/workspace` 求职者工作台（登录后访问）
- `http://localhost:18081/api/health` 健康检查 → `{"status":"ok"}`
- `ws://localhost:18081/mica/voice/ws/online-asr` 在线 ASR WebSocket

> 工作台页面为 Thymeleaf 壳 + Vue3/NaiveUI 客户端渲染，依赖
> `static/vendor/naive-ui.min.js`（vue + naive-ui 合并包）。重新打包该文件时**必须**
> 使用完整版 Vue（含模板编译器），见 `temp/vendor-build/build.ps1`；若误用 runtime-only
> 版 Vue（compile 为 NOOP），所有页面会"菜单正常、内容区空白"。

> 注意：`start.ps1` 通过 `-Dfile.encoding=UTF-8` 强制 UTF-8。
> feat 的 SSE 解析使用平台默认字符集，中文 Windows 默认 GBK 会把 UTF-8 多字节末尾字节与
> 反斜杠（如 `你自己\`）合成 GBK 汉字导致流式 JSON 解析失败；不要移除该参数。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `18081` | HTTP/WS 监听端口 |
| `SERVER_HOST` | `127.0.0.1` | 监听地址；手机浏览器局域网验收时设为 `0.0.0.0` |
| `MICA_VOICE_MODELS_DIR` | `third_party/mica-voice/models`（相对项目根） | 模型根目录；start.ps1 默认注入项目根下该目录的绝对路径 |
| `MICA_VOICE_ONLINE_MODEL` | `x-asr-zh-en-chunk-960ms` | 在线 ASR 模型子目录名（可切换 480/960/1920ms） |
| `LLM_API_KEY` | 无 | 未配置时 Copilot 的 LLM 回答链路不可用（WS 连接不受影响） |
| `LLM_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI 兼容 endpoint |
| `LLM_MODEL` | `qwen-plus` | 模型名 |

## 模型准备

在线 ASR 模型不在版本库中。下载脚本：

```powershell
powershell -File third_party/mica-voice/models/scripts/download-models.ps1 x-asr
```

模型解压后必须位于 `MICA_VOICE_MODELS_DIR` 下的 `x-asr-zh-en-chunk-960ms/`
（含 `encoder-960ms.onnx`、`decoder-960ms.onnx`、`joiner-960ms.onnx`、`tokens.txt`）。
目录缺失时服务启动直接报错（fail-fast）并给出指引。

## WebSocket ASR 协议

路径：`/mica/voice/ws/online-asr`；PCM16 S16LE / 16kHz / mono 二进制帧。

| 方向 | 帧 | 载荷 | 行为 |
|---|---|---|---|
| 服务端 | 文本 | `{"type":"ready"}` | 握手成功后立即发送 |
| 客户端 | 文本 | `{"type":"config",...}` | 回 `{"type":"config-ack"}` |
| 客户端 | 文本 | `{"type":"start"}` | 重建流，回 `{"type":"started"}` |
| 客户端 | 二进制 | PCM16 S16LE/16kHz/mono | 解码→喂流→`partial`/`final` |
| 客户端 | 文本 | `{"type":"stop"}` | `inputFinished`→`final`→释放流 |
| 客户端 | 文本 | `{"type":"close"}` | 关闭连接 |
| 客户端 | 文本 | 其他 | `{"type":"error","text":"未知控制帧: ..."}` |
| 服务端 | 文本 | `{"type":"partial"/"final","text":"..."}` | 增量/最终转写（重复文本去重） |
| 服务端 | 文本 | `{"type":"error","text":"..."}` | 识别异常 |

空转写（静音）不发 `final`——与旧网关行为一致，Python `MicaVoiceClient.finish()`
在纯静音下会等待超时，属预期。

## LLM 回答

LLM 回答只走 `/ws/copilot` 链路（`copilot_start` → 转写 → `answer_delta`/`answer_completed`），
无独立 HTTP 端点。`LLM_API_KEY` 未配置时该链路不可用（`/api/health` 与 WS ASR 不受影响）。

## 与旧 Spring 网关的差异

| 项 | 旧网关（mica-voice-gateway） | 本服务（interview-server） |
|---|---|---|
| 框架 | Spring Boot + WebSocket | feat-core 2.4.0 Router + WebSocketUpgrade |
| ASR 集成 | mica-voice Spring 自动装配 | mica-voice-core 门面（`AsrEngine`）直连 |
| LLM | 无 | feat-ai 2.4.0 流式回答（`/ws/copilot` 链路） |
| 静态页 | 无 | classpath:static 内嵌占位页 |
| 连接隔离 | `WebSocketSession attributes` | 每连接一个 `AsrWebSocketUpgrade` 实例 |
| 模型目录 | `${MICA_VOICE_MODELS_DIR:../../third_party/mica-voice/models}` | `MICA_VOICE_MODELS_DIR`（默认 `third_party/mica-voice/models`） |

## 验收记录（2026-08-23）

- [x] 编译：`mvn -q -DskipTests package` 成功
- [x] 单测：AnswerService 3 + Pcm16Decoder 2 + TranscriptRules 2 + AsrWebSocketUpgrade 9 = 16，全部通过
- [x] 启动：`start.ps1` 启动，`/api/health` 返回 `{"status":"ok"}`
- [x] 真实语音：`0-four-speakers-zh.wav` 前 8 秒经 WS 转写为
      "这是一个测试说话人日志的音频，下面我们来播放一些测试音频..."（partial/final 正常）
- [x] Python 协议零改动：`MicaVoiceClient` 直连收到 partial/final 转写
- [x] Flask 回归：`py -3 -m pytest tests/ -q` → 146 passed
- [x] LLM 回答：配置 `LLM_API_KEY` 后 Copilot WS 链路返回流式回答（原独立 HTTP 端点 `/api/copilot/answer` 已下线，无消费者）
- [ ] 真实麦克风人工验收：对着麦克风说话 5 秒，观察浏览器转写区（需人工）

## 安全与 UI 体系（2026-08-24 优化计划后）

- **WS 鉴权**：`/ws/copilot` 升级前必须登录（401），会话操作按 `user_id` 归属校验；`/mica/voice/ws/online-asr` 保持无鉴权兼容 Python 侧
- **Cookie**：`ai_session` 带 `HttpOnly; SameSite=Lax`（`COOKIE_SECURE=true` 时追加 `Secure`）；登录页「保持登录」勾选决定 7 天或会话级
- **静态资源缓存**：`/static/**` 与 `/vendor/**` 响应 `Cache-Control: public, max-age=31536000, immutable`
- **UI 体系**：全部页面统一 `tokens.css + components.css + app-shell.css` 原生设计系统（含深浅主题 `data-theme`）；NaiveUI 已移除，`vendor/` 仅剩 172KB 的 `vue.min.js`（完整版含编译器，重打见 `temp/vendor-build/build-vue.ps1`，铁律：必须 alias 到 `vue.esm-browser.prod.js`）
- **Copilot 来源透传**：`copilot_start` 的 `mode/sources` 归一化为连接级状态，转写帧 `source`/`speaker` 如实上报；`copilot_set_speaker` 回执带 `source`
