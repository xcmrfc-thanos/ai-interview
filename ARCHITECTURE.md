# 系统架构

> 面试 Copilot 当前完整架构。技术选型背景与决策记录见 `README.md` 与 `README_PLAN.md`。

## 总览

```text
┌─────────────────────────────────────────────────────────────────┐
│                  浏览器客户端（web/ 前端工程）                     │
│   React 19 + Vite + Tailwind v4 + shadcn/ui（产物 web/dist，     │
│   由双后端托管；采集 AudioWorklet PCM16 / 16kHz / mono）          │
└──────────────┬──────────────────────────────┬───────────────────┘
               │ HTTP（页面/REST，契约见       │ WebSocket /ws/copilot
               │ docs/api-contract.md）       │ （裸 WS，v2 音频帧）
               ▼                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     入口 A：Flask 主应用（:18081，SERVER_PORT 可调）              │
│  app.py                                                         │
│  ├─ routes/*        页面与 REST API（面试计划/准备包/复盘/简历…） │
│  ├─ services/*      业务服务层（Python）                         │
│  │   ├─ copilot_stream.py     Copilot 会话状态机                 │
│  │   ├─ asr_service.py        MicaVoiceProvider（ASR 会话管理）  │
│  │   ├─ sherpa_asr_client.py  ★ 进程内 ASR（sherpa-onnx）        │
│  │   ├─ mica_voice_client.py  WS 网关客户端（回退路径）           │
│  │   └─ answer/review/mock/knowledge/resume_* 等业务服务         │
│  ├─ models/*        SQLAlchemy 领域模型（兼容旧企业 ATS 模型）    │
│  ├─ utils/copilot_socketio.py  Socket.IO 桥（legacy，老前端）    │
│  └─ utils/copilot_ws.py        裸 WS 桥（契约 §6，React 前端）   │
│                                                                 │
│  ASR 后端开关 ASR_BACKEND（sherpa_asr_client.create_asr_client）│
│  ├─ builtin → 进程内 sherpa-onnx；gateway → WS 网关             │
│  └─ auto（默认）→ 内置可用则内置，否则 WS MICA_VOICE_ASR_URL    │
└──────────────┬──────────────────────────────┬───────────────────┘
               │ （可选）WS：mica/voice/ws/    │
               │      online-asr              │
               ▼                              ▼
┌──────────────────────────────┐  ┌───────────────────────────────┐
│  入口 B：interview-server     │  │ 入口 C：mica-voice-gateway    │
│  （:18081，Feat 单进程 Java） │  │ （Spring Boot 旧网关，:18080） │
│  ├─ WS 在线 ASR（协议兼容）   │  │ └─ 仅 WS ASR，无业务          │
│  ├─ LLM 流式回答（feat-ai）   │  └───────────────────────────────┘
│  ├─ 登录/注册 + 求职者工作台  │
│  │   （web/dist React SPA 托管）│
│  └─ MyBatis + SQLite 独立库  │
│  ASR 后端：ASR_BACKEND       │
│    builtin=进程内 mica-voice │
│    core；gateway=转发 C 网关 │
└──────────────────────────────┘

               （Flask 与 interview-server 共用）
┌─────────────────────────────────────────────────────────────────┐
│                    third_party/mica-voice/models/                │
│  x-asr-zh-en-chunk-960ms/（encoder/decoder/joiner.onnx+tokens） │
│  所有 ASR 入口共享同一份模型文件（≈600MB，不进版本库）           │
└─────────────────────────────────────────────────────────────────┘

               （两个主入口各自独立调用）
┌─────────────────────────────────────────────────────────────────┐
│                       外部 LLM API（OpenAI 兼容）                │
│  Flask: .env（LLM_BASE_URL/LLM_API_KEY/LLM_MODEL）              │
│  interview-server: 同名环境变量，默认 dashscope qwen-plus        │
└─────────────────────────────────────────────────────────────────┘
```

## 三个可独立运行的入口

| 入口 | 技术 | 端口 | 定位 |
|---|---|---|---|
| **A. Flask 主应用** | Python 3.12 + Flask + SQLAlchemy + Socket.IO | 18081（SERVER_PORT 可调） | 个人求职者产品主体；进程内 ASR 就绪时**单进程即可完整运行** |
| **B. interview-server** | Java 8 + Feat + mica-voice-core + MyBatis/SQLite + web/ React SPA | 18081 | 独立单进程 Java 版（含登录、工作台、LLM 流式回答）；亦可仅作 Flask 的 WS ASR 网关 |
| **C. mica-voice-gateway** | Java 8 + Spring Boot 2.7 + mica-voice-spring-boot-starter | 18080 | 旧网关，仅 WS ASR；已被 B 取代，保留作纯解码网关（A/B 的 gateway 模式可指向它） |

入口 B（:18081）与 C（:18080）端口不同、可并存；Python 侧通过 `MICA_VOICE_ASR_URL` 无差别对接 B 或 C（协议 100% 兼容）。

## ASR 架构（本轮重点）

### 分层结构

```text
ONNX 模型文件（语言无关）
    ↑ 加载
sherpa-onnx C++ 推理引擎（官方多语言绑定）
    ↑ 封装
┌──────────────┬──────────────────────┬─────────────────────────┐
│ Python 绑定   │ Java 绑定             │ Java 绑定               │
│ sherpa-onnx  │ mica-sherpa-onnx(JNI)│ mica-sherpa-onnx(JNI)  │
│ (pip 包)     │ → mica-voice-core    │ → mica-voice-core       │
│              │   （纯 Java SDK）     │                         │
├──────────────┼──────────────────────┼─────────────────────────┤
│ 进程内       │ InProcessAsrProvider │ Spring Boot 自动装配    │
│ SherpaOnnx-  │ （interview-server）  │ （mica-voice-gateway）  │
│ AsrClient    │                      │                         │
│ （Flask）★   │                      │                         │
├──────────────┼──────────────────────┴─────────────────────────┤
│ WS 客户端     │ GatewayAsrProvider（interview-server 出网关时）│
│ MicaVoice-   │                                                      │
│ Client       │                                                      │
│ （Flask）    │                                                      │
└──────────────┴──────────────────────────────────────────────────────┘
```

要点：`mica-voice-core` 不含模型，只是 Java 封装层；Python 通过官方 `sherpa-onnx` pip 包
直接加载同一份模型，**不需要经过任何 Java 组件**。

### ASR 后端选择逻辑（A、B 共用 `ASR_BACKEND` 开关）

A：`services.sherpa_asr_client.create_asr_client(source)`；
B：`AppConfig.resolveAsrProvider`（`ASR_*` 变量经 start.ps1 从项目根 `.env` 透传）：

```text
ASR_BACKEND=gateway   → 强制 WS 网关（A 连 MICA_VOICE_ASR_URL；B 连 ASR_GATEWAY_URL）
ASR_BACKEND=builtin   → 强制进程内（A=SherpaOnnxAsrClient；B=InProcessAsrProvider）
ASR_BACKEND=auto（默认）→ 本地模型目录存在则进程内，否则回退 WS 网关
未设置 ASR_BACKEND 时，B 回落旧变量 ASR_PROVIDER（in_process / gateway）
```

两个客户端 duck-type 同一接口（`set_result_callback / start / send_audio /
pause / finish / close`），`MicaVoiceProvider` 与上层零改动。

### 进程内客户端实现要点（services/sherpa_asr_client.py）

- 模型进程级单例（`_RECOGNIZER_CACHE`，~600MB 只加载一次，多 session 共享）
- 每 session 一个泵线程，按 100ms chunk（1600 samples）消费 PCM 队列
- `_RECOGNIZER_LOCK` 全局串行化解码（多 session 并发安全；RTF≈0.06，串行余量充足）
- `pause()` 仅停止消费（音频继续入队），`finish()` 解除暂停并冲刷
- 空转写（静音）不发 final——与网关行为一致

### WS 网关协议（三入口通用）

路径 `/mica/voice/ws/online-asr`，PCM16 S16LE / 16kHz / mono 二进制帧：

| 方向 | 帧 | 行为 |
|---|---|---|
| 服务端→客户端 | `{"type":"ready"}` | 握手成功 |
| 客户端→服务端 | `{"type":"config"}` / `{"type":"start"}` / `{"type":"stop"}` / `{"type":"close"}` | 会话控制 |
| 客户端→服务端 | 二进制 PCM | 流式识别 |
| 服务端→客户端 | `{"type":"partial"/"final","text":"..."}` | 增量/最终转写 |

## 环境变量对照

| 变量 | 用于 | 默认值 |
|---|---|---|
| `MICA_VOICE_MODELS_DIR` | A（进程内 ASR 模型根目录） | `third_party/mica-voice/models` |
| `MICA_VOICE_ONLINE_MODEL` | A（模型子目录名，可切 480/960/1920ms） | `x-asr-zh-en-chunk-960ms` |
| `MICA_VOICE_ASR_URL` | A（WS gateway 路径的网关地址，可指 B 或 C） | `ws://127.0.0.1:18081/mica/voice/ws/online-asr` |
| `ASR_BACKEND` | A、B 共用（`gateway` / `builtin` / `auto`） | 空（=auto） |
| `ASR_PROVIDER` | B 旧变量（`ASR_BACKEND` 未设置时生效：`in_process` / `gateway`） | `in_process` |
| `ASR_GATEWAY_URL` | B（gateway 模式下的网关地址） | `ws://127.0.0.1:18080/mica/voice/ws/online-asr` |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | A、B（OpenAI 兼容 LLM） | B 默认 dashscope `qwen-plus` |
| `LLM_COPILOT_MODEL` | A（实时 Copilot 快模型；未配回落 `LLM_MODEL`，ark 回落 `deepseek-v4-flash`） | 空 |
| `LLM_PROVIDER=ark` | A（火山引擎方舟；`LLM_BASE_URL` 默认 `https://ark.cn-beijing.volces.com/api/coding/v3`） | `siliconflow` |
| `AI_INTERVIEW_DATABASE_URL` | A（SQLAlchemy 连接串） | `sqlite:///ai_interview_local.db` |
| `SERVER_PORT` / `SERVER_HOST` | B | `18081` / `127.0.0.1` |

## 部署形态建议

| 场景 | 推荐组合 |
|---|---|
| 个人小工具（推广/开源） | **仅入口 A**，进程内 ASR，单 Python 进程 |
| 分离部署（ASR 独立扩容/共用） | 入口 A + 入口 B（或 C）作 WS 网关 |
| 独立 Java 产品版 | 仅入口 B（自带工作台与数据层） |

## 数据流（实时 Copilot，入口 A 进程内路径）

```text
麦克风/系统音频 → 浏览器 AudioWorklet（PCM16/16k/mono）
  → 裸 WS /ws/copilot → copilot_ws（会话校验/序列号去重，legacy：Socket.IO copilot_socketio）
  → MicaVoiceProvider.send_audio → SherpaOnnxAsrClient（队列）
  → 泵线程 → sherpa-onnx 解码（全局锁）→ 尾静音端点 → final
  → utterance 边界判定 → answer_service → LLM 流式回答
  → WS 事件（answer_queued/started/delta/completed）→ 浏览器渲染要点/参考回答
  → CopilotTurn/CopilotEvent 持久化（刷新可恢复）
```

## 性能优化记录（2026-09 批次）

### Python 双入口热路径（P1）
- `handle_audio` 去掉 per-frame `db.session.commit()`：sequence 只在内存累计，pause/end/disconnect 边界统一落库。基准：100 帧处理期间 DB commit 100 → 0，ack 事件 100 → 1（批量 `_AckBatch`，200ms 窗口/20 帧阈值）。
- `process_result` 放宽 paused 状态接受延迟 final，修复 pause 丢尾句。

### LLM 响应链路（P2）
- `fast_points`：LLM 首字节即发占位 `answer_started`（"正在组织回答…"），正式要点随后覆盖。
- 观测：`answer_completed` 携带 `timing.ttfb_ms` / `timing.total_ms`。
- prompt 瘦身：历史轮次 6 → 2，旧参考回答截断 400 字符，system prompt 强调首行即要点。
- 模型分级：`kind="copilot"` 走 `LLM_COPILOT_MODEL`（未配回落 `LLM_MODEL`；`LLM_PROVIDER=ark` 回落 `deepseek-v4-flash`）。配置统一 `LLM_*` 前缀，无 provider 专属变量。

### Java interview-server（P3 核对）
- B3/B4/B5 实际已落地（`SqlitePragmas` WAL、`ReadyRoutes` /api/ready+metrics、`GatewayAsrProvider` 双模式），57 个 Java 测试全绿。
- 剩余缺口：`MockRoutes` 评分与简历分析仍同步阻塞 HTTP 线程，需接口契约变更（同步 → 异步轮询）后处理。

### 目录清理（P4）
- 删除：`app.pyc`、`pytest_full.txt`、`pytest_out.txt`、`net/`（误生成 class）。
- 归档：`临时文件/` → `docs/archive/legacy-html-snapshots/`（git mv 保留历史）。
- 保留：`third_party/`（`.gitignore` 已忽略，不占历史）、`test-results/`（验收基线）、`聊天记录.md`/`提示词/`（个人内容）。

## 目录速查

```text
web/                           React 19 前端工程（构建产物 web/dist 由双后端托管）
app.py                        入口 A 启动与装配
routes/                       REST 路由（契约 docs/api-contract.md）
services/                     Python 业务服务（含 ASR 双客户端）
services/interview-server/    入口 B（Java 单进程版）
services/mica-voice-gateway/  入口 C（旧 WS 网关，已被 B 取代）
utils/copilot_ws.py           裸 WS Copilot 桥（契约 §6）
utils/copilot_socketio.py     Socket.IO 桥（legacy，老前端）
models/                       SQLAlchemy 领域模型
third_party/mica-voice/models/  ASR 模型文件（所有入口共享，不入库）
data/knowledge/               知识库种子
tests/                        Python / 契约 / 浏览器验收
docs/                         规格与验收记录（含 api-contract.md）
```
