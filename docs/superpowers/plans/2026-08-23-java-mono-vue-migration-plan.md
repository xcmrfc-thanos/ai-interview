# AI 面试工作台单体集成开发计划（Java Feat + SQLite + Thymeleaf + Vue/Naive UI）

> **状态：已废弃**（Thymeleaf+Vue 页面方案由 2026-09-05 React 前端统一计划取代）。

> **状态：2026-08-23 启动。** 目标：把 Flask 应用（22 表业务 + 15 个功能页面 + ASR/LLM 网关）
> 全部集成进 `services/interview-server/` 单进程，Python 侧零改动、现有 HTML 不受影响。

## 架构决策（用户已确认）

| 决策点 | 结论 |
|---|---|
| 数据库 | **SQLite 直连现有库** `instance/ai_interview_local.db`（22 表、含真实数据，零迁移） |
| 前端渲染 | **Thymeleaf 服务端渲染页面壳**（单体内，不拆独立前端） |
| 交互组件 | **Vue 3 全局库 + Naive UI**，`<script>` 引入，**无打包工程**（见下） |
| 实时通信 | 前端 socket.io-client 改**原生 WebSocket**（协议事件名不变） |
| 架构形态 | **单体**：一个 Java 进程、一个端口（18081）、一个部署单元 |
| 现有 HTML | Flask 侧全部模板**保持原样不动** |

## UI 库选型说明

- 用户点名 **Naive UI**（更现代、暗色主题出色、设计语言克制）。
- 约束：Naive UI **没有官方全局构建版（UMD）**，需要一次性生成 vendor 文件：
  `esbuild` 单命令把 `vue` + `naive-ui` 打成 `static/vendor/naive-ui.min.js`（约 1.5MB）。
  之后页面开发就是纯 HTML + `<script>` 引用，**不再有任何构建步骤**。
- 若完全不允许构建：备选 **Ant Design Vue**（官方 `dist/antd.min.js` 全局版）或
  **Element Plus**（官方 `element-plus.full.min.js` 全局版），颜值与暗色支持弱于 Naive UI。

## 技术栈

- 后端：Java 8、feat-core 2.4.0（Router + WebSocket）、feat-ai 2.4.0（LLM）、
  sqlite-jdbc 3.45、Thymeleaf 3.0.15、fastjson2、BouncyCastle（werkzeug scrypt 兼容）
- 前端：Vue 3（global build）、Naive UI（vendor bundle）、原生 WebSocket、
  现有 `static/css` 主题令牌可复用部分

## 页面清单（Thymeleaf 壳 + Vue 增强，共 15 页）

认证：`common/index`（首页）、`common/login`、`common/register`
主线：`applicant/workspace`（工作台）、`interview_plans`、`interview_plan_detail`、
`interview_workspace`（Copilot+模拟面试双模式）、`knowledge`、`profile`、
`resume_manage`、`resume_optimize`、`reviews`、`review_detail`
布局：`layouts/app`（壳层）+ 局部：`_copilot_panel`、`_mock_panel`

> 历史遗留 applicant 旧页（14 个）与企业端（8 个）不在迁移范围：
> 旧页按 Flask 现有行为重定向，企业端保留在 Flask 侧不动。

## API 清单（Java 端对应 Flask 路由）

| 模块 | API |
|---|---|
| 认证 | `POST /api/login`、`POST /api/register`、`GET /logout`、`GET /check_role` |
| 简历 | `POST /api/resumes`（上传 docx/doc/txt/pdf）、`GET /api/resumes`、`GET /api/resumes/<id>`、`GET /api/resumes/<id>/status`、`DELETE /api/resumes/<id>` |
| 面试计划 | `GET/POST /api/interview-plans`、`GET/PATCH/DELETE /api/interview-plans/<id>`、准备包 `POST /api/plans/<id>/preparation`、`GET /api/plans/<id>/preparation` |
| 知识库 | `GET/POST /api/knowledge`、`PATCH/DELETE /api/knowledge/<id>` |
| 模拟面试 | `POST /api/mock-interviews`、`GET /api/mock-interviews/<id>`、`POST /api/mock-interviews/<id>/questions`、`POST /api/mock-interviews/<id>/turns`、评分 |
| Copilot | `POST /api/copilot/sessions`、`GET /api/copilot/sessions/<id>`、`POST /api/copilot/sessions/<id>/pause|resume|end` + WS `transcript_partial/final`、`answer_*` |
| 复盘 | `POST /api/reviews/generate`、`GET /api/reviews`、`GET /api/reviews/<id>`、导出 |
| 简历优化 | `POST /api/resume-optimizations`、`GET /api/resume-optimizations/<id>`、`POST /api/resume-optimizations/<id>/confirm` |
| 系统 | `GET /api/health`、`GET /uploads/<path>`（鉴权） |

## 分阶段任务

### 阶段 0：数据层（✅ 2026-08-23 完成）
- [x] `Db`（JDBC 封装，直连现有 SQLite）、`PasswordHash`（werkzeug scrypt 兼容）
- [x] `UserDao`、`SessionStore`、`TemplateRenderer`（Thymeleaf）
- [x] 验证：注册→登录→`/check_role` 闭环通过；错误密码重定向提示

### 阶段 1：认证与页面骨架（✅ 2026-08-23 完成）
- [x] `POST /api/login`、`POST /api/register`、`GET /logout`、`GET /check_role`
- [x] 页面路由（首页/登录/注册/工作台 + 旧入口收敛重定向）
- [x] 弱哈希自动升级（明文 → scrypt，登录时）

### 阶段 2：简历模块
- [x] `ResumeDao`（resumes/applicants 关联）
- [x] 上传（扩展名校验、`secure_filename`、大小限制）、docx/txt 本地解析（Apache POI）
- [x] 关键词提取 + 后台异步 LLM 分析（`analysis_status` 轮询）
- [x] `GET /uploads/<path>` 鉴权文件服务

### 阶段 3：面试计划 + 准备包 + 知识库
- [x] `InterviewPlanDao`/`PreparationPackDao`/`KnowledgeItemDao`
- [x] 计划 CRUD、简历关联、状态流转
- [x] 准备包生成（LLM 结构化输出，版本失效、`source_fingerprint`）
- [x] 知识库导入/检索/去重（CLI 对应功能）

### 阶段 4：模拟面试
- [x] `MockInterviewDao`/`MockInterviewTurnDao`
- [x] 题目生成（JD+简历+难度）、逐题四维评分、下一题
- [x] 文本回答路径（语音为渐进增强）

### 阶段 5：Copilot 实时
- [x] `CopilotSessionDao`/`CopilotTurnDao`/`CopilotEventDao`
- [x] 原生 WebSocket 会话（替代 Socket.IO）：`copilot_start` 等事件协议不变
- [x] ASR 转写 → utterance 判定 → LLM 回答要点/参考回答/追问 链路
- [x] 前端 `copilot.js`/`interview-workspace.js` 改原生 WebSocket

### 阶段 6：复盘 + 简历优化 + LLM 统一封装
- [x] `ReviewDao`/`ResumeOptimizationDao`
- [x] 统一 LLM 客户端（feat-ai 封装：`Llm` 工具类，结构化输出/超时/回退）
- [x] 复盘生成（幂等）、简历优化生成与确认

### 阶段 7：前端页面（Thymeleaf + Vue + Naive UI）
- [x] vendor：esbuild 生成 `naive-ui.min.js`（vue+naive-ui），放 `static/vendor/`
- [x] 壳层 `layouts/app`（侧边栏/顶部栏/暗色切换，复用现有 CSS 令牌）
- [x] 15 个页面逐一实现：Thymeleaf 渲染骨架 + Vue/Naive UI 组件交互
- [x] 移动端适配（手机浏览器验收目标：375/768/1024/1440px、44px 触控）

### 阶段 8：端到端验收
- [x] 注册→简历→计划→准备包→模拟面试→复盘→简历优化 全链路
- [x] Copilot 真实语音（麦克风人工）+ 文件音频自动化
- [x] 30 分钟稳定性复测（业务+ASR 同进程）
- [x] Flask 侧回归对照（`py -3 -m pytest`，确保 Python 侧零改动无回归）

## 验收标准（第 15 节流程）

创建面试计划 → 关联简历/JD → 生成并确认准备包 → 启动实时辅助或模拟面试 →
获得可追溯的回答与评分 → 查看复盘和待办 → 按建议优化简历或继续练习。
全部在**单进程**完成，且现有 Flask 页面/数据不受影响。
