# Web 页面清单（py / java / 新版 web 对照）

- 制定日期：2026-09-05
- 流程整合原则：以两条现有用户路径取并集，参考面试猫 AI 的「准备 → 实战 → 复盘 → 提升」循环，保留本产品「计划/准备包/事实边界」核心差异点。
- 统一流程：

```text
注册/登录
  -> 创建面试计划（公司/岗位/JD/简历/级别/技术标签）
  -> 准备包（三版自我介绍、亮点、风险点、复习方向）
  -> 实战：实时 Copilot / 模拟面试
  -> 统一复盘（评分、风险、薄弱项、下一步任务）
  -> 简历针对性优化（人工确认，不覆盖原简历）
  （知识库作为准备与复盘的支撑能力贯穿）
```

## 页面状态对照

迁移状态：`pending` → `done`。页面路径以 `docs/api-contract.md` §0 为准。

| # | 页面 | SPA 路由 | Python 现状 | Java 现状 | 新版方案 | 状态 |
|---|---|---|---|---|---|---|
| 0 | 首页 | `/` | `common/index.html` | `common/index.html` | 公开 landing，四能力卡 + 注册 CTA | done |
| 0 | 登录 | `/login` | `common/login.html` | `common/login.html` | JSON `/api/login`，错误内联展示 | done |
| 0 | 注册 | `/register` | `common/register.html` | `common/register.html` | JSON `/api/register`，仅求职者账号字段 | done |
| 1 | Copilot 实时工作区 | `/applicant/copilot?plan_id=` | `applicant/copilot.html` + Socket.IO | `interview_workspace.html`(copilot 模式) + 裸 WS | 专注布局（无侧栏）；契约 §6 裸 WS + v2 帧；rAF 批处理；智能滚动；状态机 | pending |
| 2 | 模拟面试工作区 | `/applicant/mock-interview?plan_id=` | `applicant/mock_interview.html` | `interview_workspace.html`(mock 模式) | 专注布局；REST 逐题问答 + 评分卡；结束闭环面板 | pending |
| 3 | 工作台 | `/applicant/workspace` | `applicant/workspace.html` | `applicant/workspace.html` | 计划卡 + 快捷入口 + 引导下一步 | pending |
| 3 | 计划列表 | `/applicant/interview-plans` | `applicant/interview_plans.html` | 同名 | plan-card 列表 + 新建 Dialog | pending |
| 3 | 计划详情 | `/applicant/interview-plans/:id` | `applicant/interview_plan_detail.html` | 同名 | descriptions + 准备包编辑/确认/重生成 | pending |
| 4 | 简历中心 | `/applicant/resumes` | `applicant/resume_manage.html` | 同名 | 上传 + 解析状态轮询 + 预览 | pending |
| 4 | 简历优化 | `/applicant/resume-optimize?plan_id=` | `applicant/resume_optimize.html` | 同名 | 匹配/差距/建议列表，逐条确认 | pending |
| 5 | 知识库 | `/applicant/knowledge` | `applicant/knowledge.html` | 同名 | 检索 + 新建/编辑 Dialog + 导入 + 重建索引 | pending |
| 6 | 复盘列表 | `/applicant/reviews` | `applicant/reviews.html` | 同名 | list-item + 筛选 | pending |
| 6 | 复盘详情 | `/applicant/reviews/:id` | `applicant/review_detail.html` | 同名 | 评分环 + 风险/薄弱项/行动项 + 导出 | pending |
| 7 | 个人设置 | `/applicant/profile` | `applicant/profile.html` | 同名 | 资料/密码 + 语音档案录放（30s 计时、资源释放） | pending |
| — | 组件样板 | `/dev/baseline`（仅开发态） | 无 | `/internal/ui-baseline` | shadcn 组件 + 主题验证页（替代 Java 样板页） | pending |
| — | 企业端页面 | 无 | `company/*`（dashboard/jobManage 等） | 无（企业端未开放） | 不迁移；契约外遗留，P5 评估归档 | 不迁移 |

## 实时逻辑复用清单（P3-1 输入）

来源 = Java 版（最成熟实现），纯 JS 逻辑直接移植，组件层用 hooks 重写：

| Java 版模块 | 移植目标 | 说明 |
|---|---|---|
| `static/js/audio/pcm16-worklet.js` | `web/src/features/copilot/audio/pcm16-worklet.js` | 音频线程重采样 + 100ms PCM16 分帧，原样复用 |
| `static/js/audio/pcm-stream-recorder.js` | `web/src/features/copilot/audio/pcm-stream-recorder.ts` | 双轨采集（麦克风 + getDisplayMedia） |
| `static/js/copilot-conn-state.js` | `web/src/features/copilot/conn-state.ts` | 状态常量 + status kind 映射 |
| `static/js/realtime/transcript-buffer.js` | `web/src/features/copilot/realtime/transcript-buffer.ts` | partial rAF 合并 ≤10Hz，turn/seq 丢弃 |
| `static/js/realtime/answer-buffer.js` | `web/src/features/copilot/realtime/answer-buffer.ts` | delta rAF 合并 ≤20Hz |
| `static/js/realtime/feed-scroll.js` | `web/src/features/copilot/realtime/feed-scroll.ts` | ≤48px 跟随 + 新消息浮层 |
| `static/js/copilot-session.js`（WS/背压/生命周期） | `web/src/features/copilot/use-copilot-session.ts` + `ws-transport.ts` | 契约 §6 裸 WS + v2 帧 + bufferedAmount 500ms 背压 + 恢复 |

## 事实边界约束（生成类页面通用）

- 不得虚构候选人项目/职责/公司经历/数字；缺真实实践时使用「理论理解」「如果由我设计」表达。
- 准备包与回答的来源证据（source_evidence）必须可见。
- 守卫触发以 `FACT_GUARD_FALLBACK` / `fact_guard_triggered` / `fact_risks` 呈现，不静默丢弃。
