# Interview Server 后端与 UI 双计划

> **状态：已完成**（B0–B5 与 U0–U5 均已落地，执行记录见 `README_PLAN.md` 记录 1–44；
> 遗留收尾项——soak 压测、MockRoutes 异步化、speaker 模型就绪探针——已在
> `README.md` 已知限制与记录 44 差距总结中登记）。
> 后续演进由 [2026-09-05 前端统一计划](./2026-09-05-react-web-unification-plan.md) 接替。

- 制定日期：2026-08-29
- 适用范围：`services/interview-server`（Java 8 单体：Feat + mica-voice + MyBatis/SQLite + Thymeleaf + Vue3）
- 目标产品：Java 版独立正式产品，Python 主应用仅作行为参考
- 后端模拟分支：`feature/interview-server-backend-performance`
- 前端模拟分支：`feature/interview-server-ui-refresh`

---

## 任务清单

### 计划一：后端性能与稳定性

- [ ] **B0** 保护现有改动，初始化记录并建立性能/事件契约基线
- [ ] **B1** 用有界执行器、串行事件出口和异步 ASR 隔离 I/O 热路径
- [ ] **B2** 实现真流式 LLM、generation fencing 与语义话轮协调
- [ ] **B3** 收敛 SQLite 事务并异步化准备包、模拟面试和分析任务
- [ ] **B4** 补齐恢复状态机、ready/metrics 与压力验收
- [ ] **B5** 实现 in-process/gateway 可切换 ASR Provider

### 计划二：整体 UI/UX 与页面流畅度

- [ ] **U0** 建立组件样板、页面清单、响应式和视觉回归基线
- [ ] **U1** 统一设计 Token、组件、图标和应用壳
- [ ] **U2** 分批迁移公开页、工作台和准备资产页面
- [ ] **U3** 拆分 Copilot/模拟面试专注工作区
- [ ] **U4** 迁移 AudioWorklet，批处理实时渲染并完善连接状态机
- [ ] **U5** 完成复盘闭环、设置修复、无障碍与性能验收

---

## 共同边界与执行顺序

- 仅改造 Java 产品 `services/interview-server`；Python 主应用和根目录静态页面只作为行为参考。
- 当前工作区已有用户改动，尤其是 [`TranscriptRules.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/TranscriptRules.java)、[`Llm.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/svc/Llm.java) 和 [`TranscriptRulesTest.java`](../../../services/interview-server/src/test/java/com/aiinterview/server/TranscriptRulesTest.java)。实施时不得 reset、checkout、clean 或整文件覆盖，先保存 diff 基线，再逐块合并。
- 根目录 `README_PLAN.md` 当前不存在。首次实施前按 UTF-8 初始化；每份计划完成后追加独立记录，最终合并均等待用户确认。
- 推荐先完成后端 B0–B2，冻结 `transcript_*`、`answer_*`、错误码和恢复协议；非实时 UI 可同步推进，实时工作区 U4 在协议冻结后接入。

---

# 计划一：后端性能与稳定性

**模拟分支：** `feature/interview-server-backend-performance`

**目标：** 保留 Java 8 + Feat + mica-voice + MyBatis 技术栈，先在单进程内把 WS、ASR、LLM、数据库解耦，再通过统一 Provider 支持 `in_process` / `gateway` 环境变量切换。

**验收基线：** 默认按个人版容量设计：1 个活跃会话、4 个并发压力会话。记录服务自身排队/处理耗时与外部 ASR/LLM 耗时，避免把供应商延迟误判为 Java 性能问题。

## B0. 建立基线和行为契约

- 为 [`CopilotWs.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java)、[`AsrWebSocketUpgrade.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/AsrWebSocketUpgrade.java) 和 [`LlmAnswer.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/svc/LlmAnswer.java) 增加特征测试，锁定事件顺序、候选人语音不触发回答、短问句合并、取消、断连和过载语义。
- 新增模块内性能探针与 PowerShell 脚本，固定 PCM、模拟流式 LLM，并分别测量 1/2/4 并发：WS 回调耗时、ASR 队列等待、final、LLM 首字节、首 delta、完成、DB 事务、线程和 native stream 数量。
- 基线门槛：WS 二进制回调 P95 ≤ 5ms/P99 ≤ 20ms；非 LLM API 本机 P95 ≤ 150ms；队列过载 50ms 内显式返回；30 分钟运行无持续资源增长。

## B1. 隔离 WebSocket、ASR 和出站发送

- 新增 `concurrent/BoundedExecutor.java`、`concurrent/SerialDispatcher.java` 和 `web/WsEventSink.java`；修改 [`InterviewServerApplication.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/InterviewServerApplication.java) 与 [`CopilotWs.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java)。
- 删除所有 `CallerRunsPolicy`：队列满时返回稳定错误码并记录拒绝指标，绝不把 LLM/准备包任务回落到 HTTP/WS I/O 线程。
- 抽象 `asr/AsrProvider.java`、`AsrSession.java`、`AsrResult.java`，先实现 `InProcessAsrProvider`。WS 回调只做校验和入队；每会话串行 mailbox，共享有界 worker，关闭/暂停必须停止接收帧并释放 native stream。
- 出站事件按连接串行发送；partial 可合并为最新值，final、error、completed 不得静默丢失。

## B2. 真流式 LLM 与统一话轮协调

- 新增 `svc/StreamingLlmClient.java`、`svc/AnswerStreamParser.java`、`svc/CopilotAnswerService.java`，让 [`Llm.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/svc/Llm.java) 的流回调直接产生安全 NDJSON 事件，不再等完整 JSON 后一次性发送。
- 事件语义固定为：`answer_queued` 立即反馈；`answer_started` 在生成真正开始时发送；`answer_delta` 按供应商增量批量透传；`answer_completed` 仅在结果成功落库后发送。每轮携带 `turn_id`、`generation_id`，新问题、超时、结束或断连后屏蔽迟到事件。
- 先复用 Feat 流式回调；若 soak test 证明超时连接无法释放，再在该接口下引入可取消的 Java 8 HTTP 实现，避免无证据替换依赖。
- 新增 `copilot/UtteranceAssembler.java`、`SpeakerPolicy.java`、`CopilotCoordinator.java`、`GenerationRegistry.java`；[`TranscriptRules.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/TranscriptRules.java) 只处理原始帧去重，语义层负责短确认词、问句前缀、连续 final 合并和说话人判断。
- 候选人语音只展示；面试官完整问题才创建明确 `turnId` 并触发一次回答，禁止再用「最新 turn」推断写入目标。

## B3. 数据热路径和所有 LLM 任务异步化

- 修改 [`MyBatis.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/db/MyBatis.java)、[`CopilotDao.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/db/CopilotDao.java) 和 mapper：启动时设置 WAL/`synchronous=NORMAL`/busy timeout；会话启动时一次加载岗位与简历上下文；创建 turn、完成 turn 使用短事务和明确 ID。
- 先测量再决定是否引入连接池；SQLite 优先采用短事务和单写入调度，避免盲目扩大连接数造成锁竞争。目标为 4 会话下 `SQLITE_BUSY=0`、Copilot 写事务 P95 ≤ 20ms。
- 将 [`PlanRoutes.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/web/PlanRoutes.java)、[`MockRoutes.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/web/MockRoutes.java) 和简历分析统一为有界异步任务：提交接口快速返回任务 ID，状态接口提供 queued/running/completed/failed/cancelled，前端使用退避轮询或流式状态，不再长期占用 HTTP 请求线程。

## B4. 恢复、观测和阶段一验收

- 新增 `copilot/CopilotSessionRuntime.java` 与注册表，状态覆盖 idle/running/paused/reconnecting/ending/ended/error；结束时等待最后 final 和当前 turn 落库，设定硬超时。
- 音频协议采用兼容迁移：保留旧裸 PCM，同时增加含 version/source/sequence 的 v2 帧与 ACK；断连保留短恢复窗口，重复序列幂等丢弃。
- 增加 `/api/ready`、活动连接/ASR/LLM 数、队列深度、拒绝/取消原因、各阶段直方图。日志只记录 ID、长度、耗时和错误码，移除问题、简历及回答正文。
- 运行 JUnit、模拟 LLM 基线、真实 ASR/LLM A/B 和 30 分钟 soak；阶段一达标后再进入 B5。

## B5. 可切换独立 ASR Gateway

- 新增 `GatewayAsrProvider.java` 与协议客户端；在 [`AppConfig.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/AppConfig.java) 增加 `ASR_PROVIDER=in_process|gateway`、URL、超时和重连配置。
- [`InterviewServerApplication.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/InterviewServerApplication.java) 只在 `in_process` 模式验证并加载本地模型；gateway 不可用时 readiness 失败，但业务层、话轮、DB 和 LLM 不复制。
- 用同一 PCM 做 Provider 合约测试和 A/B；切换 Provider 不改变浏览器事件与数据库结构，可仅通过环境变量回滚。

---

# 计划二：整体 UI/UX 与页面流畅度

**模拟分支：** `feature/interview-server-ui-refresh`

**视觉方向：** `Quiet Command / 可信作战台`。浅色用于准备、阅读和复盘，深色用于用户主动选择及实时专注；主色采用深海蓝绿，突出岗位档案、行动清单和事实来源，移除紫粉 AI 渐变、大面积玻璃和装饰性发光。

## U0. 建立 UI 基线和页面清单

- 先新增内部 `ui-baseline` 样板页，覆盖按钮、字段、状态、列表、空态、骨架、错误、Dialog、Drawer、转写和回答卡片的深浅主题、长中文、键盘和 reduced-motion 状态；样板未通过不迁移业务页。
- 建立 Java 模块专用 Playwright/静态契约测试，记录 360×800、390×844、768×1024、1024×768、1280×720、1440×900、1920×1080 基线截图；根目录 Flask 测试不作为 Java UI 覆盖。

## U1. 重建设计 Token、组件和应用壳

- 重构 [`tokens.css`](../../../services/interview-server/src/main/resources/static/static/css/tokens.css)、[`components.css`](../../../services/interview-server/src/main/resources/static/static/css/components.css)、[`app-shell.css`](../../../services/interview-server/src/main/resources/static/static/css/app-shell.css) 和 [`layouts/app.html`](../../../services/interview-server/src/main/resources/templates/layouts/app.html)。
- 统一语义色、Noto Sans SC/系统回退、12–48px 字阶、8px 间距、4/8/12/16px 圆角、120/180/240ms 动效和 z-index；普通容器以边框分层，仅浮层使用阴影。
- 补齐当前模板已使用但未定义的 `workspace-cards`、`plan-card`、`list-item`、`descriptions`、`accordion`、`upload-field`、`toast-region` 等组件，统一 loading/disabled/focus/error/success 状态。
- 自托管统一 SVG 图标，移除 Google Fonts 与 Boxicons CDN 阻塞；主题脚本在首屏样式前初始化，避免主题闪烁。
- 应用壳采用「侧栏 + 顶栏 + 页面头 + 上下文条 + 主内容」，每页只保留一个主 CTA；移动端侧栏变可访问 Drawer。

## U2. 先迁移公开页与核心准备流程

- 第一批：[`common/index.html`](../../../services/interview-server/src/main/resources/templates/common/index.html)、登录、注册、[`workspace.html`](../../../services/interview-server/src/main/resources/templates/applicant/workspace.html) 和面试计划列表。首页只表达个人求职产品；注册首屏仅保留必要账号字段。
- 第二批：计划详情、简历中心、知识库、简历优化。为长任务增加骨架、进度、退避轮询、失败重试和来源证据；危险操作统一确认 Dialog。
- 将各页内联样式和业务脚本逐页拆到可缓存的 `static/css/pages/*.css` 与 `static/js/*.js`，不迁移 React/Tailwind，不一次性重写全部页面。

## U3. 拆分专注工作区

- 新增 `templates/layouts/focus.html`、独立 `applicant/copilot.html` 与 `applicant/mock-interview.html`，修改 [`PageRoutes.java`](../../../services/interview-server/src/main/java/com/aiinterview/server/web/PageRoutes.java)；旧 `/applicant/interview-workspace` 保留一个发布周期作为兼容重定向。
- Copilot 桌面端使用转写 58% / 回答 42% 双栏；小于 1180px 上下布局，小于 768px 通过「转写/回答」切换，回答区成为底部面板。首次进入只做设备检查，用户点击后才创建会话和申请权限。
- 模拟面试保留题目总进度、历史回答和评分；评分不会自动消失，用户明确点击进入下一题。

## U4. 优化实时页面主线程与音频链路

- 新增 `static/js/audio/pcm16-worklet.js` 和采集控制器，在音频线程完成连续重采样、PCM16 转换和 100ms 分帧；使用预分配环形缓冲与 transferable `ArrayBuffer`，仅在不支持时回退 `ScriptProcessor` 并显示降级状态。
- 麦克风使用 `getUserMedia`，系统声音使用 `getDisplayMedia`，混合使用 Web Audio mixer；暂停时停止上行，切换模式/离开页面/结束时完整释放 track、node、AudioContext 和 socket。
- 新增 `realtime/transcript-buffer.js`：按 session/turn/version 丢弃迟到 partial，使用重叠合并保持文本单调；WS 消息通过 `requestAnimationFrame` 批处理，partial ≤10Hz、answer delta ≤20Hz。
- 仅在用户距底部 48px 内自动跟随；向上阅读时显示「有 N 条新内容」，不强制滚动。限制活跃 DOM，监控 `WebSocket.bufferedAmount` 并对音频队列设置 500ms 上限。
- 实现 idle/permission/connecting/ready/recording/paused/reconnecting/ending/ended/error 状态机，错误必须给出恢复操作；`aria-live` 只播报 final、连接和错误，避免 partial 持续打断读屏。

## U5. 复盘闭环、设置与发布硬化

- Copilot/模拟结束后提供「生成复盘、返回计划、再练一次」，接通复盘生成；完善复盘筛选、事实风险、来源和下一步行动。
- 修复设置页字段映射、录音计时和资源清理；清理旧占位 [`index.html`](../../../services/interview-server/src/main/resources/static/index.html) 前先确认无消费者。
- 发布门槛：Axe critical/serious 为 0；所有操作可键盘完成；普通文字对比度 ≥4.5:1；触控目标 ≥44×44px；200% 缩放和 320px 重排可用；无横向滚动、控制台错误或失败静态资源。
- 性能预算：移动 4G p75 FCP ≤1.8s、LCP ≤2.5s、CLS ≤0.10、INP ≤200ms；实时工作区稳态 ≥55fps、事件到 partial 可见 P95 ≤100ms、无 >50ms 长任务、30 分钟 heap 增长 <20MB。

---

## 协同验收

- 后端 B2 冻结事件 schema 后，前端 U4 才接入；新增字段向后兼容一个发布周期。
- 后端先用模拟客户端验收协议，前端再用模拟 WS 验收渲染，最后运行真实 ASR + LLM 端到端测试，以便定位问题属于服务、网络还是浏览器。
- 两份计划分别汇报变更摘要、测试证据和 `README_PLAN.md` 记录，并分别等待用户确认后模拟合并。

---

## 相关文档

- [2026-08-24 interview-server 优化计划（历史）](./2026-08-24-interview-server-optimization-plan.md)
- [2026-08-23 Feat 集成服务计划](./2026-08-23-feat-integration-server-plan.md)
