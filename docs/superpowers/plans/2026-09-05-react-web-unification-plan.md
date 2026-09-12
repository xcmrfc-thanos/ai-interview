# 前端统一（React + shadcn/ui）与架构改造计划

- 制定日期：2026-09-05（同日修订：第三方开放 API 后置，优先自身改造）
- 适用范围：新建 `web/` 前端工程；`services/interview-server`（Java，正式产品）与根目录 Python Flask（参考/部署形态二）按同一契约适配；仓库目录整理
- 已拍板决策：
  - 前端框架：**React 19 + Vite + TypeScript + Tailwind CSS v4 + shadcn/ui**，不引入 Vue
  - 前端为独立工程，构建产物 `dist/` 由两个后端各自托管（同源、无 CORS、Cookie 会话）
  - 双后端实现同一份 API 契约；契约文档是 REST + WS 事件的唯一事实源
  - **第三方开放 API（AK/SK + JS SDK）后置**：本期不做，仅在契约中预留鉴权扩展点，设计草案见附录 A
  - 前端全部完成后**清理旧页面并整理仓库目录**（阶段 P5）

---

## 总体阶段

- [x] **P0** API 契约文档（先冻结 REST + WS 契约，再写前端）
- [x] **P1** `web/` 工程脚手架与设计系统落地
- [x] **P2** 流程整合与页面清单（参考 py+java 现状，参考面试猫 AI 优化流程）
- [x] **P3** 页面逐页迁移（Copilot 实时工作区优先）
- [x] **P4** 后端改造（双后端托管 `web/dist`、下线服务端模板渲染）
- [x] **P5** 清理旧页面、整理目录、归档（执行记录见 2026-09-05-p5-cleanup-checklist.md；B8 企业端遗留保留待用户裁定）

后置（本期不做）：第三方 AK/SK 开放 API 与 JS SDK，见附录 A。

依赖关系：P0 冻结 WS 事件 schema 后 P3 实时页才接入；P5 必须在 P3 全部页面迁移完成且 P4 双后端验收通过后执行。

---

## P0. API 契约

产出物：`docs/api-contract.md`（唯一事实源，后续所有接口变更只改这里）。

- REST 接口清单：认证、计划、准备包、简历、知识库、复盘、模拟面试、设置。来源 = Java 版现有路由 + Python 版现有路由，逐条对比后取并集、标注差异和取舍。
- WS 事件 schema：沿用 B2 已冻结的事件集（`transcript_partial/final`、`utterance_completed`、`answer_queued/started/delta/completed`、`audio_ack`、`reconnected`、`ending`、错误码），补充 v2 音频帧（AI 头 + source + sequence + PCM）的字节级定义。
- 每个接口标注：鉴权方式（本期仅会话 Cookie）、错误码、分页约定、幂等性。
- 预留扩展点：鉴权字段设计为可插拔（`auth: session | token`），未来开放 API 只新增鉴权方式，不改接口路径与事件 schema。

---

## P1. web/ 工程脚手架

- `web/`：Vite + React 19 + TypeScript strict + Tailwind v4 + shadcn/ui 初始化。
- 设计系统：把 Java 版 `tokens.css` 的 Quiet Command（深海蓝绿、深浅双主题）翻译为 shadcn theme CSS 变量；自托管 Manrope/Noto Sans SC 与图标，无 CDN。
- 基础设施：react-router 路由表、Zustand 会话状态、API client（契约生成或手写 + 类型）、主题 boot 防闪烁脚本、错误边界。
- 布局壳：侧栏 + 顶栏 + 页面头 + 上下文条；面试工作区使用独立专注布局。
- 验收门：样板页（对齐 Java `/internal/ui-baseline` 的组件集）在深浅主题、长中文、键盘导航、reduced-motion 下通过后才开始迁业务页。

## P2. 流程整合与页面清单

以两条现有用户路径为原料，取并集后优化为**一条流程**（参考面试猫 AI 的「准备 → 实战 → 复盘 → 提升」循环，但以本产品的计划/准备包为核心差异点）：

```text
注册/登录
  -> 创建面试计划（公司/岗位/JD/简历/级别/技术标签）
  -> 准备包（三版自我介绍、岗位亮点、风险点、复习方向）
  -> 实战：实时 Copilot（录音转写 + 回答要点）/ 模拟面试（多轮问答评分）
  -> 统一复盘（问题、评分、风险、薄弱项、下一步任务）
  -> 简历针对性优化（对照 JD，人工确认）
  （知识库作为准备与复盘的支撑能力贯穿）
```

产出物：`docs/web-page-inventory.md`，含每页的状态（py 现状 / java 现状 / 新版方案 / 迁移状态）。

页面清单（迁移顺序即优先级）：

| 顺序 | 页面 | 备注 |
|---|---|---|
| 1 | Copilot 实时工作区 | 双端重复最痛、实时链路最有验证价值；沿用 U3/U4 模块划分 |
| 2 | 登录 / 注册 / 首页 | 公开页 |
| 3 | 工作台 + 面试计划列表/详情 | 主导航闭环 |
| 4 | 简历中心 / 简历优化 | 上传、解析状态、改写确认 |
| 5 | 知识库 | 检索、导入、维护 |
| 6 | 复盘列表 / 复盘详情 | 评分与行动项 |
| 7 | 个人设置 | 语音档案、期望薪资等字段 |
| 8 | 模拟面试工作区 | REST 流程 + 评分展示 |

迁移约束：

- 每页先在 `web/` 完成并与契约联调通过，再切路由；旧页面保留一个发布周期做重定向。
- 实时逻辑复用清单：`transcript-buffer`、`answer-buffer`、`feed-scroll`、`pcm16-worklet`、`copilot-conn-state`（节流/背压/滚动判定为纯 JS，直接搬；组件层用 hooks 重写）。
- 事实边界规则（不虚构经历、理论表达兜底）在所有生成类页面照搬现有文案与守卫。

## P3. 页面逐页迁移

- 按 P2 清单顺序执行，一页一提交。
- 每页完成标准：契约联调通过 + 深浅主题/移动端视口通过 + `README_PLAN.md` 追加记录。
- WS 实时页额外标准：断连恢复、暂停拒收、结束排水、背压丢弃行为与 Java 版 U4 验收口径一致。

## P4. 后端改造（双后端托管 dist）

- Java（正式产品）：
  - 页面路由改为服务 `web/dist`（SPA fallback 到 `index.html`，API 与静态资源路径不变）；Thymeleaf 页面模板与页面级 JS 逐步下线（实际删除放 P5）。
  - 开发态支持 `web/` dev server 代理到 Java 后端（Vite proxy），保证前端独立开发体验。
  - `/api/ready`、`/api/metrics` 保持不变。
- Python（部署形态二）：
  - 对齐同一契约（REST + WS 事件序列），差异项记录在契约文档的「实现差异」节。
  - Flask 托管 `web/dist`，开发态同样支持 Vite proxy。
- 验收：
  - 契约一致性：双后端跑同一套契约测试（REST 快照 + WS 事件序列模拟客户端）。
  - 端到端：真实 ASR + LLM 的 Copilot 全链路、模拟面试、复盘、简历优化。
  - U5 验收门槛沿用：Axe critical/serious = 0、对比度 ≥4.5:1、触控 ≥44×44、320px 重排可用、实时工作区稳态 ≥55fps、事件到 partial P95 ≤100ms。

## P5. 清理与目录整理（前端全部完成后）

清理前先输出删除清单并请用户确认（含「确认无消费者」检查）：

- 删除 Java 侧：`templates/` 下已迁移页面、`static/static/js` 内被 `web/` 取代的模块（`interview-workspace.js`、`copilot-session.js`、`mock-interview.js`、`realtime/*`、`pcm16-worklet.js` 等）、`ui_baseline` 样板页与对应 Playwright 脚手架（若被 `web/` 测试取代）。
- 删除 Python 侧：已迁移的 Jinja 模板、`static/js/copilot.js` 及相关旧静态页。
- 保留：API 路由层、WS 端点、契约测试、`services/mica-voice-gateway`。
- 目录整理：
  - 根目录临时物（`temp/`、`test-results/`、`聊天记录.md`、`提示词`、`README_PLAN.md` 旧记录）归档至 `docs/archive/` 或移除（逐项确认）。
  - `docs/superpowers/plans/` 旧计划标注状态（完成/废弃）。
  - 根 `README.md` 重写为「web + 双后端」新架构说明，`ARCHITECTURE.md` 同步更新。

---

## 执行约定

- 沿用「一步一提交」：每个切片可编译、可测、可回滚，`README_PLAN.md` 逐条追加记录。
- 后端契约变更必须先改 `docs/api-contract.md` 再改实现；新增字段向后兼容一个发布周期。
- 两个后端合并/切换仍需用户确认；P5 的删除操作逐项确认后执行。

## 风险与对策

| 风险 | 对策 |
|---|---|
| 双后端契约漂移 | 契约测试进 CI；差异必须在契约文档「实现差异」节登记 |
| 实时页重写引入行为回归 | 先以模拟 WS 客户端对齐事件序列，再接真实 ASR/LLM；沿用 U4 验收口径 |
| 开发态双后端切换繁琐 | Vite proxy 按环境变量切换目标后端，前端代码无感 |
| 清理误删仍被引用的资产 | 删除前 grep 引用 + 构建产物验证 + 逐项用户确认 |

---

## 附录 A. 第三方开放 API（后置，本期不做）

未来目标：第三方在其页面嵌入我们的 JS SDK，用麦克风采集音频 → 实时转写（可扩展问答生成）。以下为设计草案存档，启动时以 P0 契约预留的 `auth: token` 扩展点落地。

**安全模型（浏览器不放 SK）：**

- AK/SK 只用于**服务端到服务端**换发短时效 token（10 分钟、绑定单次转写会话）；第三方后端持有 SK，浏览器侧 SDK 只拿 token。
- 签名协议：`HMAC-SHA256(AK + timestamp + nonce + bodyHash)`，时间窗 ±5 分钟，nonce 防重放。
- 纯前端无后端接入为降级方案：仅 AK + Origin 白名单 + 低速率配额，契约中明示风险等级。

**AK/SK 表草案（双后端同构）：**

```sql
CREATE TABLE api_credential (
  id            INTEGER PRIMARY KEY,
  ak            TEXT NOT NULL UNIQUE,
  sk_hash       TEXT NOT NULL,          -- 只存哈希，明文 SK 仅创建时返回一次
  name          TEXT NOT NULL,
  scopes        TEXT NOT NULL,          -- 逗号分隔: asr.transcribe, copilot.answer...
  origin_whitelist TEXT,
  rate_limit_qps   INTEGER NOT NULL DEFAULT 2,
  status        TEXT NOT NULL DEFAULT 'active',
  created_at / last_used_at
);
CREATE TABLE api_token (
  id INTEGER PRIMARY KEY,
  ak TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  scope TEXT NOT NULL,
  session_id TEXT,
  expires_at NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0
);
```

**SDK 形态：** TypeScript SDK（npm 包 + UMD），`createSession(token)` → 麦克风采集（复用 pcm16-worklet）→ WS 转写 → `onPartial/onFinal` 回调 → `stop()`。管理入口放个人设置「开放 API」页。
