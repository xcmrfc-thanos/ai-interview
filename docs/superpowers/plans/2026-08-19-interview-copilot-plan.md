# AI 面试 Copilot MVP 实施计划（技术参考，已被统一计划取代）

> **状态（2026-08-21）：** 本文不再作为独立产品路线执行。产品范围与实施顺序以 [个人 AI 面试作战台实施计划](./2026-08-21-personal-ai-interview-workspace-plan.md) 为准；本文仅保留 Copilot 技术决策和历史验证记录。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付个人自用的 AI 面试 Copilot，让用户在电脑或手机浏览器中采集面试音频，实时转写面试官问题，结合简历、JD 和对话上下文生成低干扰的回答建议。

**Architecture:** 一期以“面试手机扬声器 + 备用设备麦克风”的近场采集模式为主线：浏览器将 16kHz 单声道 PCM 经 Socket.IO 发送到 Flask，Flask 通过独立的 Java `mica-voice` 网关完成流式 ASR，并将增量转写、状态和流式 LLM 回答经 Socket.IO 返回页面。检测到完整问题后，使用结构化上下文生成回答要点和参考回答。面试计划只作为上下文配置，模拟面试和简历优化放在 P1。所有外部服务调用必须有超时、取消、重试上限和可恢复错误状态。

**Tech Stack:** Flask 3.0.3, Flask-SocketIO, SQLAlchemy, MySQL, Jinja2, HTML/JavaScript, Flask-Session, `mica-voice` Java 网关（Streaming Paraformer）, OpenAI SDK（阿里百炼兼容接口）。音频采集复用 `RecorderManager` AudioWorklet，浏览器和 Flask 的实时通信统一使用 Socket.IO；先以桌面 Chrome 验证，再覆盖手机浏览器。

---

## 产品范围与优先级

### P0：实时面试辅助闭环

1. 创建或选择一次 Copilot 会话，并关联公司、岗位、JD 和简历。
2. 电脑或手机浏览器请求麦克风权限并持续采集音频。
3. 音频分片进入实时 ASR，前端展示增量转写和最终转写。
4. 识别面试官问题结束，结合最近上下文、岗位要求和简历触发回答生成。
5. 先展示回答要点，再展示可展开的简洁参考回答和追问建议。
6. 处理麦克风拒绝、设备切换、网络中断、ASR/LLM 超时、空结果、重复触发和页面刷新恢复。

### P1：支撑功能

- 面试计划：保存和复用公司、岗位、JD、简历关联。
- 模拟面试：用文本输入验证问题、回答、评分和上下文链路。
- 简历优化：对照 JD 提供匹配分析和修改建议。
- 会话复盘：保存转写、回答建议和错误事件，便于排查和改进。

### 当前不做

- 语音播报回答。
- 会员、付费、套餐、支付和商业化体系。
- 企业招聘、多人协作、招聘方后台。
- 独立原生移动 App；一期只保证手机浏览器可用。
- 自动控制或录制第三方会议软件；只处理浏览器能获得的音频输入。

---

## 一期技术决策与约束

### 音频边界

- P0 首版支持“面试手机开启扬声器 + 备用电脑/手机打开 Copilot”的近场采集模式；备用设备的浏览器麦克风采集房间声场。
- 不承诺自动获取第三方会议软件系统音频，不保证耳机模式能采到面试官声音。
- 桌面端先验证 Chrome；手机端验证目标浏览器，记录权限、后台切换和锁屏限制。
- 页面必须显示当前音频设备、采集状态和最近一次错误。
- 不把“自己的回答被识别成问题”假设为已解决；首版用会话状态、静音间隔、文本问题特征和手动暂停辅助降低误触发，并记录误判样本。

### 实时数据流

```text
Browser microphone
  -> RecorderManager AudioWorklet
  -> PCM S16LE / 16kHz / mono frames
  -> Socket.IO audio_frame
  -> Flask ASR provider
  -> mica-voice Java gateway / Streaming Paraformer
  -> ASR partial/final transcript + VAD events
  -> utterance boundary + speaker heuristic
  -> context window (JD + resume + recent turns)
  -> LLM answer stream (DashScope OpenAI-compatible API)
  -> Socket.IO events
  -> low-distraction transcript/answer panel
```

### 性能目标

- ASR 增量转写首字：不超过 1 秒。
- 面试官问题结束后，回答要点首屏：不超过 3 秒。
- 完整参考回答：目标不超过 5 秒；超时必须先显示可用要点或错误状态。
- 前端连续运行 30 分钟不出现未处理的内存增长、重复请求或状态卡死。
- 断网恢复后，最多丢失当前音频分片，不重复提交已确认问题。

---

## 文件结构

| 文件 | 类型 | 职责 |
|---|---|---|
| `models/InterviewPlan.py` | 新建 | P1 面试上下文配置 |
| `models/CopilotSession.py` | 新建 | P0 会话状态、关联计划、恢复信息 |
| `models/CopilotTurn.py` | 新建 | P0 转写、问题、回答建议和状态记录 |
| `models/CopilotEvent.py` | 新建 | P0 错误、重连、性能计时和诊断事件 |
| `routes/route_copilot.py` | 新建 | 创建/恢复会话、音频入口、实时事件入口、暂停/结束 |
| `services/mica-voice-gateway/` | 新建 | 独立 Java ASR 服务，托管 mica-voice 模型与在线流 |
| `services/audio_chunk_service.py` | 新建 | PCM 帧校验、大小限制、顺序和幂等 |
| `services/asr_service.py` | 新建 | `AsrProvider` 抽象、增量/最终转写、超时 |
| `services/mica_voice_client.py` | 新建 | Flask 与 mica-voice 网关的长连接客户端 |
| `services/utterance_service.py` | 新建 | 问题边界、静音间隔、重复和误触发判断 |
| `services/context_service.py` | 新建 | 简历/JD/最近对话裁剪和脱敏 |
| `services/answer_service.py` | 新建 | 流式回答、回答要点、追问建议、取消旧请求 |
| `templates/applicant/copilot.html` | 新建 | 电脑/手机低干扰实时界面 |
| `static/js/copilot.js` | 新建 | RecorderManager PCM 采集、Socket.IO 重连、事件渲染和状态机 |
| `static/css/copilot.css` | 新建 | 响应式低干扰展示 |
| `tests/services/test_utterance_service.py` | 新建 | 问题边界和误触发测试 |
| `tests/services/test_context_service.py` | 新建 | 上下文裁剪和 Token 限制测试 |
| `tests/routes/test_copilot.py` | 新建 | 会话、权限、音频和事件 API 测试 |
| `routes/route_interview_plan.py` | 新建 | P1 面试计划 CRUD |
| `routes/route_mock_interview.py` | 新建 | P1 文本模拟面试 |
| `routes/route_resume_optimize.py` | 新建 | P1 简历优化 |
| `routes/__init__.py` | 修改 | 注册 Blueprint |
| `app.py` | 修改 | 页面路由入口 |

---

## P0 实施任务

## 当前执行状态（2026-08-21）

已落地的 P0 基础能力：

- 已将 `https://gitee.com/dreamlu/mica-voice` 克隆到 `third_party/mica-voice/` 作为本地参考源码，并通过 `.gitignore` 排除，不纳入本项目提交历史。
- 已建立 `mica-voice` Java WebSocket 网关骨架，固定输入为 `PCM S16LE / 16kHz / mono`，并通过 Java 单元测试。
- 已建立 Flask 侧 `MicaVoiceProvider`、`MicaVoiceClient` 和 `CopilotStream`，覆盖会话启动、序列幂等、PCM 校验、暂停、结束和错误事件，并通过定向 Python 测试。
- 已建立 Copilot 页面、`RecorderManager` 音频采集和 Socket.IO 事件桥接；Socket.IO 连接按 `sid` 隔离，事件不会广播给其他客户端。
- 已完成前端 JavaScript 语法检查和 Python 编译检查。

仍未验收、不得提前标记完成的项目：

- 未下载并加载 `mica-voice` 在线模型，尚无真实语音转写结果和延迟数据。
- 未完成面试手机扬声器 + 备用设备麦克风的桌面/手机浏览器实测。
- 上下文持久化、问题边界与说话人启发式、阿里百炼流式回答、断线恢复和 30 分钟稳定性尚未完成。

### Task 1: 执行专项音频与 mica-voice 技术 Spike

**目的：** 以 [备用设备扬声器采集与 mica-voice ASR 实施计划](./2026-08-20-speaker-capture-mica-voice-plan.md) 为准，验证设备采集、PCM 协议、mica-voice 流式 ASR 和性能基线。

**Files:**
- Create: `docs/superpowers/spikes/2026-08-20-speaker-capture-results.md`
- Create: `scripts/check_speaker_capture.html`
- Test: 桌面 Chrome 和目标手机浏览器手工验证

- [ ] **Step 1:** 按 0820 专项计划 Task 1 验证面试手机扬声器与备用设备麦克风的近场采集链路。
- [ ] **Step 2:** 验证 `RecorderManager` 输出 `PCM S16LE / 16kHz / mono`，不能使用 `MediaRecorder` 的 WebM/Opus 作为 ASR 主输入。
- [ ] **Step 3:** 先完成 0820 专项计划 Task 2 的 mica-voice 网关搭建，再完成 30 秒流式转写，记录首字延迟、最终延迟、CPU/内存和错误码。
- [ ] **Step 4:** 验证 Socket.IO 连接、重连和事件顺序；在 spike 文档记录协议、设备摆放和限制。
- [ ] **Step 5:** 只有桌面浏览器能稳定采到面试官扬声器声音且 ASR 达标后，才能开始 Task 2。

### Task 2: P0 会话与领域模型

**Files:**
- Create: `models/CopilotSession.py`
- Create: `models/CopilotTurn.py`
- Create: `models/CopilotEvent.py`
- Modify: `models/__init__.py`
- Test: `tests/models/test_copilot_models.py`

- [ ] **Step 1:** 为会话定义 `created/running/paused/ended/error` 状态、用户归属、计划/简历关联和 `last_client_sequence`。
- [ ] **Step 2:** 为 turn 定义 `recording/transcribing/ready/generating/completed/failed` 状态，并保存 partial/final transcript、answer_points、reference_answer、follow_up。
- [ ] **Step 3:** 为 event 定义 `event_type`, `client_sequence`, `latency_ms`, `error_code`, `payload`，支持诊断但不保存原始音频。
- [ ] **Step 4:** 编写用户隔离、状态转换和序列幂等测试，运行 `pytest tests/models/test_copilot_models.py -q`。

### Task 3: Socket.IO 会话协议与会话 API

**Files:**
- Create: `routes/route_copilot.py`
- Modify: `routes/__init__.py`, `app.py`
- Test: `tests/routes/test_copilot.py`

- [ ] **Step 1:** 实现 `POST /copilot/sessions`，校验登录用户、公司/岗位/JD/简历归属，返回 `session_id` 和初始状态。
- [ ] **Step 2:** 实现 `GET /copilot/sessions/<session_id>`，只允许所属用户读取，并返回可恢复的当前 turn 和上下文摘要。
- [ ] **Step 3:** 实现 `POST /copilot/sessions/<session_id>/pause` 与 `/resume`，保证重复调用幂等。
- [ ] **Step 4:** 实现 `POST /copilot/sessions/<session_id>/end`，停止后续音频和 LLM 请求。
- [ ] **Step 5:** 在现有 Socket.IO 服务上实现 `copilot_audio_frame`, `copilot_pause`, `copilot_resume`, `copilot_end` 入站事件，及 `session_state`, `transcript_partial`, `transcript_final`, `answer_started`, `answer_delta`, `answer_completed`, `error` 出站事件。
- [ ] **Step 6:** 为未登录、越权、无效状态和断线重连编写测试，运行 `pytest tests/routes/test_copilot.py -q`。

### Task 4: PCM 音频帧接收与浏览器采集

**Files:**
- Create: `services/audio_chunk_service.py`
- Modify: `routes/route_copilot.py`
- Create: `static/js/copilot.js`
- Create: `templates/applicant/copilot.html`
- Create: `static/css/copilot.css`
- Test: `tests/services/test_audio_chunk_service.py`、浏览器手工验证

- [ ] **Step 1:** 前端复用 `RecorderManager` AudioWorklet，以 `PCM S16LE / 16kHz / mono` 输出 20-80ms 音频帧；不直接使用 `MediaRecorder` WebM/Opus 分片。
- [ ] **Step 2:** 通过 `copilot_audio_frame` 事件发送 `session_id`、`client_sequence` 和 PCM 帧；后端校验会话状态、序列号、采样格式和帧大小，重复序列直接返回已处理结果，乱序帧进入错误状态。
- [ ] **Step 3:** 前端实现 `idle/requesting/recording/paused/reconnecting/error/ended` 状态机，权限拒绝和设备断开必须可见。
- [ ] **Step 4:** 前端缓存未确认序列，网络恢复后按顺序重试，超过 3 次转为可恢复错误且不无限重试。
- [ ] **Step 5:** 完成桌面 Chrome 的 30 秒采集验证和手机浏览器权限验证，运行 `pytest tests/services/test_audio_chunk_service.py -q`。

### Task 5: mica-voice ASR 网关适配与增量转写

**Files:**
- Create: `services/asr_service.py`
- Modify: `routes/route_copilot.py`
- Create: `tests/services/test_asr_service.py`

- [ ] **Step 1:** 定义 `AsrProvider` 接口与 `AsrResult(text, is_final, start_ms, end_ms, provider_request_id)`，接口包含 `start_session`、`send_audio`、`pause`、`finish`、`close`。
- [ ] **Step 2:** 实现 `MicaVoiceProvider`：每个 Copilot session 对应一条 mica-voice `OnlineAsrService` stream，设置连接、写入、空闲和总超时，并把网关错误映射为稳定错误码。
- [ ] **Step 3:** 将 partial/final/VAD 结果发布为 Socket.IO 事件；同一 turn 的结果按版本号更新，不能重复追加文本。
- [ ] **Step 4:** 空转写、低置信度、网关过载和模型未加载分别处理，不触发 LLM。
- [ ] **Step 5:** 用固定音频 fixture 验证首字、最终文本、超时和重试，运行 `pytest tests/services/test_asr_service.py -q`。

### Task 6: 问题边界、说话人启发式与上下文管理

**Files:**
- Create: `services/utterance_service.py`
- Create: `services/context_service.py`
- Create: `tests/services/test_utterance_service.py`
- Create: `tests/services/test_context_service.py`

- [ ] **Step 1:** 用最终转写、静音间隔、最小文本长度和问句特征判断 utterance 是否完成；默认静音窗口为 800ms，并做成配置项。
- [ ] **Step 2:** 在 Copilot 状态中提供“暂停识别”控制；暂停期间只展示转写，不触发回答。
- [ ] **Step 3:** 对连续重复文本、短确认词和疑似用户回答应用去重/过滤规则，并记录 `speaker_heuristic` 诊断事件。
- [ ] **Step 4:** 上下文按 `JD + 简历摘要 + 最近 6 个已确认 turn + 当前问题` 组装，超过 Token 预算时优先保留当前问题和岗位要求。
- [ ] **Step 5:** 对简历/JD 中的敏感字段做最小化处理；不得把原始音频放入 LLM 请求。
- [ ] **Step 6:** 编写边界、重复、超长和敏感字段测试，运行两个 service 测试文件。

### Task 7: 流式回答生成

**Files:**
- Create: `services/answer_service.py`
- Modify: `routes/route_copilot.py`
- Create: `tests/services/test_answer_service.py`

- [ ] **Step 1:** 定义结构化输出：`answer_points`（3-5 条）、`reference_answer`（简洁段落）、`follow_up`（必要时为空）。
- [ ] **Step 2:** 先请求低 Token 的回答要点，收到首个可用结果后发布 `answer_delta`；随后补充完整回答。
- [ ] **Step 3:** 新问题到达时取消旧的未完成 LLM 请求，避免旧答案覆盖新问题。
- [ ] **Step 4:** JSON 解析失败时保留纯文本答案；超时先返回已生成片段并发布错误事件。
- [ ] **Step 5:** 对 prompt 注入简历/JD/上下文边界，明确模型只能生成参考建议，不代替用户发言。
- [ ] **Step 6:** 使用 fake LLM 流验证首 Token、取消、超时、空结果和解析失败，运行 `pytest tests/services/test_answer_service.py -q`。

### Task 8: 低干扰响应式界面与端到端验收

**Files:**
- Modify: `templates/applicant/copilot.html`, `static/js/copilot.js`, `static/css/copilot.css`
- Create: `tests/e2e/copilot-smoke.md`
- Test: 桌面 Chrome、手机浏览器、真实麦克风场景

- [ ] **Step 1:** 页面首屏只保留会话信息、采集状态、面试官转写、回答要点和可展开参考回答；不使用营销式说明和大面积装饰。
- [ ] **Step 2:** 实现事件顺序保护：旧 turn 的事件不能更新当前 turn；刷新后按 session API 恢复。
- [ ] **Step 3:** 展示权限拒绝、网络重连、ASR 超时、LLM 超时、空结果、暂停和结束状态，并提供明确恢复操作。
- [ ] **Step 4:** 运行 `pytest -q` 和 `python -m compileall .`，修复本次改动引入的失败。
- [ ] **Step 5:** 完成手工验收：面试手机扬声器模式下问题转写首字 <= 1 秒，回答要点 <= 3 秒，完整回答目标 <= 5 秒；连续运行 30 分钟无状态卡死。
- [ ] **Step 6:** 记录桌面/手机差异、距离/音量/噪声、误识别自己的回答样本和未解决限制到 `tests/e2e/copilot-smoke.md`。

---

## P1 支撑任务

### Task 9: 面试计划与上下文管理

**Files:** `models/InterviewPlan.py`, `routes/route_interview_plan.py`, `templates/applicant/interview_plans.html`

- [ ] 保存公司、岗位、JD、岗位要求和简历关联；创建会话时复用这些字段。
- [ ] 增加归属校验、必填校验、编辑和删除测试。
- [ ] 页面提供“开始 Copilot”入口，优先跳转实时辅助页面；模拟面试和简历优化作为次级入口。

### Task 10: 文本模拟面试

**Files:** `models/MockInterviewRecord.py`, `routes/route_mock_interview.py`, `templates/applicant/mock_interview.html`

- [ ] 复用 `context_service.py` 和 `answer_service.py`，文本输入验证多轮上下文、参考答案和评分。
- [ ] 模拟面试不得复制一套独立 prompt 或 LLM 客户端；使用 fake LLM 测试错误处理。
- [ ] 页面支持开始、回答、评分和历史记录，但不阻塞 P0 实时会话交付。

### Task 11: 简历优化

**Files:** `routes/route_resume_optimize.py`, `templates/applicant/resume_optimize.html`

- [ ] 对照岗位要求输出匹配分析、缺口、关键词和修改建议。
- [ ] 复用现有简历归属校验和 LLM 调用封装。
- [ ] 增加超时、JSON 解析失败和空简历错误提示。

### Task 12: 会话复盘与诊断

**Files:** `models/CopilotEvent.py`, `routes/route_copilot.py`, `templates/applicant/copilot.html`

- [ ] 展示当前会话的转写、回答建议和关键错误，不展示原始音频。
- [ ] 提供导出文本或复制摘要能力；不引入会员或商业化数据模型。
- [ ] 统计首字延迟、回答首屏延迟、完整回答延迟和重连次数，用于后续优化。

---

## 集成顺序与质量门禁

1. 先完成 0820 专项计划 Task 1-2 和本计划 Task 1：若浏览器无法获得满足需求的音频输入，或 mica-voice 无法输出合格的实时转写，必须先调整产品边界或切换 ASR provider，不得直接进入大规模 CRUD 开发。
2. 按 Task 2 -> Task 3 -> Task 4 -> Task 5 -> Task 6 -> Task 7 -> Task 8 交付 P0；每个任务完成后运行对应测试。
3. P1 任务只能复用 P0 的上下文、LLM 和错误处理边界，禁止创建第二套实时链路。
4. 完成前必须运行：

```bash
pytest -q
python -m compileall .
```

5. 若外部 ASR/LLM 凭证、MySQL 或浏览器权限不可用，报告未验证项和替代验证结果，不得声称实时链路已完成。

---

## 需求覆盖检查

| 需求 | 对应任务 |
|---|---|
| 电脑/手机浏览器辅助真实面试 | Task 1、Task 4、Task 8 |
| 麦克风实时采集 | Task 4 |
| ASR 增量/最终转写 | Task 5 |
| 避免自己的回答反复触发 | Task 6 |
| 简历/JD/对话上下文 | Task 2、Task 6、Task 9 |
| AI 回答要点、参考回答、追问建议 | Task 7、Task 8 |
| 权限、断网、超时、空结果恢复 | Task 3、Task 4、Task 5、Task 7、Task 8 |
| 面试计划、模拟面试、简历优化 | Task 9、Task 10、Task 11 |
| 低干扰和跨端展示 | Task 8 |
| 延迟和稳定性量化验证 | Task 1、Task 8、Task 12 |

## 明确限制

- 首版以面试手机扬声器与备用设备麦克风的近场采集为主；不能保证采集第三方会议软件系统音频，耳机模式也不保证能采到面试官声音。
- 说话人区分首版采用启发式和手动暂停，不能宣称达到可靠的声纹识别效果。
- P0 不包含语音播报、支付、企业招聘和原生 App。
