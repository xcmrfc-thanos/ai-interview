# 备用设备扬声器采集与 mica-voice ASR 专项计划（统一计划的技术参考）

> **状态（2026-08-21）：** 本文作为 [个人 AI 面试作战台实施计划](./2026-08-21-personal-ai-interview-workspace-plan.md) Task 8、9、15 的专项技术参考，不再独立定义产品交付状态。

> **主计划：** [AI 面试 Copilot MVP 实施计划](./2026-08-19-interview-copilot-plan.md)
>
> **执行位置：** 本文档是主计划 P0 Task 1 的技术 Spike 和 Task 4-5 的 ASR 实现依据。主计划定义产品范围、会话模型、LLM、界面和最终交付；本文档只定义近场音频采集与 mica-voice 网关，不重复实现 Copilot 业务功能。

**Goal:** 支持用户用一台手机参加真实面试并开启扬声器，另一台电脑或手机打开 Copilot 网站，通过麦克风采集面试现场声音，使用本地 `mica-voice` 流式 ASR 转写并生成回答建议。

**适用场景：**

```text
面试手机（参加会议，开启扬声器）
        -> 房间声场
备用电脑/手机（打开 Copilot，使用麦克风）
        -> Flask-SocketIO
        -> mica-voice Java ASR 服务
        -> 转写结果与问题边界
        -> 阿里百炼 LLM
        -> Copilot 页面展示回答要点
```

## 方案决策

- 使用 `mica-voice` 的 `OnlineAsrService`，优先验证 Streaming Paraformer；备选 X-ASR。
- `mica-voice` 作为独立 Java 服务部署，不直接嵌入当前 Flask/Python 进程。
- 浏览器到 Flask 使用现有 `Flask-SocketIO`；Flask 到 mica-voice 使用 WebSocket 或内部长连接适配层。
- 浏览器只向自有 Flask 服务发送 PCM 音频，不暴露讯飞、mica-voice 或百炼密钥。
- 首版不采集第三方会议软件系统音频，也不支持耳机模式下自动获得面试官声音。
- 首版不做可靠声纹识别；使用 VAD、静音窗口、手动暂停和文本启发式降低把用户回答误判为问题的概率。

## 依赖与部署

### mica-voice 服务

- JDK 17 或更高版本。
- `mica-voice-core` 或其 Spring Boot starter。
- Streaming Paraformer 模型；模型不打包进 jar，部署时放在指定 `models/` 目录。
- 独立监听内网地址，例如 `127.0.0.1:18080`，只允许 Flask 服务访问。
- 提供以下内部能力：创建流、写入 PCM 帧、返回 partial/final 文本、结束流、释放资源。

### Flask 服务

- 补齐运行时依赖：`Flask-SocketIO` 与 Python WebSocket 客户端/服务端适配库。
- 增加 `MICA_VOICE_ASR_URL`、`MICA_VOICE_MODEL_DIR`、音频采样率和超时配置。
- 保留现有 `openai` SDK 和阿里百炼流式 LLM，不新增第二套 LLM 客户端。

## 专项执行顺序

1. 先完成 Task 1，确认“面试手机扬声器 -> 备用设备麦克风”能稳定采到面试官声音。
2. 再完成 Task 2，确认本机 mica-voice Streaming Paraformer 能输出合格的实时转写。
3. 最后完成 Task 3 和 Task 4，并回到主计划的 P0 Task 2-8 交付 Copilot 会话、上下文、LLM 与界面。
4. Task 5 和 Task 6 的结果作为主计划 P0 Task 6 和 Task 8 的输入与验收证据；完整 Copilot 端到端验收只在主计划 Task 8 执行一次。

## 专项实施任务

### Task 1: 验证设备采集链路

**Files:**
- Create: `docs/superpowers/spikes/2026-08-20-speaker-capture-results.md`
- Create: `scripts/check_speaker_capture.html`

- [ ] 面试手机开启扬声器，备用电脑和备用手机分别采集 30 秒中文语音。
- [ ] 记录设备距离、音量、环境噪声、是否戴耳机和浏览器版本。
- [ ] 验证备用设备能同时采到面试官声音和用户声音；记录双方音量差异。
- [ ] 验证浏览器切后台、锁屏、拒绝麦克风权限和切换麦克风时的行为。
- [ ] 只有在至少一个桌面浏览器场景能够稳定采到面试官声音后，才进入服务开发。

### Task 2: 搭建 mica-voice Java ASR 服务

**Files:**
- Create: `services/mica-voice-gateway/`（独立 Java 服务目录）
- Create: `services/mica-voice-gateway/README.md`

- [ ] 引入 `mica-voice-core`，配置 Streaming Paraformer 模型路径和中文识别参数。
- [ ] 实现每个 Copilot 会话一条在线 ASR stream，持续接收 `PCM S16LE / 16kHz / mono`。
- [ ] 输出统一事件：`partial_transcript`、`final_transcript`、`vad_speech_start`、`vad_speech_end`、`asr_error`。
- [ ] 限制单会话音频流数量和空闲时间；结束或异常时释放 native stream 和模型资源。
- [ ] 用固定音频 fixture 验证 partial/final 结果、静音结束、空音频、超时和重复关闭。

### Task 3: Flask 与 mica-voice 网关对接

**Files:**
- Create: `services/asr_service.py`
- Create: `services/mica_voice_client.py`
- Modify: `routes/route_copilot.py`, `utils/socketio_service.py`
- Modify: `requirements.txt`

- [ ] 定义 `AsrProvider` 接口，至少包含 `start_session`、`send_audio`、`pause`、`finish`、`close`。
- [ ] 实现 `MicaVoiceProvider`，将客户端音频帧按顺序转发到 Java 网关，并将识别事件转为 Flask 内部事件。
- [ ] 增加连接超时、写入超时、空闲超时和最多 3 次重连；重连不可重复提交已确认音频序列。
- [ ] 处理网关不可用、模型未加载、协议错误、会话不存在和服务端主动关闭。
- [ ] 使用 fake 网关完成单元测试，不依赖真实模型才能运行 `pytest`。

### Task 4: 浏览器 PCM 采集与 Socket.IO 接入

**Files:**
- Create/Modify: `static/js/copilot.js`
- Create/Modify: `templates/applicant/copilot.html`
- Create/Modify: `static/css/copilot.css`

- [ ] 复用现有 `RecorderManager` AudioWorklet，输出 16kHz、单声道、16-bit PCM；不要把 `MediaRecorder` 的 WebM/Opus 直接发送给 mica-voice。
- [ ] 通过 Socket.IO 发送带 `session_id`、`client_sequence` 和音频帧的事件。
- [ ] 向主计划的 Copilot 页面提供采集状态、转写事件和“暂停识别”控制；回答要点和参考回答由主计划 Task 7 实现。
- [ ] 网络中断后缓存未确认序列，恢复时按序重发；页面刷新后恢复当前会话状态。
- [ ] 明确提示“请让面试手机开启扬声器；耳机模式可能无法采集面试官声音”。

### Task 5: 为主计划提供 VAD 与误识别证据

**Files:**
- Create: `services/utterance_service.py`
- Create: `tests/services/test_utterance_service.py`

- [ ] 输出 mica-voice 的 VAD 结束事件，并向主计划 `utterance_service.py` 提供默认 800ms 静音窗口的验证数据。
- [ ] 连续短确认词、重复 partial 文本和低于最小长度的文本不触发 LLM。
- [ ] 用户回答阶段支持手动暂停；恢复后不把暂停前已确认文本重新提交。
- [ ] 记录 `speaker_heuristic`、误触发原因和原始转写摘要，不保存原始音频。
- [ ] 用面试官提问、用户回答、双方抢话、环境噪声四组样本测试 ASR/VAD 行为；最终的问题触发率由主计划 Task 6 测试。

### Task 6: 采集与 ASR 性能验收

**Files:**
- Create: `tests/e2e/mica-voice-speaker-capture-smoke.md`

- [ ] 桌面 Chrome 完成一次 10 分钟真实采集，ASR 首字延迟目标 <= 1 秒。
- [ ] 记录 ASR 最终转写延迟，供主计划计算“问题结束 -> 回答要点 <= 3 秒”和“完整回答 <= 5 秒”的端到端指标。
- [ ] 备用手机浏览器完成至少一次采集验证，并记录后台/锁屏限制。
- [ ] 连续运行 30 分钟，确认没有 ASR stream 泄漏或 Socket.IO 重连风暴；重复问题和前端状态由主计划 Task 8 验收。
- [ ] 记录距离、音量、噪声和浏览器差异，形成后续设备摆放建议。

## 验收标准

### 必须满足

- 面试手机开启扬声器时，至少一个桌面浏览器可以稳定转写面试官问题。
- ASR 增量转写首字不超过 1 秒，并为主计划提供可计算端到端延迟的时间戳。
- ASR 网关不可用时，页面能显示错误并允许重新连接。
- 用户回答期间可以手动暂停识别，不能持续触发 LLM。
- 讯飞、mica-voice、百炼凭证均只存在服务端环境变量或服务配置，不下发浏览器。

### 明确不保证

- 耳机模式下采集面试官声音。
- 嘈杂环境、远距离、低音量和多人同时讲话时的识别准确率。
- 仅靠单麦克风可靠区分面试官和候选人。
- 手机浏览器切后台或锁屏后的持续采集。

## 回退方案

如果 mica-voice 在目标机器上无法达到延迟或准确率目标，保留同一 `AsrProvider` 接口，增加 `XfyunIatProvider`：

```text
Browser PCM -> Flask-SocketIO -> Xfyun IAT WebSocket -> Flask events -> LLM
```

业务层不感知 ASR 供应商变化，只有 `asr_service.py` 和配置项切换。当前不需要同时维护两条生产链路，先用技术 Spike 的数据选择默认 provider。
