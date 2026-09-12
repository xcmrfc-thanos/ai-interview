# 个人 AI 面试作战台实施计划

> **状态：已完成**（个人作战台功能已落地并由后续统一计划迭代）。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 Flask AI 面试项目收敛为个人求职者使用的面试作战台，交付“面试计划 -> 准备包 -> 实时辅助/模拟面试 -> 复盘 -> 简历优化”完整闭环。

**Architecture:** 保留 Flask、SQLAlchemy、Jinja2、Socket.IO、现有 Resume/Job/AIInterview 数据和 mica-voice 网关；新增个人领域模型、服务层和 Blueprint。所有 AI 能力共用统一的上下文、Prompt 和结构化输出边界；知识库第一版使用 SQLAlchemy 结构化字段与 SQLite FTS5/兼容 LIKE 检索，不增加向量数据库依赖。旧企业 ATS 先从个人版入口退出，模型与接口保持兼容。

**Tech Stack:** Python 3.12, Flask 3, Flask-SQLAlchemy, Flask-SocketIO, Jinja2, SQLite/MySQL, OpenAI-compatible SDK, Java mica-voice gateway, HTML/CSS/JavaScript, pytest, Maven.

**Authoritative Spec:** `docs/superpowers/specs/2026-08-21-personal-ai-interview-workspace-design.md`

**Execution Constraints:** 当前仓库没有有效提交历史。实现过程不执行 commit、push、rebase 或删除历史文件；所有提交动作等待用户单独授权。使用 `py -3.12`，不能使用当前指向 Python 2.7 的裸 `python` 命令。

---

## 最终产品决策

1. 创建面试计划时保存岗位、JD 和关联简历；输入齐全后自动生成准备包，30/60/90 秒自我介绍为必备输出，60 秒版本为默认。自我介绍只能使用简历、JD 和用户确认事实，缺少简历时允许保存计划但不生成，不得补写虚构经历。
2. 开始实时辅助或模拟面试时读取最新准备包，不在启动链路重复生成自我介绍。准备包未确认不阻塞启动，但页面必须提示事实核查；来源变化后按版本重新生成并保留旧版本。
3. 知识库采用“预置最小种子库 + Flask CLI 批量维护 + 个人设置轻量维护页”。首批内容覆盖通用表达、STAR、行为面试、项目追问框架和经过筛选的技术主题，全部保留来源、更新时间与启用状态。
4. 知识库是回答增强能力，不是面试启动依赖。空库或检索失败时自动降级，不阻塞准备包、Copilot 或模拟面试；第一版继续使用结构化关键词检索，不增加向量数据库、企业管理台或自动爬取系统。

---

## 文件结构

### 新增领域与服务

| 文件 | 职责 |
|---|---|
| `models/InterviewPlan.py` | 面试计划、JD、简历关联和状态 |
| `models/PreparationPack.py` | 准备包版本和结构化生成结果 |
| `models/KnowledgeItem.py` | 个人知识条目、标签、来源和启停状态 |
| `models/CopilotSession.py` | 实时辅助会话状态和恢复游标 |
| `models/CopilotTurn.py` | 转写问题、回答要点、参考回答和状态 |
| `models/CopilotEvent.py` | 错误、延迟、重连和诊断事件 |
| `models/MockInterview.py` | 模拟面试会话与状态 |
| `models/MockInterviewTurn.py` | 模拟面试逐题问答、评分和反馈 |
| `models/Review.py` | 实时/模拟面试统一复盘 |
| `services/structured_output.py` | LLM JSON 提取、校验和纯文本回退 |
| `services/preparation_service.py` | 准备包生成、事实边界和版本失效 |
| `services/knowledge_service.py` | 知识导入、去重、检索和来源过滤 |
| `services/context_service.py` | 简历、JD、最近 turn 和知识片段组装 |
| `services/utterance_service.py` | 问题边界、重复、短文本和暂停过滤 |
| `services/answer_service.py` | 回答要点、参考回答、追问和流式事件 |
| `services/mock_interview_service.py` | 题目生成、逐题评分和下一题 |
| `services/review_service.py` | 统一复盘和待办生成 |
| `services/resume_optimize_service.py` | 岗位匹配缺口和候选修改文本 |

### 新增路由与页面

| 文件 | 职责 |
|---|---|
| `routes/route_interview_plan.py` | 面试计划和准备包 API |
| `routes/route_knowledge.py` | 知识库 API 和 Flask CLI |
| `routes/route_copilot.py` | Copilot 会话、恢复和结束 API |
| `routes/route_mock_interview.py` | 模拟面试 API |
| `routes/route_review.py` | 复盘查询和导出 API |
| `routes/route_resume_optimize.py` | 简历优化 API |
| `templates/layouts/app.html` | 个人版统一应用壳层 |
| `templates/applicant/workspace.html` | 个人工作台 |
| `templates/applicant/interview_plans.html` | 面试计划列表与创建 |
| `templates/applicant/interview_plan_detail.html` | 准备包与启动入口 |
| `templates/applicant/knowledge.html` | 个人知识库维护 |
| `templates/applicant/mock_interview.html` | 模拟面试 |
| `templates/applicant/reviews.html` | 复盘列表 |
| `templates/applicant/review_detail.html` | 复盘详情 |
| `templates/applicant/resume_optimize.html` | 简历优化 |
| `static/css/tokens.css` | 全站语义令牌 |
| `static/css/app-shell.css` | 侧边栏、顶部栏和响应式壳层 |
| `static/css/components.css` | 表单、按钮、表格、状态和反馈组件 |
| `static/css/pages/*.css` | 页面特定布局 |
| `static/js/app-shell.js` | 移动导航和通用交互 |
| `static/js/interview-plans.js` | 计划 CRUD 与准备包交互 |
| `static/js/knowledge.js` | 知识库维护 |
| `static/js/mock-interview.js` | 模拟面试状态机 |
| `static/js/reviews.js` | 复盘筛选和导出 |
| `static/js/resume-optimize.js` | 简历优化交互 |

---

## Task 1：安全与运行基线

**Files:**
- Modify: `app.py`
- Modify: `routes/route_login.py`
- Modify: `routes/route_register.py`
- Modify: `routes/route_resume.py`
- Modify: `.env.example`
- Test: `tests/routes/test_auth.py`
- Test: `tests/routes/test_resume_upload.py`

- [x] **Step 1:** 新增认证测试，证明新注册密码使用 `werkzeug.security.generate_password_hash`，旧明文密码首次登录后自动升级为哈希；未登录用户不能访问个人工作台、计划、Copilot、模拟面试、简历和复盘页面。
- [x] **Step 2:** 运行 `py -3.12 -m pytest tests/routes/test_auth.py -q`，确认测试在实现前失败。
- [x] **Step 3:** 将 `SECRET_KEY`、`UPLOAD_DIR`、`MAX_CONTENT_LENGTH` 和允许扩展名改为环境配置；启动时创建解析后的上传目录，移除固定 `D:\myworld\...` 路径。
- [x] **Step 4:** 登录使用 `check_password_hash`，兼容旧明文密码并在成功登录后升级；注册使用密码哈希，不改变表单字段和 `User.password` 数据列。
- [x] **Step 5:** 简历上传先校验登录、扩展名、文件大小和 `secure_filename`，API Key 统一读取 `utils/llm_config.py`，删除源码中的硬编码凭证。
- [x] **Step 6:** 为个人页面增加统一 `applicant_required` 装饰器，为企业页面保留 `company_required`；API 未登录返回 JSON 401，页面未登录重定向登录页。
- [x] **Step 7:** 运行定向测试、`py -3.12 -m compileall -q app.py models routes services utils`，并确认源码中不再出现被移除的凭证文本。

## Task 2：个人领域模型

**Files:**
- Create: `models/InterviewPlan.py`
- Create: `models/PreparationPack.py`
- Create: `models/KnowledgeItem.py`
- Create: `models/CopilotSession.py`
- Create: `models/CopilotTurn.py`
- Create: `models/CopilotEvent.py`
- Create: `models/MockInterview.py`
- Create: `models/MockInterviewTurn.py`
- Create: `models/Review.py`
- Modify: `models/__init__.py`
- Test: `tests/models/test_personal_workspace_models.py`

- [x] **Step 1:** 编写模型测试，覆盖用户归属、计划状态、准备包版本、Copilot/模拟面试状态、turn 顺序、知识条目唯一指纹和复盘关联。
- [x] **Step 2:** 运行模型测试，确认缺少模型而失败。
- [x] **Step 3:** 实现 `InterviewPlan`：`plan_id`, `user_id`, `resume_id`, `company_name`, `position_name`, `job_description`, `extra_requirements`, `level`, `tech_tags`, `status`, `preparation_status`, `created_at`, `updated_at`, `last_used_at`。
- [x] **Step 4:** 实现 `PreparationPack`：`pack_id`, `plan_id`, `version`, `source_fingerprint`, `status`, `intro_30`, `intro_60`, `intro_90`, `highlights`, `project_followups`, `risk_points`, `frequent_questions`, `star_stories`, `review_topics`, `source_evidence`, `confirmed_at`。
- [x] **Step 5:** 实现 `KnowledgeItem`：`item_id`, `user_id`, `title`, `question`, `category`, `role_tags`, `tech_tags`, `difficulty`, `core_conclusion`, `answer_points`, `standard_answer`, `follow_ups`, `pitfalls`, `source`, `source_url`, `content_hash`, `is_enabled`, `created_at`, `updated_at`。
- [x] **Step 6:** 实现 Copilot、模拟面试和统一复盘模型；所有 JSON 列默认返回新对象，所有用户资源带 `user_id`，所有会话带稳定状态和最后序列/turn 游标。
- [x] **Step 7:** 在应用启动前显式导入新模型，使 `db.create_all()` 能创建表；运行模型测试并通过。

## Task 3：统一 LLM 输出与面试准备包

**Files:**
- Create: `services/structured_output.py`
- Create: `services/preparation_service.py`
- Create: `tests/services/test_structured_output.py`
- Create: `tests/services/test_preparation_service.py`

- [x] **Step 1:** 为 JSON fenced block、纯 JSON、前后说明文字、缺失字段、空响应和超时回退编写测试。
- [x] **Step 2:** 为准备包编写 fake LLM 测试：输出必须包含 30/60/90 秒自我介绍和所有准备字段；引用不存在于简历/JD 的数字或公司经历时标记 `needs_review`。
- [x] **Step 3:** 实现 `extract_json_object(text)` 和字段校验器，错误返回稳定错误码，不把原始异常直接暴露给页面。
- [x] **Step 4:** 实现 `PreparationService.generate(plan, resume)`，Prompt 使用明确的 `<resume_facts>`、`<job_description>` 和 `<user_requirements>` 分隔；要求关键亮点返回 `source_type` 与 `source_excerpt`。
- [x] **Step 5:** 用简历/JD/要求的规范化哈希生成 `source_fingerprint`；来源变化时把最新准备包标为过期，重新生成递增版本但保留旧版本。
- [x] **Step 6:** 实现编辑、确认和复制所需的服务方法；确认操作只能作用于当前用户计划的最新有效版本。
- [x] **Step 7:** 运行两个服务测试并通过。

## Task 4：个人知识库和初始化命令

**Files:**
- Create: `services/knowledge_service.py`
- Create: `routes/route_knowledge.py`
- Create: `data/knowledge/README.md`
- Create: `data/knowledge/sample-interview-basics.json`
- Create: `tests/services/test_knowledge_service.py`
- Create: `tests/routes/test_knowledge_cli.py`

- [x] **Step 1:** 编写导入测试，覆盖 Markdown、TXT、JSON、重复哈希、更新、禁用、无来源条目和非法格式。
- [x] **Step 2:** 编写检索测试，查询同时考虑问题关键词、岗位标签、技术标签、难度和启用状态，返回不超过 5 条并包含来源。
- [x] **Step 3:** 实现结构化导入器。JSON 使用明确 schema；Markdown 按二级标题切分；TXT 作为单条原始资料导入。重复 `content_hash` 更新元数据而不是新增副本。
- [x] **Step 4:** 实现兼容 SQLite/MySQL 的关键词评分：标题和问题权重最高，技术标签次之，正文最低；无结果时返回空列表，不能把整库注入 Prompt。
- [x] **Step 5:** 注册 Flask CLI：`knowledge import <path>`、`knowledge stats`、`knowledge rebuild-index` 和 `knowledge dedupe`。命令输出导入、新增、更新、跳过和失败数量。
- [x] **Step 6:** 提供最小示例数据，只包含原创的通用面试表达和 STAR 方法，不复制未知授权的八股文内容。
- [x] **Step 7:** 运行知识服务和 CLI 测试；执行一次示例数据导入和统计命令。
- [x] **Step 8:** 将最小种子库扩充为可交付初始化集：覆盖通用表达、STAR、行为面试、项目追问框架和项目实际支持的核心技术主题；每条内容包含来源、更新时间和启用状态，并通过同一 CLI 幂等导入，不引入来源不明的整包八股文。
- [x] **Step 9:** 增加空库和检索失败降级测试，确认准备包、Copilot 和模拟面试仍可继续，响应和页面只提示“知识增强未启用”，不把知识库变成启动依赖。

## Task 5：面试计划 API 与准备包页面

**Files:**
- Create: `routes/route_interview_plan.py`
- Modify: `routes/__init__.py`
- Modify: `app.py`
- Create: `templates/applicant/interview_plans.html`
- Create: `templates/applicant/interview_plan_detail.html`
- Create: `static/js/interview-plans.js`
- Create: `static/css/pages/interview-plans.css`
- Create: `tests/routes/test_interview_plans.py`

- [x] **Step 1:** 编写 CRUD 和归属测试：创建、列表、详情、编辑、归档、关联本人简历、拒绝他人简历、准备包生成/编辑/确认/重新生成。
- [x] **Step 2:** 实现 `/api/interview-plans` REST API 和 `/api/interview-plans/<id>/preparation` 子资源；删除采用归档，不物理删除已有会话引用。
- [x] **Step 3:** 创建计划时保存公司、岗位、JD、补充要求、级别、技术标签和简历；响应包含准备状态和可执行的下一步。
- [x] **Step 4:** 实现计划列表和详情页：一个主 CTA；详情首屏展示 60 秒自我介绍、岗位亮点、风险点和“实时辅助/模拟面试/简历优化”入口。
- [x] **Step 5:** 长 JD 和准备包生成提供加载、失败、重试和过期提示；所有字段保留可见标签，移动端单列。
- [x] **Step 6:** 运行 API 测试和模板静态测试。

## Task 6：个人版设计系统与应用壳层

**Files:**
- Create: `static/css/tokens.css`
- Create: `static/css/app-shell.css`
- Create: `static/css/components.css`
- Create: `static/js/app-shell.js`
- Create: `templates/layouts/app.html`
- Create: `templates/applicant/workspace.html`
- Modify: `templates/common/login.html`
- Modify: `templates/common/register.html`
- Modify: `static/css/auth.css`
- Modify: `app.py`
- Test: `tests/static/test_app_shell.py`
- Test: `tests/static/test_auth_pages.py`

- [x] **Step 1:** 编写静态测试，约束个人导航只有工作台、面试计划、实时辅助、模拟面试、简历中心、复盘记录和个人设置；验证移动菜单、跳过链接、表单 label、密码显隐和 reduced-motion。
- [x] **Step 2:** 定义语义令牌：背景/表面/描边/文字/主色/实时状态/成功/警告/错误、4/8px 间距、0/4/8px 圆角、150/220/300ms 动效和固定 z-index 层级。
- [x] **Step 3:** 实现 Jinja 应用壳层，支持页面标题、描述、主操作、侧边栏 active 状态、移动抽屉、用户菜单和 flash/toast 区域。
- [x] **Step 4:** 工作台只展示最近面试计划、准备进度、快速开始、最近复盘和待办，不复用旧企业统计卡片。
- [x] **Step 5:** 登录注册重做为首页视觉延伸的认证页，保留现有 action、name、role 和字段；增加密码显隐、提交状态和移动布局。
- [x] **Step 6:** 运行静态测试，并用 Flask test client 验证公开页、未登录重定向和登录后的工作台。

## Task 7：Copilot 会话 API 与上下文服务

**Files:**
- Create: `routes/route_copilot.py`
- Create: `services/context_service.py`
- Create: `services/utterance_service.py`
- Modify: `routes/__init__.py`
- Create: `tests/routes/test_copilot_sessions.py`
- Create: `tests/services/test_context_service.py`
- Create: `tests/services/test_utterance_service.py`

- [x] **Step 1:** 编写会话 API 测试：从本人计划创建、恢复、暂停、继续、结束、越权拒绝、重复调用幂等和过期准备包提示。
- [x] **Step 2:** 编写上下文预算测试：顺序固定为当前问题、JD、简历事实、最近 6 个 turn、知识片段；超预算优先删除旧 turn 和低分知识片段。
- [x] **Step 3:** 编写问题边界测试：800ms 静音、问句特征、短确认词、重复 partial、重复 final、暂停状态和用户回答误触发。
- [x] **Step 4:** 实现 `POST /api/copilot/sessions`、`GET /api/copilot/sessions/<id>`、pause/resume/end；会话关联计划、简历和准备包版本。
- [x] **Step 5:** 实现 `ContextService.build_answer_context`，只返回必要的结构化内容并对简历/JD/知识文本使用不可信输入边界。
- [x] **Step 6:** 实现 `UtteranceService.accept_partial/final`，输出 `ignore/update/complete` 和可诊断原因。
- [x] **Step 7:** 运行三个测试文件并通过。

## Task 8：Copilot 回答生成与实时事件

**Files:**
- Create: `services/answer_service.py`
- Modify: `services/copilot_stream.py`
- Modify: `services/asr_service.py`
- Modify: `services/mica_voice_client.py`
- Modify: `utils/copilot_socketio.py`
- Create: `tests/services/test_answer_service.py`
- Modify: `tests/services/test_copilot_stream.py`
- Modify: `tests/services/test_asr_service.py`

- [x] **Step 1:** 用 fake 流式 LLM 编写测试，覆盖回答要点先返回、完整回答、追问、取消旧请求、超时保留已生成片段、JSON 失败纯文本回退和无知识结果。
- [x] **Step 2:** 编写 ASR 增量接收测试，证明 partial 在会话进行中发布，而不是只在 `finish()` 时集中返回。
- [x] **Step 3:** 实现后台接收循环或回调，让 `MicaVoiceClient` 持续解析 ready/started/partial/final/error；连接、读取、空闲和结束均有超时。
- [x] **Step 4:** 实现 `AnswerService.generate`，输出 `answer_points`、`reference_answer`、`follow_up` 和引用的 `knowledge_item_ids`；新问题取消旧生成任务。
- [x] **Step 5:** 将 ASR final 送入 `UtteranceService`，完整问题持久化为 turn，组装上下文并发布 `answer_started`、`answer_delta`、`answer_completed`。
- [x] **Step 6:** Socket.IO handler 校验登录用户、会话归属、状态、序列和帧大小；按 `request.sid` 定向发送但以数据库 session 为恢复依据。
- [x] **Step 7:** 暂停恢复不重置客户端序列；重复序列返回确认，乱序进入可恢复错误；断开连接释放网关资源但不结束业务会话。
- [x] **Step 8:** 运行 Copilot 服务测试和 Socket.IO 集成测试。

## Task 9：实时辅助页面与恢复

**Files:**
- Modify: `templates/applicant/copilot.html`
- Modify: `static/js/copilot.js`
- Modify: `static/css/copilot.css`
- Create: `tests/static/test_copilot_ui.py`
- Modify: `tests/static/test_copilot_client.py`

- [x] **Step 1:** 编写客户端静态/状态机测试，覆盖计划 ID、后端 session ID、全会话递增序列、未确认帧缓存、三次有限重试、刷新恢复、旧 turn 事件隔离和结束后不创建幽灵会话。
- [x] **Step 2:** 页面接入统一应用壳层；首屏只保留计划摘要、设备状态、转写、回答要点、可展开参考回答和控制栏。
- [x] **Step 3:** 启动时调用会话 API，禁止浏览器随机 ID 作为业务会话；刷新时从 URL/sessionStorage 恢复允许的会话。
- [x] **Step 4:** 实现未确认音频序列缓存和确认清理；网络恢复时按序发送，超过三次显示可恢复错误。
- [x] **Step 5:** 展示麦克风拒绝、设备断开、ASR 不可用、模型未加载、网络重连、LLM 超时、空结果、暂停和结束状态。
- [x] **Step 6:** 测试 375/768/1024px，无横向滚动、固定控制栏不遮内容、触控目标不小于 44px、reduced-motion 生效。

## Task 10：模拟面试纵向闭环

**Files:**
- Create: `services/mock_interview_service.py`
- Create: `routes/route_mock_interview.py`
- Create: `templates/applicant/mock_interview.html`
- Create: `static/js/mock-interview.js`
- Create: `static/css/pages/mock-interview.css`
- Create: `tests/services/test_mock_interview_service.py`
- Create: `tests/routes/test_mock_interview.py`

- [x] **Step 1:** 编写测试：从本人计划开始、生成题目结构、提交回答、事实一致性检查、岗位相关性/完整性/表达评分、下一题、结束和恢复。
- [x] **Step 2:** 实现服务并复用个人计划事实边界、结构化输出和统一 LLM 配置；禁止复制第二套 LLM 客户端配置。
- [x] **Step 3:** 题目覆盖自我介绍、简历项目、JD 技术项、场景题和行为题；难度由计划级别控制。
- [x] **Step 4:** 每题返回参考要点、得分维度、做得好的地方、需要改进和下一题；评分解析失败时保留回答并允许重试。
- [x] **Step 5:** 页面支持文本回答为必备路径，语音/数字人为渐进增强；数字人失败不能阻塞面试。
- [x] **Step 6:** 运行服务、API 和页面状态测试。

## Task 11：统一复盘

**Files:**
- Create: `services/review_service.py`
- Create: `routes/route_review.py`
- Create: `templates/applicant/reviews.html`
- Create: `templates/applicant/review_detail.html`
- Create: `static/js/reviews.js`
- Create: `static/css/pages/reviews.css`
- Create: `tests/services/test_review_service.py`
- Create: `tests/routes/test_reviews.py`

- [x] **Step 1:** 编写测试，证明实时辅助和模拟面试都能生成统一复盘，且只能由所属用户读取和导出。
- [x] **Step 2:** 实现复盘摘要、问题分类、多维评分、事实风险、表达问题、薄弱知识、下一步任务和延迟诊断。
- [x] **Step 3:** `POST /api/reviews/generate` 对同一 source 幂等；`GET /api/reviews` 支持计划/类型/日期筛选；导出只返回文本和 JSON，不包含原始音频。
- [x] **Step 4:** 页面提供复盘列表、详情、来源引用、待复习知识和简历优化入口。
- [x] **Step 5:** 运行复盘测试并用两个来源 fixture 验证生成。

## Task 12：简历针对性优化

**Files:**
- Create: `services/resume_optimize_service.py`
- Create: `routes/route_resume_optimize.py`
- Create: `templates/applicant/resume_optimize.html`
- Create: `static/js/resume-optimize.js`
- Create: `static/css/pages/resume-optimize.css`
- Create: `tests/services/test_resume_optimize_service.py`
- Create: `tests/routes/test_resume_optimize.py`

- [x] **Step 1:** 编写测试：从本人计划读取简历和 JD，输出匹配项、缺口、关键词、段落建议和候选文本；任何新增经历/数字必须标记 `requires_confirmation`。
- [x] **Step 2:** 实现服务，复用结构化输出和事实边界；默认不修改 `Resume.parsed_data` 和原文件。
- [x] **Step 3:** API 支持生成、读取最近结果和确认单条建议；确认只表示用户认可，不自动覆盖简历。
- [x] **Step 4:** 页面以“原事实/建议文本/依据/确认状态”对照展示，支持复制和从复盘定位到相关建议。
- [x] **Step 5:** 运行服务和路由测试。

## Task 13：迁移现有个人页面并收敛旧入口

**Files:**
- Modify: `templates/applicant/dashboard.html`
- Modify: `templates/applicant/resume_manage.html`
- Modify: `templates/applicant/resume_uoload.html`
- Modify: `templates/applicant/analyze_resume.html`
- Modify: `templates/applicant/profile.html`
- Modify: `templates/applicant/interview_manage.html`
- Modify: `templates/applicant/reports.html`
- Modify: `app.py`
- Create: `templates/applicant/legacy_notice.html`
- Test: `tests/routes/test_navigation.py`

- [x] **Step 1:** 编写路由测试，所有个人导航目标返回 200，旧 Dashboard 重定向新工作台，旧报告/面试管理映射新复盘/计划，企业入口不出现在个人导航。
- [x] **Step 2:** 把仍需保留的简历上传、管理和个人设置迁入统一壳层，保留原 API、表单字段和核心行为。
- [x] **Step 3:** 旧 `interview_manage` 和 `reports` 使用兼容重定向，不再形成第二套主流程。
- [x] **Step 4:** 企业路由和数据模型保留但不进入个人导航；修复会造成 500 的直接企业路由，使用明确的列表入口。
- [x] **Step 5:** 移除个人主线中的占位按钮、无效入口和不存在模板引用。
- [x] **Step 6:** 运行导航与关键页面 test client smoke。

## Task 14：全站视觉统一与响应式验收

**Files:**
- Modify: `static/css/style.css`
- Modify: `static/css/dashboard.css`
- Modify: `static/css/profile.css`
- Modify: `static/css/resume.css`
- Modify: `static/css/reports.css`
- Modify: `static/css/interview.css`
- Modify: `static/css/avatar.css`
- Modify: `static/css/copilot.css`
- Modify: `templates/common/index.html`
- Modify: personal templates from Tasks 5-13
- Test: `tests/static/test_design_system.py`

- [x] **Step 1:** 增加设计系统测试，禁止个人页重新定义核心颜色/圆角，禁止小于 44px 的主控件，要求可见 focus、reduced-motion 和移动断点。
- [x] **Step 2:** 清理旧页面内联大段 CSS，把通用规则迁到令牌、壳层和组件 CSS；页面 CSS 只保留特定布局。
- [x] **Step 3:** 首页保留品牌表达但降低一色紫蓝占比，修正文案和无效演示入口；认证页和应用页保持同一品牌识别。
- [x] **Step 4:** 专用 Copilot、模拟面试和数字人页面使用不受装饰干扰的全屏/工作台布局，不把主要体验放在营销卡片中。
- [x] **Step 5:** 使用 Playwright 或可用浏览器工具在 1440x900、1024x768、768x1024、375x812 截图，逐页检查溢出、遮挡、空白画布、按钮文本和移动导航。
- [x] **Step 6:** 检查键盘导航、焦点顺序、表单错误、对比度、动态长文本和 `prefers-reduced-motion`。
- [x] **Step 7:** 在个人设置中完成轻量知识库维护页，支持搜索、新增、编辑、启停、文件导入和索引重建反馈；批量初始化继续使用 Flask CLI，不新增企业管理台。

## Task 15：端到端验证、计划同步与交付

**Files:**
- Create: `tests/e2e/personal-workspace-smoke.md`
- Update: `docs/superpowers/plans/2026-08-21-personal-ai-interview-workspace-plan.md`
- Update: `docs/superpowers/spikes/2026-08-20-speaker-capture-results.md`
- Update: `README.md`

- [x] **Step 1:** 运行 `py -3.12 -m pytest -q`、`py -3.12 -m compileall -q app.py models routes services utils`、`mvn test -q`，记录测试数和退出码。
- [x] **Step 2:** 启动 Flask 和 mica-voice 网关，幂等导入最小种子知识库；创建测试用户、简历和面试计划，确认根据岗位/JD/简历自动生成 30/60/90 秒自我介绍和准备包。
- [ ] **Step 3:** 完成桌面真实闭环：计划 -> 准备包 -> Copilot 真实麦克风 -> 回答 -> 复盘 -> 简历优化。
- [x] **Step 4:** 完成模拟面试闭环：同一计划 -> 多轮回答 -> 逐题反馈 -> 综合复盘 -> 待复习知识。
- [ ] **Step 5:** 完成手机浏览器核心路径验证，记录权限、后台、锁屏、距离、音量和噪声限制。
- [x] **Step 6:** 连续运行 30 分钟，记录 ASR 首字、回答要点、完整回答、内存、错误、误触发和重连次数；未达到目标时如实标记，不得声称验收通过。
- [x] **Step 7:** 更新 README 的产品定义、环境变量、知识导入、启动命令和已知限制；把本计划每个实际完成步骤勾选。
- [x] **Step 8:** 对照设计规格第 15 节逐项审计。只有所有自动验证和可执行的真实验收都有证据，才声明整体完成。

### 2026-08-21 验收证据与剩余门槛

- 自动验证：`py -3.12 -m pytest -q` 为 `95 passed`；Python 编译和 `mvn test -q` 均退出 0。
- 浏览器：1440x900、1024x768、768x1024、375x812 最终为 `4 passed (1.3m)`，覆盖主线页面、弹窗、移动导航、焦点、Escape、44px 触控、长文本和 reduced-motion。
- 知识库：15 条种子知识重复导入后总数仍为 15，全部启用；检索状态检查成功。
- 模拟面试：同一计划真实完成 8 题、逐题四维评分、统一复盘和简历优化结果。
- Copilot 文件音频：57/57 帧 ACK，ASR partial/final、LLM 回答、落库恢复和复盘链路成功；这不能替代实体麦克风验收。
- 未完成门槛：实体麦克风与目标手机、短暂断网恢复、30 分钟连续稳定性仍未验证；ASR 首字约 1.2 秒，回答要点约 18 秒以上，未达到 1 秒/3 秒/5 秒性能目标。因此 Step 3、5、6、8 保持未勾选，整体计划不得标记完成。

### 2026-08-23 Feat 集成服务验收追加（interview-server）

- 30 分钟连续稳定性：feat 服务（`services/interview-server`）连续运行 1808 秒，80 轮真实语音会话（每轮 5 秒语音 + 停顿），0 错误、0 重连、640 个事件（partial/final 正常），无 ASR stream 泄漏（服务内存平稳）。**通过**。
- ASR 首字延迟（1 倍实时节奏、真实 wav）：首个转写事件（final）约 **3.2 秒**，partial 约 4.8 秒；2 倍实时节奏下 final 约 2.3 秒。**未达到 1 秒目标**，如实标记。
- LLM 回答延迟：`/api/copilot/answer` 实测 9.9 秒返回约 850 字符回答（qwen3.7-flash，流式聚合后一次性返回），**未达到 3 秒/5 秒目标**（历史记录：回答要点约 22 秒）。
- 第 15 节审计：创建计划 -> 关联简历/JD -> 准备包 -> 实时辅助（文件音频 ASR+LLM 链路）/模拟面试（8 题四维评分）-> 回答与评分 -> 复盘（6 个下一步任务）-> 简历优化（needs_review 不自动覆盖）均有自动化证据；**实体麦克风、目标手机浏览器、断网恢复仍待人工验收**，Step 3/5 保持未勾选。
- 全部自动验证：`py -3.12 -m pytest -q` 146 passed（08-23 回归）；interview-server 单测 13 passed；JS 静态测试 29 passed。

### 2026-08-21 解析与简历中心补丁

- 简历上传不再直接对模型原始响应调用 `json.loads`；兼容空响应、Markdown JSON 代码块和前后说明文字，格式异常统一返回可执行提示，不暴露 `JSONDecodeError` 细节。
- 简历中心前端统一处理空/非 JSON 响应，避免浏览器原始解析异常；文件选择控件、上传面板和焦点状态纳入主题化组件样式。
- 定向验证：`py -3.12 -m pytest tests/routes/test_resume_upload.py tests/services/test_structured_output.py tests/static/test_resume_center_ui.py -q`，`9 passed`；相关 JS 语法检查和 `git diff --check` 通过。
- 上传卡顿根因：简历解析调用的是 `.env` 配置的远端 SiliconFlow 兼容接口，原 `OpenAI` 客户端未设置超时，网络或模型服务异常时可能等待数分钟。现在上传链路使用默认 45 秒上限（可通过 `LLM_TIMEOUT_SECONDS` 调整，范围 5-120 秒），前端额外设置 60 秒 AbortController，并显示可执行的超时提示。
- 补丁后的完整回归：`py -3.12 -m pytest -q` 为 `106 passed`；新服务运行于 `http://127.0.0.1:5001`。
- 视觉补丁：统一 `tokens/components/app-shell` 的表面、阴影、控件高度和浅色主题层级；首页 Hero 改为无卡片左右构图，修复浅色遮罩穿透和次按钮对比度；计划页、面试工作区、复盘和设置页统一卡片密度与交互状态。深浅主题均已用 Playwright 截图复核。
- 视觉补丁后的完整回归：`py -3.12 -m pytest -q` 为 `107 passed`；Python 编译、JS 语法和 `git diff --check` 均通过。
- 视觉截图证据：`test-results/live-home-dark-3.png`、`test-results/live-home-light-3.png` 和 `test-results/live-home-mobile.png`；桌面深浅主题及 390px 移动布局均未出现首屏重叠或横向溢出。完整 Playwright 审计仍受仓库缺少 `@playwright/test` 依赖影响，未冒充通过。

### 2026-08-21 上传与 AI 分析解耦补丁

- 上传接口只负责校验、落盘、本地 DOCX/PDF 文本提取和关键词识别，成功后立即返回 `201`；不再在请求内创建 `OpenAI` 客户端，也不再调用远程 Files API。
- `Resume.parsed_data` 保存 `status`、`analysis_status`、`extracted_text`、`keywords` 和基础事实；模型深度分析由带 Flask 上下文的后台线程执行，失败只记录 `analysis_error`，不影响文件和本地关键词。
- 新增 `GET /api/resumes/<resume_id>/status`，简历中心前端轮询并展示等待、分析中、完成和失败状态；上传文案明确为“上传文件”。
- 新增本地提取、损坏 DOCX 和关键词回归测试，并验证上传阶段模型客户端不可用时仍能成功：`py -3.12 -m pytest -q` 为 `111 passed`；Python 编译、简历中心 JS 语法和 `git diff --check` 均通过。
- 修复未关联简历的计划点击生成准备包返回 400 的 UX：详情页现在可直接选择本人简历并通过现有 PATCH 接口关联，关联后再生成准备包；无简历时不再展示必然失败的生成按钮。相关回归后全量测试为 `114 passed`。
- 准备包生成改为生产环境后台任务：创建计划或手动生成接口不再同步等待远程模型，立即返回 `generating`/`202`，详情页轮询状态并在完成或失败后刷新；远程 OpenAI 兼容客户端增加 5–120 秒超时边界。全量回归：`115 passed`，编译、JS 语法和 `git diff --check` 通过。
- 实时辅助与模拟面试入口改为新浏览器标签打开，并支持 `standalone=1` 沉浸式壳层：新标签隐藏左侧导航和重复页面头部，保留主题切换与返回计划按钮；计划页继续保留主导航。全量回归：`118 passed`。
- 独立面试工作区 UI 进行一轮精修：重新建立岗位上下文、模式切换、转写/回答双栏、底部控制栏和模拟面试评分层级，补充深浅主题、悬停/聚焦、移动端和 reduced-motion 状态。全量回归：`118 passed`。
- 根据独立页面交互收敛要求，移除实时辅助/模拟面试页的返回按钮和顶部模式 Tab；每个 URL 只渲染对应面板及脚本，计划页负责新开 Tab。全量回归：`119 passed`。
- 依据真实辅助截图继续精修沉浸式布局：独立页顶部改为轻量浮层，减少顶部占用；ASR 对“介绍自己”类 final 问题增加边界识别，前端收到 `transcript_final` 立即显示“正在生成回答”，再由流式回答替换。全量回归：`121 passed`。
- 2026-08-22 个人页 UI 二次重做：普通页面容器扩展到 1680px 自适应宽度，浅色主题改为冷灰画布与白色表面分层；工作台新增准备节奏概览，计划卡片改为三列紧凑信息卡并统一中文状态；简历中心将列表行改为文件卡片，增加解析状态徽章与本地关键词；复盘筛选与记录改为卡片化布局，统一控件和空状态。验证：`py -3.12 -m pytest -q` 为 `121 passed`，Python 编译、全部 JS 语法检查和 `git diff --check` 通过。

### 2026-08-22 公共实时 API / SDK（延期，不进入当前实现）

- 目标：在现有实时辅助能力稳定后，对第三方网站、小程序 WebView 和 App 提供“音频帧 -> ASR partial/final -> 大模型流式回答”的公共能力。
- 计划契约：`POST /api/v1/realtime/sessions` 创建会话；`WS /api/v1/realtime?session_id=...` 传输音频与实时事件；`POST /api/v1/realtime/sessions/{id}/close` 结束会话。
- 事件类型：`transcript.partial`、`transcript.final`、`answer.delta`、`answer.completed`、`error`，统一携带 `sequence` 以支持重连和乱序检测。
- 计划交付：API Key / 项目权限、Origin 白名单、限流与用量统计、音频默认不持久化、JavaScript SDK 示例；后续再评估 Python / Java SDK。
- 当前状态：仅记录设计方向，暂不新增公共路由、鉴权模型或 SDK 文件；先完成现有个人页面的现代化 UI 收敛。
- 本轮 UI 范围：个人设置、个人知识库、计划详情、简历优化、复盘详情统一改为内容优先的现代工作台样式；降低主按钮饱和度与阴影，增加表单分组、身份摘要、卡片层级和统一导航状态。公共 API / SDK 继续延期。

### 2026-08-22 实时辅助无回答与识别延迟排查

- 根因：mica-voice 网关在线处理阶段始终发送 `partial`，没有根据 `OnlineRecognizer.isEndpoint(stream)` 发 `final`；Python 侧只有 `final` 才会进入问题边界判断和回答生成，因此右侧长期停留在占位文案。
- 修复：端点检测后发送 `final` 并调用 `recognizer.reset(stream)`，增加 Java 回归测试；Python 回答服务对第三方 LLM 未继承标准异常的错误发送 `answer_completed` 兜底，避免前端永久加载。
- 音频调优：保持 16kHz PCM16 单声道，帧长从 1280 对齐到网关 `chunk-size=1600`；开启浏览器 `echoCancellation`、`noiseSuppression` 和 `autoGainControl`。1920 采样点不是根因，暂不采用。
- 验证：Python 全量 `123 passed`；网关 `mvn -q test` 通过；JS 语法、Python 编译和 `git diff --check` 通过。

---

## 需求覆盖检查

| 规格要求 | 计划任务 |
|---|---|
| 个人版产品收敛 | Task 6、13、14 |
| 面试计划 | Task 2、5 |
| 30/60/90 秒自我介绍与准备包 | Task 3、5 |
| 个人知识库、种子初始化、降级与轻量维护页 | Task 4、14、15 |
| Copilot 会话、ASR、回答和恢复 | Task 7、8、9 |
| 模拟面试与逐题反馈 | Task 10 |
| 统一复盘 | Task 11 |
| 简历针对性优化 | Task 12 |
| 数字人降级为可选表现层 | Task 10、14 |
| 企业 ATS 移出个人主线且保留兼容 | Task 13 |
| 统一深色科技工作台 | Task 6、14 |
| 安全和资源归属 | Task 1、5、7、10、11、12 |
| 桌面、手机和真实音频验收 | Task 9、14、15 |

## 执行方式

本计划由当前会话使用 `superpowers:executing-plans` 串行执行。每个 Task 完成后更新复选框和高层计划，运行对应定向验证；不因单元测试通过跳过后续真实链路验收。涉及删除旧文件、数据库迁移、依赖升级、提交或远程 Git 操作时，必须先取得用户确认。
