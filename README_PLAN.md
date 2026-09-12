# 项目开发记录

> 本文件由开发助手维护，记录功能迭代、需求变更与实现细节。

## 项目信息

- **创建日期**：2026-08-29
- **技术栈**：Java 8（Feat + mica-voice + MyBatis/SQLite + Thymeleaf + Vue3）；Python Flask 为参考实现
- **主分支**：master / main（模拟分支合并前需用户确认）

## 已实现功能清单

- [x] interview-server 单进程 ASR + Copilot WebSocket + LLM 回答
- [x] Flask 侧 Copilot 独立布局（standalone）与前端测试
- [ ] interview-server 后端性能优化（B0–B5，进行中）
- [ ] interview-server 整体 UI 刷新（U0–U5，待开始）

## 注意事项

- 实施 interview-server 优化时不得覆盖工作区内已有 Java 改动，需逐块合并
- 后端 B2 冻结 WS 事件 schema 后，前端 U4 再接入实时工作区
- 根目录 Python 应用仅作行为参考，Java 版为独立正式产品目标

## 工作流版本记录

- v1.0 - 2026-08-29 - 初始工作流规则与 README_PLAN 建立

## 开发记录

---
## 记录 1 - 2026-08-29

### 【本次功能】
建立 interview-server 后端/UI 双优化计划文档；提交 Copilot standalone 布局与转写/LLM 日志等残留改动。

### 【参考文件】
- `docs/superpowers/plans/2026-08-29-interview-server-dual-optimization-plan.md` — 双计划主文档
- `templates/layouts/standalone.html` — Copilot 轻量布局
- `services/interview-server/src/main/java/com/aiinterview/server/svc/Llm.java` — LLM 请求/响应日志

### 【差距总结】
- 预期：按计划分阶段落地后端与 UI 优化
- 实际：仅完成计划文档与现场提交，B0 基线任务进行中
- 原因：按用户要求先提交残留再逐步执行

---
## 记录 2 - 2026-08-29

### 【本次功能】
完成后端优化 B0：初始化项目记录，提取 Copilot 来源/说话人策略为可测纯函数，并补充 LlmAnswer 与 Copilot WS 控制帧契约测试。

### 【参考文件】
- `README_PLAN.md` — 项目记录初始化
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWsPolicy.java` — 采集来源与说话人策略
- `services/interview-server/src/test/java/com/aiinterview/server/web/CopilotWsPolicyTest.java` — 策略契约测试
- `services/interview-server/src/test/java/com/aiinterview/server/svc/LlmAnswerTest.java` — 回答 JSON 解析测试
- `services/interview-server/src/test/java/com/aiinterview/server/web/CopilotWsControlTest.java` — WS 握手/错误/会话校验测试
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java` — 委托 CopilotWsPolicy

### 【差距总结】
- 预期：B0 含性能基线脚本与 Utterance 特征测试
- 实际：已完成 README、策略抽取与核心契约测试；性能脚本与 UtteranceAssembler 留待后续 B0 续项或 B1/B2
- 原因：按「一步一提交」先交付可编译、可测的最小 B0 切片

---
## 记录 3 - 2026-08-29

### 【本次功能】
完成后端优化 B1：引入 BoundedExecutor 与 WsEventSink，ASR 解码移出 WebSocket I/O 线程，移除 CallerRunsPolicy。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/concurrent/BoundedExecutor.java` — 有界线程池
- `services/interview-server/src/main/java/com/aiinterview/server/asr/InProcessAsrProvider.java` — 异步 ASR 会话
- `services/interview-server/src/main/java/com/aiinterview/server/web/WsEventSink.java` — 串行 WS 出站
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java` — 接入异步 ASR 与拒绝策略
- `services/interview-server/src/main/java/com/aiinterview/server/web/PlanRoutes.java` — 准备包任务改用 BoundedExecutor

### 【差距总结】
- 预期：B1 同时改造 AsrWebSocketUpgrade 与性能指标采集
- 实际：已完成 Copilot 链路与线程池隔离；独立 ASR 网关 WS 仍同步 decode
- 原因：优先解除 Copilot 热路径阻塞，网关路径留待 B5 或后续补丁

---
## 记录 4 - 2026-08-29

### 【本次功能】
完成后端优化 B2：LLM 真流式 NDJSON 推送、话轮过滤与 generation 屏蔽，Copilot 仅在面试官完整问句后触发回答。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/svc/CopilotAnswerService.java` — 流式回答与 delta 分片
- `services/interview-server/src/main/java/com/aiinterview/server/svc/AnswerStreamParser.java` — NDJSON 行解析
- `services/interview-server/src/main/java/com/aiinterview/server/svc/Llm.java` — 新增 streamChat
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/UtteranceAssembler.java` — 话轮边界判定
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/GenerationRegistry.java` — generation 取消与屏蔽
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java` — 接入 utterance_completed / answer_queued / 流式事件

### 【差距总结】
- 预期：B2 同时改造 LlmAnswer HTTP 路径与完整 fact guard
- 实际：Copilot WS 链路已流式化；LlmAnswer 非流式路径保留兼容；fact guard 未移植
- 原因：优先对齐 Python Copilot 体感，守卫逻辑留待后续

---
## 记录 5 - 2026-08-29

### 【本次功能】
完成后端优化 B3：整合 SQLite PRAGMA（WAL + synchronous=NORMAL）、会话启动时缓存岗位/简历上下文、turn 创建与转写同事务化，Copilot 写库经单线程 DbWriteExecutor 串行调度。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/db/SqlitePragmas.java` — 连接级 PRAGMA
- `services/interview-server/src/main/java/com/aiinterview/server/db/DbWriteExecutor.java` — SQLite 写队列
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/CopilotSessionContext.java` — 会话上下文缓存
- `services/interview-server/src/main/java/com/aiinterview/server/db/CopilotDao.java` — beginTurn / completeTurnAsync
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java` — 启动加载上下文、事务化 turn

### 【差距总结】
- 预期：B3 同时异步化 MockRoutes 评估与 PlanRoutes 轮询
- 实际：已完成 Copilot 热路径 SQLite 与上下文优化；MockRoutes 仍同步 LLM
- 原因：优先消除 Copilot 重复查库与 latest turn 查询，Mock 异步留待 B3 续项或 B4

---
## 记录 6 - 2026-08-29

### 【本次功能】
启动后端优化 B4（切片 1）：新增 `/api/ready` 与 `/api/metrics` 探针，注册各 BoundedExecutor 队列指标，LLM 日志脱敏（仅记录字数/延迟），Copilot 连接数计数。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/web/ReadyRoutes.java` — ready/metrics 路由
- `services/interview-server/src/main/java/com/aiinterview/server/observability/ServerMetrics.java` — 指标快照
- `services/interview-server/src/main/java/com/aiinterview/server/db/Db.java` — ping()
- `services/interview-server/src/main/java/com/aiinterview/server/AsrEngine.java` — isReady()
- `services/interview-server/src/main/java/com/aiinterview/server/svc/Llm.java` — 日志脱敏

### 【差距总结】
- 预期：B4 含 CopilotSessionRuntime 恢复状态机、v2 音频帧与 soak 压测
- 实际：已完成就绪/指标探针与日志脱敏基础
- 原因：按一步一提交先交付可观测性最小切片

---
## 记录 7 - 2026-08-29

### 【本次功能】
完成后端优化 B4（切片 2）：引入 CopilotSessionRuntime 状态机与注册表，支持 30s 断连恢复（保留话轮/转写版本）、暂停拒收 PCM、结束排水等待在途回答落库（5s 硬超时）。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/CopilotSessionState.java` — 状态枚举
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/CopilotSessionRuntime.java` — 运行时状态机
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/CopilotSessionRegistry.java` — 进程内注册表
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java` — 接入 reconnected/ending 事件与排水

### 【差距总结】
- 预期：B4 含 v2 音频帧 ACK、序列幂等与 soak 压测
- 实际：已完成会话恢复状态机与结束排水；前端尚未处理 `reconnected`/`ending` 事件
- 原因：优先后端恢复语义，UI 适配留待 U4

---
## 记录 8 - 2026-08-29

### 【本次功能】
完成后端优化 B4（切片 3）：Copilot v2 音频帧协议（AI 头 + source + sequence + PCM），保留 v1 裸 PCM 兼容；重复序列幂等丢弃并回 ACK；工作区前端发送 v2 帧并处理 reconnected/ending。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/CopilotAudioFrame.java` — v1/v2 帧解析
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/CopilotSessionRuntime.java` — 音频序列跟踪
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java` — audio_ack 与协议协商
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — v2 发送与重连 UI

### 【差距总结】
- 预期：B4 含 soak 压测与阶段一直方图指标
- 实际：已完成 v2 音频协议与前端对接；soak/直方图留待 B4 收尾
- 原因：按一步一提交优先打通协议闭环

---
## 记录 9 - 2026-08-29

### 【本次功能】
完成后端优化 B4（切片 4）：`/api/metrics` 增加 Copilot 管道计数器（音频接受/重复、回答完成/失败/拒绝），便于阶段一观测与后续 soak 对照。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/observability/ServerMetrics.java` — incrementCounter + counters 快照
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java` — 埋点接入

### 【差距总结】
- 预期：B4 含各阶段延迟直方图与 30 分钟 soak 脚本
- 实际：已完成事件计数器；直方图与 soak 留待 B4 收尾或 B5 前验收
- 原因：先补齐 /api/metrics 可观测最小集

---
## 记录 10 - 2026-08-29

### 【本次功能】
完成后端优化 B5：支持 `ASR_PROVIDER=in_process|gateway` 切换；新增 GatewayAsrProvider（Java-WebSocket 客户端转发 PCM）、AsrProviders 工厂与 AsrReadiness 探针；gateway 模式跳过本地模型校验/预热。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/AppConfig.java` — ASR_PROVIDER / ASR_GATEWAY_URL 等
- `services/interview-server/src/main/java/com/aiinterview/server/asr/GatewayAsrProvider.java` — 远程网关 ASR
- `services/interview-server/src/main/java/com/aiinterview/server/asr/GatewayAsrProtocol.java` — 网关 JSON 解析
- `services/interview-server/src/main/java/com/aiinterview/server/asr/AsrProviders.java` — Provider 工厂
- `services/interview-server/src/main/java/com/aiinterview/server/asr/AsrReadiness.java` — /api/ready ASR 探针
- `services/interview-server/src/main/java/com/aiinterview/server/InterviewServerApplication.java` — 条件启动与 Copilot 注入

### 【差距总结】
- 预期：B5 含同一 PCM 的 Provider 合约 A/B 与 gateway 不可用时自动降级
- 实际：已完成环境变量切换、协议客户端与 readiness；无自动降级（需手动切回 in_process）
- 原因：优先交付可切换架构，A/B 脚本留待验收阶段

---
## 记录 11 - 2026-08-29

### 【本次功能】
启动 UI 优化 U0：新增内部 `/internal/ui-baseline` 组件样板页（按钮/表单/状态/空态/骨架/Dialog/Drawer/转写回答卡），并建立 Java 模块页面清单文档。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/internal/ui_baseline.html` — 样板页
- `services/interview-server/src/main/resources/static/static/css/pages/ui-baseline.css` — 样板布局与 skeleton
- `services/interview-server/src/main/resources/static/static/js/ui-baseline.js` — Dialog/Drawer 演示
- `services/interview-server/docs/ui-baseline-pages.md` — 页面清单与迁移状态
- `services/interview-server/src/main/java/com/aiinterview/server/web/PageRoutes.java` — 路由注册

### 【差距总结】
- 预期：U0 含 Playwright 七视口截图基线
- 实际：已完成样板页与页面 inventory；Playwright 截图流水线留待 U0 续项
- 原因：按一步一提交先交付可浏览、可对照的组件基线

---
## 记录 12 - 2026-08-29

### 【本次功能】
启动 UI 优化 U1（切片 1）：将 `tokens.css` 品牌色切换为 Quiet Command 深海蓝绿，移除紫粉 AI 渐变，统一深浅主题 focus/glow。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/css/tokens.css` — 设计令牌主色与背景微调

### 【差距总结】
- 预期：U1 同时重构 components.css、app-shell 与自托管图标
- 实际：仅完成 tokens 色彩体系第一步
- 原因：按一步一提交，先确立全站色彩方向

---
## 记录 13 - 2026-08-29

### 【本次功能】
完成 UI 优化 U1（切片 2）：补齐 components.css 缺失组件（list/plan-card/descriptions/accordion/upload/toast/skeleton），app-shell 实底顶栏与导航指示，工作区移除紫粉硬编码。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/css/components.css` — 业务页共用组件
- `services/interview-server/src/main/resources/static/static/css/app-shell.css` — Quiet Command 外壳
- `services/interview-server/src/main/resources/static/static/css/pages/interview-workspace.css` — 品牌色对齐
- `services/interview-server/src/main/resources/templates/internal/ui_baseline.html` — 样板页补充卡片/列表

### 【差距总结】
- 预期：U1 含自托管图标与 landing/auth 页渐变清理
- 实际：已完成 components + app-shell + 工作区色彩；CDN 字体/图标仍保留
- 原因：优先修复模板已引用但缺失的组件样式

---
## 记录 14 - 2026-08-29

### 【本次功能】
完成 UI 优化 U1（切片 3）：landing/auth 公开页背景径向渐变移除紫粉硬编码，统一为 `var(--primary)` / `var(--accent)` 的 `color-mix` 表达。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/css/landing.css` — hero 与 CTA 区背景
- `services/interview-server/src/main/resources/static/static/css/auth.css` — 登录/注册页背景

### 【差距总结】
- 预期：U1 含自托管 Boxicons/Google Fonts 与主题防闪烁脚本
- 实际：已完成全站 CSS 紫粉色清理；CDN 字体/图标仍保留
- 原因：先完成色彩一致性，CDN 自托管作为 U1 切片 4

---
## 记录 15 - 2026-08-29

### 【本次功能】
完成 UI 优化 U1（切片 4）：自托管 Manrope/Noto Sans SC 字体与 Boxicons 2.1.4，移除 Google Fonts/jsDelivr CDN 依赖；`<head>` 内联主题 boot 脚本防止深浅色切换闪烁。

### 【参考文件】
- `services/interview-server/src/main/resources/static/vendor/fonts/fonts.css` — 本地 @font-face
- `services/interview-server/src/main/resources/static/vendor/boxicons/` — 图标字体与 CSS
- `services/interview-server/src/main/resources/templates/fragments/vendor-head.html` — Thymeleaf 共用 vendor 片段
- `services/interview-server/src/main/resources/templates/layouts/app.html` — 应用壳引用 vendor 片段
- `services/interview-server/src/main/resources/templates/common/index.html` — 首页自托管资源
- `services/interview-server/src/main/resources/templates/common/login.html` — 登录页自托管资源
- `services/interview-server/src/main/resources/templates/common/register.html` — 注册页自托管资源

### 【差距总结】
- 预期：U1 全部完成，进入 U2 页面迁移
- 实际：U1 设计令牌/组件/色彩/CDN 自托管已完成；U0 Playwright 截图基线仍待补
- 原因：按一步一提交优先消除外部 CDN 与主题闪烁

---
## 记录 16 - 2026-08-29

### 【本次功能】
启动 UI 优化 U2（切片 1）：工作台与面试计划页对齐 U0 基线，使用 `page-header`、`plan-card`、`workspace-cards`、`.status` 准备状态徽章，移除 interview_plans 内联样式与硬编码 `data-theme`。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/workspace.html` — 工作台页头与计划卡片
- `services/interview-server/src/main/resources/templates/applicant/interview_plans.html` — Vue 列表迁移至 plan-card
- `services/interview-server/src/main/resources/static/static/css/components.css` — plan-card-head/desc、spin-wrap、generating 状态
- `services/interview-server/docs/ui-baseline-pages.md` — 迁移状态更新

### 【差距总结】
- 预期：U2 含计划详情、简历/知识库等全部准备资产页
- 实际：已完成 workspace + interview-plans 两页；详情页仍 pending
- 原因：按一步一提交，先完成最高频入口

---
## 记录 17 - 2026-08-29

### 【本次功能】
完成 UI 优化 U2（切片 2）：面试计划详情页对齐设计体系，使用 `page-header`、`descriptions`、`section-header`、`accordion-stack`，移除内联样式与硬编码主题，操作按钮改用 Boxicons。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/interview_plan_detail.html` — 详情页 Vue 模板重构
- `services/interview-server/src/main/resources/static/static/css/components.css` — page-actions/section-header/surface-pad 等布局类
- `services/interview-server/docs/ui-baseline-pages.md` — 详情页状态 baseline

### 【差距总结】
- 预期：U2 含简历中心、知识库等准备资产页
- 实际：计划三页（工作台/列表/详情）已完成 baseline 对齐
- 原因：按一步一提交，优先完成计划闭环路径

---
## 记录 18 - 2026-08-29

### 【本次功能】
完成 UI 优化 U2（切片 3）：简历中心页对齐设计体系，使用 `page-header`、`upload-field`、`list-item`、`.status` 分析状态徽章，移除内联样式与硬编码主题。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/resume_manage.html` — 简历列表与上传区重构
- `services/interview-server/src/main/resources/static/static/css/components.css` — tag-list、list-item-subtitle 等列表辅助类
- `services/interview-server/docs/ui-baseline-pages.md` — 简历中心 baseline

### 【差距总结】
- 预期：U2 含知识库、简历优化等全部准备资产页
- 实际：已完成简历中心；知识库仍 pending
- 原因：按一步一提交，先完成简历上传/解析入口

---
## 记录 19 - 2026-08-29

### 【本次功能】
完成 UI 优化 U2（切片 4）：知识库页对齐设计体系，使用 `page-header`、`page-toolbar`、`list-item`、Dialog 表单，移除内联样式与硬编码主题。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/knowledge.html` — 搜索/列表/新建 Dialog 重构
- `services/interview-server/docs/ui-baseline-pages.md` — 知识库 baseline

### 【差距总结】
- 预期：U2 含简历优化、复盘列表等剩余业务页
- 实际：准备资产两页（简历中心 + 知识库）已完成；复盘/设置仍 pending
- 原因：按一步一提交，先完成准备资产录入路径

---
## 记录 20 - 2026-08-29

### 【本次功能】
完成 UI 优化 U2（切片 5）：复盘列表页对齐设计体系，使用 `page-header` 与 `list-item`，移除内联样式与硬编码主题。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/reviews.html` — 复盘列表重构
- `services/interview-server/docs/ui-baseline-pages.md` — 复盘列表 baseline

### 【差距总结】
- 预期：U2 含复盘详情、简历优化等剩余页
- 实际：复盘列表已完成；详情页仍 pending
- 原因：按一步一提交，先完成复盘入口列表

---
## 记录 21 - 2026-08-29

### 【本次功能】
完成 UI 优化 U2（切片 6）：复盘详情与简历优化页对齐设计体系，补充 `divider`、`gap-card`、`action-list` 等内容区组件，移除内联样式与硬编码主题。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/review_detail.html` — 复盘详情重构
- `services/interview-server/src/main/resources/templates/applicant/resume_optimize.html` — 简历优化重构
- `services/interview-server/src/main/resources/static/static/css/components.css` — 内容区辅助类与 info alert
- `services/interview-server/docs/ui-baseline-pages.md` — 复盘详情/简历优化 baseline

### 【差距总结】
- 预期：U2 含公开页（首页/登录/注册）与个人设置页
- 实际：工作台内业务页（除 profile/settings）均已 baseline；公开页仍 pending
- 原因：按一步一提交，先完成复盘闭环与简历优化路径

---
## 记录 22 - 2026-08-29

### 【本次功能】
完成 UI 优化 U2（切片 7）：个人设置页对齐设计体系，增加 `page-header`，表单双列网格与语音状态改用 `.status` 徽章，语音操作按钮统一 `button-sm`。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/profile.html` — 设置页结构优化
- `services/interview-server/src/main/resources/static/static/css/pages/settings.css` — settings-form-fields 网格与 voice 状态精简
- `services/interview-server/src/main/resources/static/static/js/voice-profile.js` — 状态徽章 data-state 映射
- `services/interview-server/docs/ui-baseline-pages.md` — profile baseline

### 【差距总结】
- 预期：U2 含公开页 head 统一与首页验收
- 实际：应用内设置页已完成；公开页仍标记 pending
- 原因：按一步一提交，先完成登录后设置路径

---
## 记录 23 - 2026-08-29

### 【本次功能】
完成 UI 优化 U2（切片 8）：公开页（首页/登录/注册）统一引用 `fragments/vendor-head` 片段，消除重复的 theme boot 与 vendor link 声明；页面清单标记 baseline。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/common/index.html` — vendor 片段引用
- `services/interview-server/src/main/resources/templates/common/login.html` — vendor 片段引用
- `services/interview-server/src/main/resources/templates/common/register.html` — vendor 片段引用
- `services/interview-server/docs/ui-baseline-pages.md` — 公开页 baseline

### 【差距总结】
- 预期：U2 全部完成，进入 U3 专注工作区
- 实际：除 `interview_workspace`（partial）外，U2 页面清单均已 baseline
- 原因：Copilot 工作区属 U3 范围，公开页先完成 head 统一

---
## 记录 24 - 2026-08-29

### 【本次功能】
启动 UI 优化 U3（切片 1）：面试工作区启用 `app-shell--standalone` 专注外壳（隐藏侧栏），移除硬编码主题；模拟面试/mock 抽屉区去除全部内联样式，评分反馈改用 Boxicons。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/layouts/app.html` — interview/mock 页自动 standalone
- `services/interview-server/src/main/resources/static/static/css/app-shell.css` — standalone 隐藏侧栏
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — mock/抽屉模板清理
- `services/interview-server/src/main/resources/static/static/css/pages/interview-workspace.css` — mock-workbench 等布局类
- `services/interview-server/docs/ui-baseline-pages.md` — 工作区迁移进度

### 【差距总结】
- 预期：U3 含 Copilot/模拟模式 JS 拆分与 AudioWorklet
- 实际：已完成专注外壳与 mock 区样式化；Copilot 实时逻辑未动
- 原因：按一步一提交，先交付可见的布局专注态

---
## 记录 25 - 2026-08-29

### 【本次功能】
完成 UI 优化 U3（切片 2）：模拟面试模式增加与 Copilot 一致的 `copilot-context-bar` 上下文栏与玻璃风 `mock-workbench` 外壳，状态改用 `.status` 徽章。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — mock 上下文栏
- `services/interview-server/src/main/resources/static/static/css/pages/interview-workspace.css` — mock-workbench 玻璃容器样式

### 【差距总结】
- 预期：U3 含 Copilot/模拟 JS 模块拆分
- 实际：mock 模式视觉与 Copilot 外壳对齐；Copilot 区逻辑仍内联于模板
- 原因：按一步一提交，先统一双模式上下文体验

---
## 记录 26 - 2026-08-29

### 【本次功能】
完成 UI 优化 U3（切片 3）：将 `interview_workspace.html` 内联 Vue 模板与 setup 逻辑外提——模板保留于 `#interview-workspace-template`，逻辑迁移至 `static/js/interview-workspace.js`，行为不变。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — 移除内联脚本，引用外部 JS
- `services/interview-server/src/main/resources/static/static/js/interview-workspace.js` — 新建，IIFE + DOMContentLoaded 挂载
- `services/interview-server/docs/ui-baseline-pages.md` — 更新工作区备注

### 【差距总结】
- 预期：U3 含 Copilot/模拟 JS 进一步模块化拆分
- 实际：完成 Vue 逻辑与模板分离，Copilot WS/音频逻辑仍同文件
- 原因：按一步一提交，先消除 600+ 行内联脚本便于后续切片

---
## 记录 27 - 2026-08-29

### 【本次功能】
完成 UI 优化 U3（切片 4）：将 Copilot WebSocket、PCM 重采样采集与会话生命周期从 `interview-workspace.js` 拆至独立模块 `copilot-session.js`（`window.CopilotSession.create`），工作区仅保留模式切换、抽屉/全屏与模拟面试逻辑。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — 新建 Copilot 会话模块
- `services/interview-server/src/main/resources/static/static/js/interview-workspace.js` — 注入 CopilotSession，精简 setup
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — 增加 copilot-session.js 引用

### 【差距总结】
- 预期：U3 含模拟面试逻辑外提及连接状态 UI 统一
- 实际：Copilot 实时链路已模块化；mock 逻辑仍内联于 interview-workspace.js
- 原因：按一步一提交，优先隔离 WS/音频热路径便于 U4 AudioWorklet 迁移

---
## 记录 28 - 2026-08-29

### 【本次功能】
完成 UI 优化 U3（切片 5）：将模拟面试 REST 流程（startMock/loadQuestion/submitAnswer/endMock）从 `interview-workspace.js` 外提至 `mock-interview.js`（`window.MockInterview.create`），工作区 setup 进一步精简为壳层编排。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/mock-interview.js` — 新建模拟面试模块
- `services/interview-server/src/main/resources/static/static/js/interview-workspace.js` — 注入 MockInterview
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — 增加 mock-interview.js 引用

### 【差距总结】
- 预期：U3 含连接状态 UI 统一为设计系统 `.status`
- 实际：双模式 JS 均已模块化；Copilot 状态仍用 `.copilot-state` 局部样式
- 原因：按一步一提交，先完成 mock 逻辑外提

---
## 记录 29 - 2026-08-29

### 【本次功能】
完成 UI 优化 U3（切片 6）：Copilot 上下文栏与转写面板状态徽章由局部 `.copilot-state` 迁移为设计系统 `.status` 组件；`components.css` 补充 `idle`/`connecting` 状态 token，移除 interview-workspace 重复样式。

### 【参考文件】
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — 状态徽章改用 `.status`
- `services/interview-server/src/main/resources/static/static/css/components.css` — 新增 idle/connecting 状态
- `services/interview-server/src/main/resources/static/static/css/pages/interview-workspace.css` — 删除 copilot-state 块

### 【差距总结】
- 预期：U3 全部完成并进入 U4 AudioWorklet
- 实际：U3 模块化与状态 UI 对齐已完成，工作区页面标记 baseline
- 原因：按一步一提交，U4 音频管线迁移留待下一阶段

---
## 记录 30 - 2026-08-29

### 【本次功能】
完成 UI 优化 U4（切片 1）：Copilot 音频采集由 deprecated `ScriptProcessor` 迁移为 `AudioWorklet`（`pcm16-worklet.js`），在音频线程完成线性插值重采样与 100ms PCM16 分帧；不支持或模块加载失败时回退 ScriptProcessor 并在 `captureStatus` 标注降级。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/audio/pcm16-worklet.js` — 新建 PCM16 采集 Worklet
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — Worklet 优先 + 降级路径
- `services/interview-server/src/main/resources/static/static/js/interview-workspace.js` — 暴露 captureBackend

### 【差距总结】
- 预期：U4 含 transcript 批处理与完整连接状态机
- 实际：音频链路已上 Worklet；转写渲染批处理与状态机待续
- 原因：按一步一提交，先完成主线程音频热路径卸载

---
## 记录 31 - 2026-08-29

### 【本次功能】
完成 UI 优化 U4（切片 2）：新增 `copilot-conn-state.js` 统一连接状态标签与 `.status` kind 映射；`copilot-session.js` 经 `setConnState` 集中切换；采集降级时 `capture-source-status` 显示 warning 态。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/copilot-conn-state.js` — 连接状态常量与 kindFor
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — setConnState 重构
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — 引入 conn-state 脚本、降级 warning
- `services/interview-server/src/main/resources/static/static/css/pages/interview-workspace.css` — warning 采集状态色

### 【差距总结】
- 预期：U4 完整 idle→recording→reconnecting 状态机与 transcript 批处理
- 实际：连接状态标签已集中管理；WS 事件驱动逻辑未改，批处理待续
- 原因：按一步一提交，先建立状态常量层便于后续状态机扩展

---
## 记录 32 - 2026-08-29

### 【本次功能】
完成 UI 优化 U4（切片 3）：新增 `realtime/transcript-buffer.js`，Copilot partial 转写经 `requestAnimationFrame` 合并刷新（≤10Hz），final 立即落盘；支持 turn/seq 丢弃迟到 partial；重连/结束会话时 reset。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/realtime/transcript-buffer.js` — 转写批处理模块
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — handleTranscriptEvent 集成
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — 引入 transcript-buffer

### 【差距总结】
- 预期：U4 含 answer delta 批处理与智能滚动
- 实际：转写 partial 已批处理；回答流仍逐 delta 更新 DOM
- 原因：按一步一提交，先卸载转写热路径

---
## 记录 33 - 2026-08-29

### 【本次功能】
完成 UI 优化 U4（切片 4）：新增 `realtime/answer-buffer.js`，Copilot `answer_delta` 文本经 rAF 合并追加（≤20Hz），JSON 要点仍立即解析；`answer_completed` 刷尽剩余 delta。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/realtime/answer-buffer.js` — 回答流批处理
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — handleAnswerDelta 集成
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — 引入 answer-buffer

### 【差距总结】
- 预期：U4 含智能滚动、WS bufferedAmount 监控
- 实际：转写与回答 delta 均已批处理；feed 仍每次更新后滚底
- 原因：按一步一提交，实时渲染节流优先于滚动策略

---
## 记录 34 - 2026-08-29

### 【本次功能】
完成 UI 优化 U4（切片 5）：新增 `realtime/feed-scroll.js` 实现转写 feed 智能滚动——距底部 ≤48px 时自动跟随，否则累计 final 新消息并显示「有 N 条新内容」浮层按钮。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/realtime/feed-scroll.js` — 智能滚动控制器
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — feedPendingCount / scrollFeedToBottom
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — feed 包裹层与新消息指示器
- `services/interview-server/src/main/resources/static/static/css/pages/interview-workspace.css` — feed-new-indicator 样式

### 【差距总结】
- 预期：U4 含 WS bufferedAmount 背压与音频队列上限
- 实际：滚动策略已智能化；音频上行仍无背压丢弃
- 原因：按一步一提交，先完成用户阅读体验

---
## 记录 35 - 2026-08-29

### 【本次功能】
完成 UI 优化 U4（切片 6）：Copilot 音频上行增加 WebSocket `bufferedAmount` 背压——队列超过约 500ms（16KB）时丢弃 PCM 帧，重连/停止采集时重置计数，避免网络阻塞时主线程与发送队列无限膨胀。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — sendPcmFrame 背压守卫

### 【差距总结】
- 预期：U4 全部完成（含 aria-live 策略、DOM 上限）
- 实际：音频/渲染/滚动/背压核心链路已就绪；无障碍播报策略与 DOM 裁剪待 U5
- 原因：按一步一提交，U4 主路径已闭环

---
## 记录 36 - 2026-08-29

### 【本次功能】
完成 UI 优化 U5（切片 1）：Copilot/模拟面试结束后展示「生成复盘 / 返回计划 / 再练一次」闭环面板，对接 `POST /reviews/generate`；新增 `workspace-closure.js` 与 `sessionEndedByUser` 状态。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/workspace-closure.js` — 复盘生成与导航
- `services/interview-server/src/main/resources/static/static/js/interview-workspace.js` — 双模式结束态集成
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — sessionEndedByUser
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — session-closure 面板
- `services/interview-server/src/main/resources/static/static/css/pages/interview-workspace.css` — 闭环样式

### 【差距总结】
- 预期：U5 含 aria-live 策略与设置页修复
- 实际：复盘闭环 UI 已接通；读屏播报与 profile 字段待续
- 原因：按一步一提交，先完成面试结束用户路径

---
## 记录 37 - 2026-08-29

### 【本次功能】
修复 interview-server 启动时 SQLite 初始化失败（`SQLITE_BUSY`）：`PRAGMA journal_mode=WAL` 会返回结果集，原代码未关闭 Statement 导致后续 PRAGMA 与连接关闭失败。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/db/Db.java` — 委托 SqlitePragmas 执行初始化
- `services/interview-server/src/main/java/com/aiinterview/server/db/SqlitePragmas.java` — 新增 applyFilePragmas，每条 PRAGMA 独立 Statement

### 【差距总结】
- 预期：排查并消除启动报错
- 实际：已修复并验证 `start.ps1` 可正常越过 Db.init 启动服务
- 原因：sqlite-jdbc 要求 PRAGMA 结果集关闭后才能继续执行

---
## 记录 37 - 2026-08-29

### 【本次功能】
完成 UI 优化 U5（切片 2）：Copilot 无障碍播报策略——转写 feed 改为 `aria-live="off"`，独立 `sr-only` 区域仅播报 final 转写与连接/WS 错误；新增 `.sr-only` 工具类。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — announceLive + ariaLiveMessage
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — 读屏区域与 feed aria-live 调整
- `services/interview-server/src/main/resources/static/static/css/components.css` — .sr-only

### 【差距总结】
- 预期：U5 含设置页字段修复与 DOM 消息上限
- 实际：读屏 partial 打断问题已缓解；消息列表仍无限增长
- 原因：按一步一提交，优先修复 a11y 播报策略

---
## 记录 38 - 2026-08-29

### 【本次功能】
完成 UI 优化 U5（切片 3）：Copilot 转写 `messages` 列表上限 200 条，超出时 splice 最早气泡，防止长会话 DOM 与内存无限增长。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — maxTranscriptMessages + trimTranscriptMessages

### 【差距总结】
- 预期：U5 含设置页字段修复
- 实际：DOM 上限已加；设置页薪资字段待修
- 原因：按一步一提交，先完成实时工作区性能护栏

---
## 记录 39 - 2026-08-29

### 【本次功能】
完成 UI 优化 U5（切片 4）：修复设置页期望薪资字段——未设置时展示空值而非 `0`，提交时空白解析为 `0` 以匹配后端 `getIntValue` 语义。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/settings.js` — formatSalary / parseSalaryInput

### 【差距总结】
- 预期：U5 全部验收（Axe、性能预算）
- 实际：profile 薪资映射已修复；自动化 a11y/性能验收待续
- 原因：按一步一提交，先修复用户可见的设置回填问题

---
## 记录 40 - 2026-08-29

### 【本次功能】
完成 UI 优化 U5（切片 5）：语音档案录音增加秒级计时（meta 区显示 elapsed/30s），并在停止/离开页面时释放 MediaStream、定时器与 preview blob URL，修复 Object URL 泄漏。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/voice-profile.js` — 计时、revokePreviewUrl、releaseRecordingResources

### 【差距总结】
- 预期：U5 含 Playwright 视觉回归流水线
- 实际：语音档案交互与资源清理已完善；截图基线待 U0 续
- 原因：按一步一提交，先完成 profile 页运行时稳定性

---
## 记录 41 - 2026-08-29

### 【本次功能】
完成 UI 优化 U0（续）：新增 `tests/ui-baseline/` Playwright 脚手架，对 `/internal/ui-baseline` 在七视口生成全页截图；更新 `ui-baseline-pages.md` 引用。

### 【参考文件】
- `services/interview-server/tests/ui-baseline/package.json` — Playwright 依赖与脚本
- `services/interview-server/tests/ui-baseline/playwright.config.js` — 配置
- `services/interview-server/tests/ui-baseline/ui-baseline.spec.js` — 七视口截图用例
- `services/interview-server/tests/ui-baseline/README.md` — 运行说明
- `services/interview-server/docs/ui-baseline-pages.md` — 脚手架路径

### 【差距总结】
- 预期：U0 CI 集成与全页面截图矩阵
- 实际：组件样板页截图流水线可本地运行；其余 baseline 页待扩展 BASELINE_PAGES
- 原因：按一步一提交，先交付可运行的最小截图入口

---
## 记录 42 - 2026-08-29

### 【本次功能】
修复 Copilot 转写「一句话拆成多个气泡」问题：对齐旧版三段式协议——`transcript_partial/final` 仅更新进行中气泡（current），整句话轮由 `utterance_completed` 落盘；候选人侧增加 1.5s 静音超时落盘。

### 【参考文件】
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — mergeTranscriptText、finalizeCurrentUtterance、utterance_completed 处理

### 【差距总结】
- 预期：与后端 UtteranceAssembler 语义完全一致
- 实际：前端已区分 ASR 片段 final 与话轮 complete；候选人无 utterance_completed 事件，靠静音超时兜底
- 原因：后端仅对面试官问句发 utterance_completed

---
## 记录 43 - 2026-08-29

### 【本次功能】
对齐 Python 版音频角色分流：自动=系统(面试官)+麦克风(有声纹档案→本人/否则面试官)；本人=仅麦克风；面试官=麦克风+系统均为面试官。后端双路 ASR 会话 + 前端双轨采集（PcmStreamRecorder）；移除候选人 1.5s 定时落盘避免句内碎气泡。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWsPolicy.java` — resolveSpeakerForAudioSource
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java` — 多路 ASR + 按帧来源码路由
- `services/interview-server/src/main/resources/static/static/js/copilot-session.js` — acquireStreams 双轨采集
- `services/interview-server/src/main/resources/static/static/js/audio/pcm-stream-recorder.js` — 新增
- `services/interview-server/src/main/resources/templates/applicant/interview_workspace.html` — 本人/面试官/自动文案

### 【差距总结】
- 预期：麦克风声纹实时比对（真实 diarization）
- 实际：有声纹档案时自动模式麦克风轨标记为本人，未录入则视为面试官（规则近似）
- 原因：声纹在线比对尚未接入 ASR 网关，先用档案存在性作启发式

---
## 记录 44 - 2026-08-29

### 【本次功能】
自动模式麦克风角色改为真实声纹比对：会话启动时用个人中心语音档案注册 mica-voice SpeakerService embedding，转写时对最近 3 秒麦克风 PCM 做 verify；匹配成功=本人，未录入/比对失败/模型不可用=面试官。

### 【参考文件】
- `services/interview-server/src/main/java/com/aiinterview/server/SpeakerEngine.java` — 声纹引擎单例
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/VoiceProfileVerifier.java` — enroll + verify
- `services/interview-server/src/main/java/com/aiinterview/server/copilot/MicPcmBuffer.java` — 麦克风 PCM 环形缓冲
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWs.java` — 按比对结果下发 speaker/voice_match
- `services/interview-server/src/main/java/com/aiinterview/server/web/CopilotWsPolicy.java` — voiceProfileMatch 三态策略

### 【差距总结】
- 预期：声纹模型与 ASR 模型一并预下载并在 /api/ready 暴露 speaker 就绪状态
- 实际：Speaker 模型缺失时 verifier 创建失败，自动模式麦克风回退面试官
- 原因：沿用现有 models 目录，speaker onnx 需另行下载

---
## 记录 45 - 2026-09-05

### 【本次功能】
启动「前端统一（React + shadcn/ui）与架构改造」计划：完成 P0 API 契约、P2 页面清单、P1 web/ 脚手架（Vite+React19+TS+Tailwind v4+shadcn/ui，Quiet Command 主题翻译）、P3-1 Copilot 实时工作区迁移（裸 WS + v2 音频帧 + rAF 批处理 + 智能滚动 + 背压 + 结束复盘闭环）。第三方 AK/SK 开放 API 按用户决定后置为附录 A。

### 【参考文件】
- `docs/superpowers/plans/2026-09-05-react-web-unification-plan.md` — 主计划
- `docs/api-contract.md` — REST + WS 唯一事实源（含实现差异登记 D1–D8）
- `docs/web-page-inventory.md` — py/java/web 页面对照与实时逻辑复用清单
- `web/` — 新前端工程（src/features/copilot 为实时链路移植）
- `web/public/audio/pcm16-worklet.js` — AudioWorklet 采集（移植自 Java 版）

### 【差距总结】
- 预期：P0–P5 全部完成
- 实际：P0/P1/P2/P3-1 完成；P3-2 登录注册首页已完成；P3-3~P3-8 页面、P4 双后端适配、P5 清理未完成
- 原因：按一步一提交推进，后端契约适配（D1–D6）留待 P4

---
## 记录 46 - 2026-09-05

### 【本次功能】
完成前端统一计划 P3-2~P4：登录/注册/首页、工作台、计划列表/详情（准备包编辑/确认/重生成）、简历中心、简历优化、知识库、复盘列表/详情、个人设置（声纹录制 30s 计时）、模拟面试工作区全部迁入 `web/`。后端改造完成——Java：`RouteSupport.route()` 统一 `/api` 别名、AuthRoutes JSON body、WS 出站 `system`/`message` 对齐契约、准备包 PATCH/regenerate、knowledge import/rebuild-index、resume-optimize 单条确认、SpaSupport 托管 web/dist（`pnpm build:server` 产出）；Python：新增 `utils/copilot_ws.py` 裸 WS 通道（契约 §6 全事件）、契约补充蓝图（/api/logout、/api/health、/api/resumes 别名）、登录/注册 JSON、SPA 托管中间件（SPA_ENABLED=1 启用）。契约测试 9 项 + 全量 158 通过，Java 编译+测试通过。

### 【参考文件】
- `docs/api-contract.md` §8 — 实现差异登记 D1–D10（D1–D6 已完成）
- `web/src/features/copilot/` — 实时链路（use-copilot-session/pcm-stream-recorder/transcript-buffer/answer-buffer/feed-scroll）
- `services/interview-server/src/main/java/com/aiinterview/server/web/SpaSupport.java` — Java SPA 托管
- `services/interview-server/.../web/RouteSupport.java` — `/api` 别名注册助手
- `utils/copilot_ws.py` — Python 裸 WS Copilot 通道
- `routes/route_contract.py` — Python 契约补充路由
- `tests/api/test_contract.py` — 契约测试

### 【差距总结】
- 预期：P0–P5 全部完成
- 实际：P0–P4 完成；P5（删除旧页面/目录整理）待用户确认删除清单后执行
- 原因：删除操作按计划约定需逐项确认；D8/D9 遗留项随 P5 收敛

---
## 记录 47 - 2026-09-05

### 【本次功能】
闭合 P4 验收门 + 执行 P5 清理与文档重写。
1) 端到端验收：新增 `scripts/verify_copilot_ws_flow.py`（契约裸 WS 版全链路驱动），对 Python（:5000）与 Java（:18081）双后端以真实语音 PCM 完成真实 ASR + LLM 验收——transcript→utterance_completed(question_feature)→answer_queued/started/delta×90+/completed(要点+参考回答)→ending→ended，双后端 PASS。过程中修复：注册把空串写入 Enum 列导致登录 500；sherpa 客户端补尾静音端点检测（对齐网关 final 行为）；copilot_ws drain 线程 ORM 脱管崩溃。Java 慢模型流受 `LLM_TIMEOUT_SECONDS`（默认 45s）约束，E2E 用 `LLM_COPILOT_MODEL=deepseek-v4-flash` 验证。
2) P5：Python 已迁移页面视图改服务 `web/dist/index.html`（保留鉴权装饰器），删除 templates/{common,applicant,layouts} 与 static/js/copilot.js；Java PageRoutes 纯 SPA 化，删除 templates/、TemplateRenderer.java、static/static、static/vendor、ui-baseline 脚手架及对应的 12 个过时静态 UI 测试；新增 `pnpm build:server`（dist→classpath:static/app）；SPA 托管经真实启动验证（/ 与 /login 200、未鉴权 302、/assets 200）。文档：README 重写（保留双 ASR 启动章节）、ARCHITECTURE 更新（React SPA/裸 WS/双桥）、旧计划标注状态、根目录临时文件归档 docs/archive/。

### 【参考文件】
- `scripts/verify_copilot_ws_flow.py` — 真实 ASR+LLM E2E 驱动（契约 §5–§6）
- `services/sherpa_asr_client.py` — 端点检测（SHERPA_ENDPOINT_SILENCE，默认 1.2s）
- `services/interview-server/.../web/PageRoutes.java` / `SpaSupport.java` — 纯 SPA 页面路由
- `web/scripts/copy-to-java.mjs` — dist→Java classpath 复制
- `docs/superpowers/plans/2026-09-05-p5-cleanup-checklist.md` — 删除清单与执行状态

### 【差距总结】
- 预期：P0–P5 全部完成
- 实际：P0–P5 完成。遗留：Java 企业端模板/路由（契约外 B8，用户裁定是否归档）；SPA_ENABLED 中间件与 PageRoutes 的兼容分支保留一个发布周期；Mimosa 审计建议重跑完整深度扫描
- 原因：按用户确认的推荐方案执行；不可逆操作仅限 git 可恢复范围

---
## 记录 48 - 2026-09-05

### 【本次功能】
按用户裁定执行 B8 归档 + 全链路自检。
1) B8：企业端遗留隔离——`git rm` 移除 route_Job/route_appliacntion/score_weight/route_AIInterview/route_company/text_speech_synthesis(TTS 桩) 与 templates/company，出处与恢复方式记录于 `docs/archive/legacy-company/README.md`（git rm 隔离，git 历史可取回）；`routes/__init__.py` 与 app.py 摘除企业视图与死助手（stream_chat/_get_llm_client 等）；`utils/socketio_service.py` 瘦身为纯 SocketIO 装配（老 Socket.IO 面试处理器归档）；company 角色登录按契约拒绝（与 Java 一致）；数据层模型保留（求职者投递流仍用 Application）。
2) 自检：全量 Python 116 测试通过；Java mvn test 通过；双后端真实 ASR+LLM E2E 归档后复跑均 PASS；Mimosa 深度审计 `scan-2026-09-05T06-02-10.976Z-c95a1a06ba70`（seal `6485…1d1a`，completion=completed，依赖风险 0）。
3) 顺带修复：app.py 中因公司视图删除产生的重复 `register_blueprints(app)` 调用；Java `CopilotWs` 的 WS 空闲销毁（默认 120s）会掐断慢 LLM 流式回答，改为与 `LLM_TIMEOUT_SECONDS` 联动（max(120s, 超时+60s)）。

### 【参考文件】
- `docs/archive/legacy-company/README.md` — 企业端归档清单与恢复方式
- `utils/socketio_service.py` — 瘦身后的 SocketIO 装配
- `docs/superpowers/plans/2026-09-05-p5-cleanup-checklist.md` — 执行记录与自检结果

### 【差距总结】
- 预期：完成所有计划和功能开发
- 实际：P0–P5 全部完成；自检（测试/构建/E2E/深度审计）全部通过
- 原因：无——仅存的开放项为「企业端复活需先轮换讯飞密钥」与「30 分钟长期稳定性验收」（计划外硬件依赖项）
