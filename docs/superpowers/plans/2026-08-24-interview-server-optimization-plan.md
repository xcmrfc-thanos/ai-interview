# interview-server 全面优化计划（UI + 代码 + Bug）

> **状态：已废弃**（由 2026-08-29 双优化计划取代并完成）。

- 制定日期：2026-08-24
- 版本：**v2（2026-08-24 自检修订）** — v1 的诊断是对**更早的工作区快照**写的，本次逐条读源码核验，删除已修复项、修正 3 处会失败的技术方案、补 6 个新发现。
- 适用范围：`services/interview-server`（Java 8 单体：feat 2.4.0 + MyBatis/SQLite + Thymeleaf 壳 + Vue3/NaiveUI）
- 执行方式：**逐任务顺序执行**，每个任务自带「目标 / 改哪些文件 / 具体步骤 / 验证 / 完成勾选」。一次只做一个任务，做完验证通过再做下一个。

---

## 0. v2 自检结论（先看这段，避免做无用功）

### 0.1 v1 中「已经修好了、不要再做」的任务（已删除或降级）

| v1 编号 | v1 的说法 | 核验结果 |
|---|---|---|
| BE-NAV-1 / T1.6 | company 登录跳未注册的 `/company/dashboard` | **已修**：`AuthRoutes.java:57-58` 已统一跳 `/applicant/workspace`。但衍生出新问题，见 BE-NAV-2 |
| FE-UI-2 / T4.3 | `workspace.html` 未用 shell、内联 40 行侧栏 | **已修**：首行就是 `th:replace="~{layouts/app :: shell('工作台','workspace')}"`，无内联侧栏，只剩 10 行卡片 `<style>` |
| FE-DRY-1 / T3.3 | 7 个页面手写 `createApp` 样板 | **已修**：8 个 Vue 页全部已调 `mountApp(...)`，只剩一行未使用的 `createApp` 解构 |
| FE-DEAD-1 / T4.1 | `auth.js`/`settings.js`/`voice-profile.js` 是死代码 | **不成立**：三者的 `data-*` / `id` 钩子在 `login.html`、`register.html`、`profile.html` 里全部存在并生效 |
| FE-DEAD-1 | `auth.css` 变量未 link、类名不匹配 | **不成立**：`login/register.html` 先 link `tokens.css` 再 link `auth.css`，`.auth-*` 类名与 CSS 一一对应 |
| FE-DEAD-1 | `landing.css` 无引用可删 | **不成立**：`common/index.html:13` 正在使用 |
| FE-DEAD-1 | `register.html` 重复 Bootstrap link、`../js/auth.js` 路径错、`btnshowCompany` 绑错 | **已修/已不存在**：Bootstrap link 只有一处，脚本是绝对路径 `/static/js/auth.js`，全库搜不到 `btnshowCompany` |
| FE-A11Y-1 | 登录/注册只有 placeholder 无 `<label>` | **已修**：每个 input 都有 `<label for>`，另有 `role="alert"` / `aria-live="polite"` / skip-link |
| FE-A11Y-1 | `index.html` 三段重复 demo 脚本 | **不成立**：只有 1 段内联脚本（打字机），且 `index.js` 里无 `.play-button` 死代码 |
| T4.2 | 删除 `style.css` / `index.css` | **已删**：文件已不存在（git status 显示 `D`） |

### 0.2 v1 中「方案本身会失败、必须换做法」的 3 处

1. **T1.1 无法在 `WebSocketUpgrade` 内拒绝握手**。`WebSocketUpgrade.init(HttpRequest,HttpResponse)` 是 `final`，`onHandShake(...)` 在 101 响应**之后**才触发。正确做法：在**路由 lambda** 里升级前鉴权（见新 T1.1）。
2. **T2.3 HikariCP `maximumPoolSize=1` 必然死锁**。每个 DAO 方法自己调 `MyBatis.open()`；事务持有 1 号连接时嵌套 DAO 再取连接 → 池耗尽永久阻塞。且 HikariCP 5.x 要求 Java 11，本项目是 Java 8。正确做法：不引池，改 DAO 接受 `SqlSession` 重载（见新 T2.3）。
3. **T5.1 「音频帧里已带 mode/source」是错的**。上行音频是**裸二进制 PCM 帧**，不含任何元数据。真实来源只能从控制帧（`copilot_start` / `copilot_set_speaker`）跟踪（见新 T5.1）。

### 0.3 v2 新增的 6 个发现

- **BE-BUG-2（真 bug，直接堵住 Phase 5 验收）**：`CopilotWs.handleSetSpeaker` 里 `sid != sessionId` 是 `Long` **引用比较**。session_id > 127（超出 Long 缓存）时永远不相等 → 切换音频来源必定返回「会话不匹配」。
- **BE-BUG-3**：`MockRoutes.handleAnswer` 取 `MockDao.findTurn(...)` 未判空，紧接着 `turn.get("question")` → 数据不一致时 NPE 500。
- **BE-NAV-2**：company 账号登录 → 跳 `/applicant/workspace` → `requireApplicant` 失败 → 静默跳回 `/loginView`，无任何提示，用户看到「登录了但又回到登录页」的死循环。
- **BE-LLM-2**：`Llm.chat` / `AnswerService.answer` 的 `final boolean[] settled` 非 volatile，回调线程与调用线程之间无可见性保证。
- **FE-DEAD-2（真死代码）**：Bootstrap CDN 共 5 处引用（`login.html` CSS+JS、`register.html` CSS+JS、`layouts/app.html` JS），全库 **0 个** Bootstrap 类名、0 个 `data-bs-*` → 纯白吃 3 个 CDN 请求。
- **FE-NAV-1**：`layouts/app.html` 的「面试」与「模拟面试」两项共用 `activePage == 'interview'`，进任一模式两项同时高亮。

### 0.4 核验基线数据（v1 缺失，执行时以此为准）

- Java 单测：**16 个**（`AnswerServiceTest` 3 + `AsrWebSocketUpgradeTest` 9 + `Pcm16DecoderTest` 2 + `TranscriptRulesTest` 2），来自 `target/surefire-reports`。
- 无文件超 300 行硬上限的严重违规，最大 `PlanRoutes.java` 349 行、`ReviewRoutes.java` 318 行。
- `slf4j-api 1.7.36` **已在 classpath**（`mica-voice-core` 传递依赖），只缺 binding。
- 加载 2.4MB vendor 包的是 **8 个**页面，不是 9 个；`profile.html` 已经是纯原生实现，可当作目标设计系统的样板页。
- `/api/copilot/answer` 在 `InterviewServerApplication.java:57` 注册，**全库无任何前端/脚本消费者**。

---

## 1. 背景（执行前必读，30 秒看懂这是什么）

`services/interview-server` 是一次**融合迁移**的产物，把两个旧东西合并进了**一个 Java 进程**：

1. **根目录 Flask 项目**（`app.py` + `routes/` + `templates/` + `static/`）——页面与业务（注册登录、简历、面试计划、准备包、实时辅助 Copilot、模拟面试、复盘、知识库）。
2. **`services/mica-voice-gateway`**（Spring Boot 流式 ASR 网关）——`/mica/voice/ws/online-asr` 语音转写。

融合证据：`InterviewServerApplication.java` 在同一个 `Router` 上同时注册了业务/页面路由和 ASR/Copilot WebSocket（`:35` ASR WS、`:38-53` 各 Routes、`:48` `/ws/copilot`、`:57` `/api/copilot/answer`）。

**当前状态（v2 核验后）**：前端的「三套 UI 并存」已经收敛掉一大半——登录/注册/首页/工作台/个人设置都已是原生设计系统，剩下的 8 个 Vue 页仍依赖 NaiveUI。后端还欠 2 个高危安全洞、3 个真实 bug、大量样板重复。本计划终点：**修安全与 bug → 消后端重复 → 前端请求健壮化 → 8 个 Vue 页收敛到单一设计系统 → 采集来源真实透传并显示**。

### 四条铁律（违反会直接损坏功能，任何任务都不得触碰）

1. **不改 ASR WS 协议**：`/mica/voice/ws/online-asr` 与 Python 侧 100% 兼容，帧格式/事件名一个字都不能动（`AsrWebSocketUpgrade.java` 的 `config/start/stop/close` 与 `ready/config-ack/started/...` 全部冻结）。
2. **不删 `-Dfile.encoding=UTF-8`**：`start.ps1:47` 这个参数去掉会导致中文流式 JSON 解析乱码。
3. **重打 vendor 包必须用含编译器的完整版 Vue**（见 `temp/vendor-build/build.ps1`），否则所有页面「菜单在、内容空白」。
4. **不要试图在 `WebSocketUpgrade` 子类里拒绝握手**：`init(...)` 是 `final`，`onHandShake(...)` 在 101 之后执行。鉴权只能放在路由 lambda 的 `ctx.Request.upgrade(...)` **之前**。

---

## 2. 现状诊断（v2 已逐条读源码核验；行号仅供定位，**以方法名为准**）

### 2.1 后端（Java）

- **[高·安全] BE-SEC-1a**：`/ws/copilot` 升级前不校验登录（`InterviewServerApplication.java:48`）；`CopilotWs.handleStart` 用 `CopilotDao.findSessionAnyUser(sid)`（`CopilotWs.java:131`）不校验归属 → 匿名可连 + 越权读写他人会话（IDOR）+ 白嫖 LLM。
- **[高·安全] BE-SEC-1b**：`/api/copilot/answer`（`InterviewServerApplication.java:57`）无鉴权，匿名 GET 即触发 LLM。**该端点全库无消费者**。
- **[高·bug] BE-BUG-1**：`MockRoutes.handleAnswer` 评分失败分支 `error(response, 503, body.toJSONString())`（`:139`）；`error()` 内部又 `JSON.toJSONString(message)`（`:239`）→ 双重编码，前端拿到被转义的字符串、`evaluation` 结构丢失。
- **[高·bug] BE-BUG-2**：`CopilotWs.handleSetSpeaker`（`:174`）`sid != sessionId` 用 `!=` 比较两个 `Long` → session_id > 127 时永远走「会话不匹配」，音频来源切换功能实际不可用。
- **[中·bug] BE-BUG-3**：`MockRoutes.handleAnswer`（`:123`）`MockDao.findTurn` 结果未判空，`:130` `turn.get("question")` NPE。
- **[中·安全] BE-SEC-2**：会话 Cookie 只设 `HttpOnly`（`AuthRoutes.java:52-56`），缺 `SameSite`/`Secure`，写操作无 CSRF 防护。
- **[中·安全] BE-SEC-3**：异常原文回传客户端，**共 9 处**：`ReviewRoutes:104,210`、`CopilotWs:91,116,239`、`InterviewServerApplication:65`、`AsrWebSocketUpgrade:81`、`MockInterviewService:93`、`ResumeAnalyzer:61`；`Db.java:48,69,91` 把 SQL 原文塞进异常消息；`System.out`/`printStackTrace` 共 10 处（`AsrEngine`、`InterviewServerApplication`、`ResumeExtractor`、`CopilotWs`），其中 `CopilotWs:210,219,221` 直接打印面试问题原文。
- **[中·可维护] BE-DRY-1**：`parseBody / parseLong / ok / error / parsePayload` 在 `KnowledgeRoutes / MockRoutes / CopilotRoutes / ReviewRoutes / ResumeRoutes / PlanRoutes / ProfileRoutes` 里逐字复制；`session == null → 401` 4 行块重复约 30 次；无一处显式设 `Content-Type: application/json`。
- **[中·正确性] BE-DB-1**：`MyBatis.open()` 固定 `openSession(true)` 自动提交（`:45`），多步写无事务；`SqliteDataSource.getConnection` 每次 `DriverManager.getConnection`（`:58`）无复用。
- **[中·稳定] BE-LLM-1**：`Llm.chat` 与 `AnswerService.answer` 是近乎逐行重复的 ~40 行；超时只置 `settled` 标志丢弃回调，不取消底层流；`CopilotWs.ANSWER_EXECUTOR` 固定 2 线程、`PlanRoutes.PREP_EXECUTOR` 单线程且队列无界。
- **[中·并发] BE-LLM-2**：上述 `final boolean[] settled` 非 volatile，跨线程可见性无保证。
- **[中·功能] BE-CAP-1**：`CopilotWs.publishTranscript`（`:197-198`）把 `source`/`speaker` 硬编码成 `"mixed"`/`"interviewer"`，`handleStart` 完全忽略前端传来的 `mode`/`sources`。
- **[中·导航] BE-NAV-2**：company 账号登录 → `/applicant/workspace` → `requireApplicant` 失败 → 静默 302 回 `/loginView`，无提示，表现为登录不进去。
- **[低] BE-MISC**：`AppConfig.appSecretKey` 死配置且硬编码默认值（`:56-59`，无任何消费点）；`SessionStore` 只在 `get()` 时惰性过期、无清扫，废弃 token 永驻内存；「7 天」在 `AuthRoutes:54` 与 `SessionStore:14` 各写一遍；`Db` 的 query API 只剩 `ResumeRoutes:136` 一个消费者。

### 2.2 前端（模板 + 静态资源）

- **[高·健壮] FE-ROBUST-1**：8 个模板共 **23 处 `fetch(`**，只有 **1 处 `.catch`**、**0 处 `finally`**。网络错误时 `loading` 永久卡在转圈。这是当前前端最值得先修的问题。
- **[中·架构] FE-UI-1**：UI 体系收敛已完成一半——`login/register/index/workspace/profile` + `layouts/app` 已是 `tokens.css + components.css + app-shell.css + auth.css + landing.css + pages/settings.css` 原生体系；剩下 **8 个 Vue 页仍挂 NaiveUI**（`interview_plans / interview_plan_detail / interview_workspace / knowledge / resume_manage / resume_optimize / reviews / review_detail`）。边界清晰，不再是「三套混战」，而是「原生已站住，NaiveUI 待撤」。
- **[中·债务] FE-DEAD-2**：Bootstrap CDN 5 处引用（`login.html:11,80`、`register.html:11,146`、`layouts/app.html:46`），全库 0 个 Bootstrap 类名与 `data-bs-*`，纯粹白吃请求。
- **[中·性能] FE-PERF-1**：8 个页面各自**同步**加载 2.4MB `vendor/naive-ui.min.js`（无 `defer`、无强缓存、无内容 hash）。
- **[中·重复] FE-DRY-2**：`parseList`/`parseObj` 在 `interview_plan_detail.html:112`、`review_detail.html:49-50`、`resume_optimize.html` 各写一份。
- **[低] FE-DRY-3**：8 个 Vue 页仍留着 `var { createApp, ... } = window.Vue;`，`createApp` 已由 `mountApp` 接管，是未使用变量。
- **[低·导航] FE-NAV-1**：`layouts/app.html` 「面试」与「模拟面试」共用 `activePage == 'interview'`，两项同时高亮。
- **[低] FE-MISC**：`login.html` 的 `#rememberMe` 复选框无任何后端语义（点了不生效）；`profile.html` 把 `<link rel="stylesheet">` 放在 content fragment 内而非 `<head>`。

---

## 3. 目标与验收标准（做完长这样）

1. **安全**：`/ws/copilot` 升级前必须登录且会话按 `user_id` 归属校验，越权返回明确错误；`/api/copilot/answer` 下线或加鉴权；Cookie 带 `SameSite=Lax`。
2. **无已知 bug**：BE-BUG-1/2/3 全部修掉——评分失败分支结构正确、音频来源切换真正生效、`findTurn` 判空。
3. **前端不卡死**：所有请求走统一 `api.js`，失败有可见提示，`loading` 一定在 `finally` 复位。
4. **无重复样板**：后端公共逻辑收敛到 1 个 `RouteSupport`；前端 `parseList/parseObj` 收敛到 `utils.js`。
5. **单一 UI 体系**：8 个 Vue 页按批次撤掉 NaiveUI，全部改用 `components.css`；Bootstrap CDN 5 处全删；最终删除 `vendor/naive-ui.min.js`。若中途停在半途，**必须在本文档 §11 偏离记录里明确标注当前边界**。
6. **采集状态可见**：Copilot 页显示连接/麦克风/会话三状态 + 当前采集来源，后端透传真实 `source`，`speaker` 明确标注为推测。
7. **每一步都验证过**：编译 + 16 个单测 + `start.ps1` 起得来 + `/api/health` ok + 关键页面手工点通。

### 验证命令速查（每个任务结尾都会用到）

```powershell
# 1) 编译（最常用；依赖已下全时可加 -o 离线）
cd services/interview-server; mvn -q -DskipTests package
# 2) 跑单测（基线 16 个：3+9+2+2，必须不减少）
cd services/interview-server; mvn -q test
# 3) 启动服务（另开终端保持运行；已内置端口占用自动清理）
powershell -File services/interview-server/start.ps1
# 4) 健康检查
curl http://localhost:18081/api/health   # 期望 {"status":"ok"}
# 5) Flask 回归（确认没碰坏 Python 侧，基线 146 passed）
py -3 -m pytest tests/ -q
```

---

## 4. 执行总原则（照做，别自由发挥）

1. **一次一个任务**：从 Phase 0 开始严格按编号。做完跑该任务的「验证」，绿了打勾再进下一个。
2. **行号会漂移，按方法名定位**。v1 的行号已经普遍偏移 2~20 行；本文档行号同样只是提示，改前先打开完整读一遍目标方法。
3. **改前先确认问题还在**。v2 已经删掉 10 条过期诊断，但工作区仍在变动——每个任务第一步先复现/确认，确认不成立就直接勾掉并在 §11 偏离记录里记一行。
4. **小步提交**：每个 Phase 结束一次 commit，`<type>(interview-server): <中文摘要>`。
5. **禁止扩大范围**：任务没写的别改，顺手发现的记到 §10「附加待办」。
6. **遇到与四条铁律冲突，立即停手上报。**

---

## 5. 分阶段任务清单（核心，按顺序执行）

> 优先级：Phase 1（安全+bug）→ Phase 2（后端重构）→ Phase 3（前端健壮性）→ Phase 4（UI 收敛）→ Phase 5（采集显示）→ Phase 6（收尾）。
> Phase 1 与 Phase 3 是「小改动、高收益」，Phase 4 是最大的一块可分批。

### Phase 0 — 建立基线（先证明「现在能跑」）

- [x] **T0.1 跑通基线**：依次执行验证命令 1→2→3→4，记录：编译是否过、单测是否 **16 passed**、`/api/health` 是否 ok。基线跑不起来先修环境（JDK8、Maven、`third_party/mica-voice/models/x-asr-zh-en-chunk-960ms` 模型目录），不要往下做。
- [x] **T0.2 建工作分支**：`git checkout -b feat/interview-server-optimize`。注意当前工作区**已有大量未提交改动**（模板、静态资源、部分 Java），先 `git status` 确认，决定是先提交这批既有改动再开分支，还是带着改动切分支——**不要让优化改动和既有未提交改动混成一个无法回滚的整体**。

### Phase 1 — 高危安全 + 真实 Bug（改动小、收益最高、必须先做）

#### T1.1 `/ws/copilot` 鉴权 + 会话归属校验（BE-SEC-1a）

- **目标**：未登录连不上；登录用户只能操作自己的 copilot 会话。
- **改哪些文件**：`InterviewServerApplication.java`、`web/CopilotWs.java`、`db/CopilotDao.java`（可能）。
- **步骤**：
  1. **在路由 lambda 里鉴权**（不要放进 `CopilotWs`，见铁律 4）。把 `InterviewServerApplication.java:48` 改成：
     ```java
     router.route("/ws/copilot", (ctx) -> {
         SessionStore.Session s = AuthRoutes.current(ctx.Request);
         if (s == null) {
             ctx.Response.setHttpStatus(HttpStatus.UNAUTHORIZED);
             ctx.Response.write("{\"success\":false,\"message\":\"用户未登录\"}");
             return; // 不升级
         }
         ctx.Request.upgrade(new CopilotWs(
             AsrEngine.getInstance().onlineAsr(config), config, new Llm(config), s.userId));
     });
     ```
     浏览器 WS 握手是同源 HTTP 请求，会带上 `ai_session` cookie，`AuthRoutes.current` 可直接用。
  2. `CopilotWs` 构造函数加 `long userId` 并存为 final 字段。
  3. `handleStart` 里 `CopilotDao.findSessionAnyUser(sid)` → `CopilotDao.findSession(sid, userId)`（该重载已存在，`CopilotRoutes` 在用；若签名不符再在 `CopilotMapper` 加 `AND user_id = #{userId}`）。查不到回 `{"event":"error","text":"无权访问该会话"}` 并 return。
  4. `generateAnswer` 里的 `CopilotDao.findSessionAnyUser(sessionId)` 同样换成带 `userId` 的版本。
- **验证**：编译过；`curl -i http://localhost:18081/ws/copilot`（无 cookie）返回 401 且**没有** `101 Switching Protocols`；登录后用他人 `session_id` 发 `copilot_start` 收到「无权访问该会话」。
- **注意**：**不要动** `/mica/voice/ws/online-asr`（铁律 1）。它是给 mica-voice 兼容客户端用的，加鉴权会破坏兼容性；如确需保护，改为绑定 `127.0.0.1` 或另开配置开关，单独立项。

#### T1.2 处理 `/api/copilot/answer`（BE-SEC-1b）

- **目标**：关掉匿名调用 LLM 的口子。
- **前置事实**：该端点全库无消费者（前端 Copilot 走 `/ws/copilot` 的 answer 链路），只在 README 里作为冒烟示例。
- **两个选项，默认选 A**：
  - **A（推荐）下线端点**：删除 `InterviewServerApplication.java:55-68` 整个 `if (llmApiKey...)` 块。`AnswerService` 类保留（`AnswerServiceTest` 的 3 个单测依赖它），或在 T2.4 与 `Llm` 合并。同步改 `services/interview-server/README.md:45` 那一行说明。
  - **B 加鉴权**：在 handler 首行加 `SessionStore.Session s = AuthRoutes.current(ctx.Request); if (s == null) { 401; return; }`，并顺手把 `:65` 回传的 `error.getMessage()` 换成固定文案（属 BE-SEC-3）。
- **验证**：A → 编译过、`curl` 该路径返回 404、`mvn -q test` 仍 16 passed；B → 未登录 `curl` 返回 401 且日志无 LLM 调用。

#### T1.3 修 MockRoutes 评分失败分支双重编码（BE-BUG-1）

- **改哪个文件**：`web/MockRoutes.java`，`handleAnswer` 里 `evaluation.getBoolean("retryable")` 分支（约 `:133-141`）。
- **步骤**：把 `error(response, 503, body.toJSONString())` 换成不再二次编码的写法：
  ```java
  response.setHttpStatus(HttpStatus.valueOf(503));
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  response.write(body.toJSONString());
  ```
  （`body` 已含 `success/evaluation/message`，`error()` 会把它整体当字符串再转义一遍。）
- **验证**：编译过；构造评分失败（临时改 `LLM_BASE_URL` 为不可达地址）后 `POST /mock-interviews/{id}/answers`，响应体的 `message` 是纯文案、`evaluation` 是对象而非字符串。

#### T1.4 修音频来源切换失效（BE-BUG-2）

- **改哪个文件**：`web/CopilotWs.java` `handleSetSpeaker`（`:174`）。
- **步骤**：`if (sid == null || sessionId == null || sid != sessionId)` → `if (sid == null || sessionId == null || !sid.equals(sessionId))`。顺手扫一遍全库还有没有别的 `Long` 用 `==`/`!=` 比较（`grep -rn "!= sessionId\|== sessionId"`）。
- **验证**：编译过；用一个 `session_id > 127` 的真实会话发 `copilot_set_speaker`，收到 `speaker_changed` 而不是「会话不匹配」；`copilot_events` 表出现 `speaker_changed` 记录。
- **说明**：这是 T5.2 「切换来源」验收能通过的前提，必须先做。

#### T1.5 修 findTurn 空指针（BE-BUG-3）

- **改哪个文件**：`web/MockRoutes.java` `handleAnswer`（`:123` 之后）。
- **步骤**：`Map<String,Object> turn = MockDao.findTurn(...)` 之后加 `if (turn == null) { error(response, 409, "当前轮次不存在，请重新开始模拟面试"); return; }`。
- **验证**：编译过 + `mvn -q test` 16 passed。

#### T1.6 Cookie 补 SameSite/Secure（BE-SEC-2）

- **改哪些文件**：`web/AuthRoutes.java`（`:52-56`）、`AppConfig.java`。
- **步骤**：`AppConfig` 新增 `cookieSecure`（读 `COOKIE_SECURE`，默认 `false`）。若 feat 的 `Cookie` 没有 `setSameSite`，手工写头：`response.setHeader("Set-Cookie", "ai_session=" + token + "; Path=/; Max-Age=" + SESSION_TTL_SECONDS + "; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : ""))`，并注意别和 `addCookie` 重复下发。顺手把「7 天」提成 `SessionStore.SESSION_TTL_SECONDS` 共享常量（解掉 BE-MISC 的魔法数字项）。
- **验证**：登录后 devtools → Application → Cookies，`ai_session` 有 `SameSite=Lax`；登录/登出/刷新页面会话保持正常。

#### T1.7 停止向客户端泄露异常原文（BE-SEC-3）

- **改哪些文件**：`web/ReviewRoutes.java`（`:104,210`）、`web/CopilotWs.java`（`:91,116,239`）、`svc/MockInterviewService.java`（`:93`）、`svc/ResumeAnalyzer.java`（`:61`）、`db/Db.java`（`:48,69,91`）、`InterviewServerApplication.java`（`:65`，若 T1.2 选 A 则已随块删除）。
- **步骤**：
  1. 客户端只看固定文案（「复盘生成失败，请稍后重试」「简历优化生成失败，请稍后重试」「转写异常，请重新开始」等），真实异常先 `e.printStackTrace()` 留在服务端，T2.2 再换成 `log.error`。
  2. `Db.java` 三处 `RuntimeException("SQL ... 失败: " + sql, e)` 去掉 `sql` 拼接（SQL 只进日志）。
  3. `CopilotWs:210,219,221` 打印面试问题原文/LLM 结果的 `System.out.println` 直接删除或改成只打 `sessionId` + 长度。
- **不要动**：`AsrWebSocketUpgrade:81` 的 error 帧（铁律 1）。要改只改文案不改事件名与帧结构，且单独验证 `AsrWebSocketUpgradeTest` 9 个测试仍绿。
- **验证**：编译 + `mvn -q test` 16 passed；把 `AI_INTERVIEW_DATABASE_URL` 指到一个坏路径触发 DB 错误，客户端响应不含 SQL 与堆栈。

#### T1.8 修 company 登录静默死循环（BE-NAV-2）

- **改哪些文件**：`web/AuthRoutes.java`（`handleLogin` 的 role 分支 / `requireApplicant`）。
- **步骤**：`handleLogin` 里若 `role` 不是 `applicant`，直接 `redirect("/loginView?message=" + encode("企业端暂未开放，请使用求职者账号登录"))` 并**不下发 session cookie**（或下发但立刻在登录页说明）。同时给 `requireApplicant` 的失败重定向带上 `?message=...`，避免任何角色被静默弹回。
- **验证**：company 账号登录看到明确提示而不是回到空白登录页；applicant 登录流程不受影响。

- [x] **Phase 1 收口**：验证命令 1+2+3+4；`git commit -m "fix(interview-server): 修复 WS 越权与鉴权、评分双重编码、来源切换失效、异常泄露等安全与 bug 项"`。

### Phase 2 — 后端公共层重构（消除重复，最高 ROI 的整洁改造）

#### T2.1 新建 `web/RouteSupport.java` 收敛公共方法（BE-DRY-1）

- **目标**：把散落在 7 个 Routes 里的重复私有方法集中到一处。
- **新建文件**：`web/RouteSupport.java`。
- **步骤**：
  1. 新建 `public final class RouteSupport`，把以下方法搬进来做成 `static`：
     - `parseBody(HttpRequest)` → `JSONObject`
     - `parseLong(String)`（失败返回 -1，与现状一致）
     - `json(HttpResponse, JSONObject)` / `ok(HttpResponse, String)`
     - `error(HttpResponse, int, String message)`——**只接受文案，不接受 JSON 串**，从签名上杜绝 BE-BUG-1 复发；需要返回结构体时用 `json(...)`
     - `toJson(Map)` → `new JSONObject(map)`（替换所有手写 entry 循环）
     - `parsePayload(Object)`
     - `require(HttpRequest, HttpResponse)`：解析 session，`null` 时**已写好 401 并返回 null**，调用方 `Session s = RouteSupport.require(req, resp); if (s == null) return;`
     - 所有 JSON 出口统一 `setHeader("Content-Type", "application/json; charset=utf-8")`
  2. 逐个 Routes 删本地重复方法、改调 `RouteSupport.*`。**一次只改一个文件，改完立刻 `mvn -q -DskipTests package`**。顺序（由短到长）：`KnowledgeRoutes`(127) → `CopilotRoutes`(188) → `MockRoutes`(242) → `ProfileRoutes`(264) → `ResumeRoutes`(292) → `ReviewRoutes`(318) → `PlanRoutes`(349)。
  3. `planDict / packDict / interviewDict / sessionDict` 里的 Map→JSONObject 循环换成 `RouteSupport.toJson(map)`（保留 packs 等嵌套特殊处理）。
- **验证**：每改一个文件编译一次；全部改完 `mvn -q test` 16 passed；启动后手点计划列表/创建/详情、复盘、mock 一轮、知识库增删、简历列表，行为不变。
- **附带收益**：`PlanRoutes` / `ReviewRoutes` 删掉重复方法后大概率回到 300 行以内。

#### T2.2 接入 SLF4J 替换 System.out（BE-SEC-3 收尾 / BE-MISC）

- **前置事实**：`slf4j-api 1.7.36` 已由 `mica-voice-core` 传递引入，当前**无 binding**，启动时 slf4j 处于 NOP 状态。
- **改哪些文件**：`pom.xml`、`AsrEngine.java`、`InterviewServerApplication.java`、`svc/ResumeExtractor.java`、`web/CopilotWs.java`（共 10 处 `System.out`/`printStackTrace`）+ T1.7 里暂留 `printStackTrace` 的位置。
- **步骤**：
  1. `pom.xml` 显式声明 `slf4j-api` 与 binding，**版本都钉 1.7.36**（Java 8 安全；不要用 slf4j 2.x + logback 1.3+，那要 Java 11）：
     ```xml
     <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>1.7.36</version></dependency>
     <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-simple</artifactId><version>1.7.36</version></dependency>
     ```
  2. 每个类加 `private static final Logger log = LoggerFactory.getLogger(X.class);`；`System.out.println(x)` → `log.info(...)`，`printStackTrace()` → `log.error("msg", e)`。
  3. **不要在日志里打面试问题原文、回答原文、简历内容**，只打 `planId`/`sessionId`/长度/错误摘要。
  4. `start.ps1` 已经把依赖 jar 拼进 classpath（`target/lib`），新增依赖后需重跑 `mvn package` 才会出现在 `target/lib`——在验证步骤里显式提醒。
- **验证**：`mvn -q -DskipTests package` 后 `ls target/lib | grep slf4j` 能看到两个 jar；启动日志正常输出、无「Failed to load class StaticLoggerBinder」警告、无用户敏感内容。

#### T2.3 事务边界（BE-DB-1）——**方案已改，不引连接池**

- **v1 方案为什么不能用**：每个 DAO 方法都自己 `MyBatis.open()`。若用 HikariCP 且 `maximumPoolSize=1`，事务持有唯一连接时嵌套 DAO 再取连接 → 池耗尽永久阻塞（死锁）。而 SQLite 是单写模型，池开大又会撞写锁。且 HikariCP 5.x 需要 Java 11，本项目 Java 8 只能用 4.0.3。**结论：这一步收益远小于风险，不引池。**
- **目标（缩小到真正需要的）**：只给两条多写路径加事务；连接创建开销保持现状（SQLite 本地文件，`DriverManager` 开销可接受）。
- **改哪些文件**：`db/MyBatis.java`、`db/MockDao.java`、`db/PlanDao.java`、`web/MockRoutes.java`、`web/PlanRoutes.java`、`db/Db.java`。
- **步骤**：
  1. `Db.init` 里除 `PRAGMA journal_mode=WAL` 再加 `PRAGMA busy_timeout=5000`，并让 `MyBatis` 的 `SqliteDataSource.getConnection()` 也执行一次 `busy_timeout`（否则并发写会立刻抛 `SQLITE_BUSY`）。这是本任务**性价比最高**的一步，可以单独先做。
  2. `MyBatis` 增加：
     ```java
     public static SqlSession openTx() { return factory.openSession(false); }
     public static void tx(java.util.function.Consumer<SqlSession> work) {
         try (SqlSession s = openTx()) {
             try { work.accept(s); s.commit(); }
             catch (RuntimeException e) { s.rollback(); throw e; }
         }
     }
     ```
  3. 给 `MockDao.updateTurn / updateInterview / createTurn` 和 `PlanDao` 对应写方法各加一个 **接受 `SqlSession` 的重载**（原无参版本保留，内部调重载），这样事务内不会再嵌套开连接。
  4. `MockRoutes.handleAnswer` 的 `updateTurn → updateInterview → createTurn` 三步、`PlanRoutes.handleUpdate` 的「改字段 + 标记 outdated」两步，各包进一个 `MyBatis.tx(s -> {...})`。
- **验证**：`mvn -q test` 16 passed；手测 mock 作答一轮 + 计划编辑，数据一致；在 `tx` 中间临时 `throw new RuntimeException()` 验证确实回滚、无脏数据（验证完删掉临时代码）。
- **风险**：改 DAO 签名会波及多处调用点，**务必一个 DAO 一编译**。若中途发现波及面过大，退回只做步骤 1（`busy_timeout`），把事务留到 §10 附加待办。

#### T2.4 LLM 逻辑去重 + 超时语义澄清 + 线程池（BE-LLM-1 / BE-LLM-2）

- **改哪些文件**：`svc/Llm.java`、`AnswerService.java`、`web/CopilotWs.java`、`web/PlanRoutes.java`、`AppConfig.java`、`src/test/.../AnswerServiceTest.java`。
- **步骤**：
  1. `settled` 改成 `java.util.concurrent.atomic.AtomicBoolean`（修 BE-LLM-2 的可见性问题），两个类都改。这一步独立、零风险，先做。
  2. 去重：让 `AnswerService.answer` 变成 `Llm` 的薄封装——把 SYSTEM_PROMPT 作为 `Message.ofSystem` 拼进消息列表，调用 `llm.chat(messages, temperature)`，删掉重复的 40 行 `CompletableFuture` 块。**`AnswerServiceTest` 有 3 个测试注入 mock `ChatModel`**，改完必须同步调整测试或保持包可见构造签名兼容，`mvn -q test` 仍需 16 passed。若 T1.2 选了 A（下线端点），这一步仍值得做，因为 `AnswerService` 的测试是现有覆盖的一部分。
  3. 超时取消：**先确认** `feat-ai` 的 `ChatModel.chatStream` 是否返回可取消的句柄（`javap -cp target/lib/feat-ai-2.4.0.jar tech.smartboot.feat.ai.chat.ChatModel`）。有则超时后调用它释放连接；**没有就不要硬造**——改为「把 `chatStream` 提交到 executor 并 `Future.cancel(true)`」，并在代码注释与本文档如实记录「底层 HTTP 连接可能仍在跑，仅丢弃回调」这一限制，不要在验收里声称已彻底取消。
  4. 线程池：`CopilotWs.ANSWER_EXECUTOR`（2）与 `PlanRoutes.PREP_EXECUTOR`（单线程无界队列）改为从 `AppConfig` 读（默认 4 / 2），换成有界队列 + `CallerRunsPolicy` 或拒绝时给前端明确「排队中/服务繁忙」提示，避免无界队列堆积。
- **验证**：`mvn -q test` 16 passed；手测 Copilot 一次真实回答 + 准备包生成；把 `LLM_TIMEOUT_SECONDS=1` 触发超时，观察日志有明确超时记录且服务不挂死。

#### T2.5 清理死配置与低危项（BE-MISC）

- **步骤**（每条独立小改，可合并一次提交）：
  1. `AppConfig.appSecretKey`：已确认全库无消费点（只有字段 + getter），**删除字段、构造参数、`load()` 里的默认值兜底与 getter**。
  2. `SessionStore`：加一个 daemon `ScheduledExecutorService`，每小时扫一次 `SESSIONS` 清过期项。
  3. 「7 天」魔法数字：若 T1.6 未一并处理，这里统一提到 `SessionStore.SESSION_TTL_SECONDS`。
  4. `Db` 查询层：把 `ResumeRoutes:136` 那条唯一的 `Db.queryOne` 挪进 `ResumeMapper`，然后删掉 `Db` 的 `query/queryOne/scalar/execute/insert/bind`，`Db` 只留 `init`（WAL + busy_timeout）与 `open`。
  5. `FE-NAV-1`：`layouts/app.html` 把「面试」「模拟面试」拆成两个 activePage 值（如 `interview` / `mock`），`PageRoutes` 对 `/applicant/interview-workspace` 按 `mode` 参数决定传哪个。
- **验证**：`mvn -q test` 16 passed + 启动 + 登录会话正常 + 简历报告页数据正常 + 两个菜单项不再同时高亮。

- [x] **Phase 2 收口**：验证命令 1+2+3+4+5（含 Flask 回归 146 passed）；`git commit -m "refactor(interview-server): 抽取 RouteSupport、接入 SLF4J、补事务与 busy_timeout、清理死配置"`。

### Phase 3 — 前端健壮性（当前最痛的一块，23 处 fetch 只有 1 处 catch）

#### T3.1 新建 `static/static/js/api.js` 统一请求封装（FE-ROBUST-1）

- **新建文件**：`src/main/resources/static/static/js/api.js`（对外路径 `/static/js/api.js`）。
- **步骤 1**：写一个全局 `window.api`：
  ```js
  (function () {
    "use strict";
    window.api = {
      async request(method, url, body) {
        const opt = { method, headers: {} };
        if (body !== undefined) {
          opt.headers["Content-Type"] = "application/json";
          opt.body = JSON.stringify(body);
        }
        let res;
        try { res = await fetch(url, opt); }
        catch (e) { throw new Error("网络异常，请检查连接后重试"); }
        if (res.status === 401) { location.href = "/loginView"; throw new Error("登录已过期"); }
        const data = await res.json().catch(() => ({}));
        if (!res.ok || data.success === false) {
          throw new Error(data.message || ("请求失败 " + res.status));
        }
        return data;
      },
      get(u) { return this.request("GET", u); },
      post(u, b) { return this.request("POST", u, b); },
      patch(u, b) { return this.request("PATCH", u, b); },
      del(u) { return this.request("DELETE", u); },
      // 文件上传：不设 Content-Type，交给浏览器带 boundary
      async upload(url, formData) { /* 同上但 body=formData、不设 header */ }
    };
  })();
  ```
  注意：`resume_manage.html` 用 `n-upload` 传文件，**不能**走 JSON 分支，必须用 `upload()`。
- **步骤 2**：`layouts/app.html` 里在 `app-shell.js` 之前引入 `/static/js/api.js`（这样 8 个 Vue 页自动获得，不必逐页加 script）。确认 `profile.html` 的 `settings.js`/`voice-profile.js` 也能用上（它们自己已有 try/catch，可后续再迁）。
- **步骤 3**：逐页把 `fetch(...).then(r => r.json())` 改为：
  ```js
  loading.value = true;
  try { const data = await api.get('/plans'); /* ... */ }
  catch (e) { message.error(e.message); }
  finally { loading.value = false; }
  ```
  **一页一改一验证**，顺序：`reviews`(1 处) → `review_detail`(1) → `resume_optimize`(2) → `interview_plans`(3) → `knowledge`(3) → `resume_manage`(3) → `interview_plan_detail`(5) → `interview_workspace`(5)。共 23 处。
- **验证**：每页改完，停掉后端再刷该页 → 弹出「网络异常」提示、spinner 停止、页面不卡死；后端起来后功能正常。

#### T3.2 抽 `static/static/js/utils.js`（FE-DRY-2）

- **新建文件**：`utils.js`，放 `parseList(v)` / `parseObj(v)`（JSON 容错解析），挂 `window.utils`。
- **删除**：`interview_plan_detail.html:112`、`review_detail.html:49-50`、`resume_optimize.html` 各自的重复实现，改用 `window.utils.parseList` 等（Vue setup return 里仍要暴露给模板）。
- **同 T3.1 一起在 `layouts/app.html` 引入。**
- **验证**：计划详情的准备包四个列表、复盘详情的评分/弱项/下一步渲染正常。

#### T3.3 vendor `defer` + 静态资源强缓存（FE-PERF-1，短期缓解）

- **步骤**：
  1. 8 个页面的 `<script src="/vendor/naive-ui.min.js">` 与 `/static/js/app-boot.js` 都加 `defer`（`defer` 保持文档顺序，`app-boot.js` 仍在 vendor 之后执行，安全）。同时确认页面底部的内联 `<script>`（定义 `PageRoot` + 调 `mountApp`）也要改成 `defer` 的外部脚本或包进 `DOMContentLoaded`，否则会在 vendor 之前跑而报 `window.Vue` 未定义——**这一步是本任务最容易踩的坑，先改一页验证再推广**。
  2. 静态资源加强缓存：`InterviewServerApplication.java:31` 的 `HttpStaticResourceHandler` 查一下 feat 2.4.0 是否支持自定义响应头；不支持就在 `Router` 上为 `/vendor/**`、`/static/**` 前置一个设 `Cache-Control: public, max-age=31536000, immutable` 的处理。给 vendor 文件名加内容 hash（`naive-ui.<hash>.min.js`）避免更新后读旧缓存。
- **验证**：devtools Network → 二次进页面 vendor 命中 `disk cache`；首屏 `<head>` 不再被 2.4MB 脚本阻塞；**8 个页面全部逐一打开确认内容渲染正常**（这是 `defer` 改动的主要风险）。
- **说明**：彻底方案（Vite 构建 + `vue.runtime` + 按需引入）见 §10 附加待办。

#### T3.4 删除 Bootstrap CDN（FE-DEAD-2）

- **改哪些文件**：`templates/common/login.html`（`:11` CSS、`:80` JS）、`templates/common/register.html`（`:11` CSS、`:146` JS）、`templates/layouts/app.html`（`:46` JS）。
- **前置已核验**：全库 0 个 Bootstrap 类名（`btn/row/col-/container/modal/form-control/navbar`）、0 个 `data-bs-*`，删除零风险。
- **验证**：devtools Network 无 bootstrap 请求、无 404、Console 无报错；登录/注册/所有 applicant 页视觉与交互（主题切换、密码显隐、抽屉侧栏）无变化。

- [x] **Phase 3 收口**：验证命令 3+4 + 手工点通全部 applicant 页 + 断网场景验证；`git commit -m "feat(interview-server): 统一 api.js/utils.js、fetch 错误兜底、vendor defer 与静态缓存、移除 Bootstrap"`。

### Phase 4 — 8 个 Vue 页撤掉 NaiveUI（最大的一块，分批）

> 目标体系 = `tokens.css`（84 行，颜色/间距/字体变量，含亮/暗）+ `components.css`（120 行，`.button/.field/.select/.surface/.status/.tag/.dialog/.empty-state`）+ `app-shell.css`+`app-shell.js`（响应式侧栏 + 主题切换）。
> **`profile.html` 是已经完成的样板页**——它没有任何 NaiveUI 依赖、用 `.field-group`/`.field`/`.button`/`.surface`、`aria-live` 齐全。改其他页时照它抄。

#### T4.0 先补齐 components.css 的缺口（做在换页之前）

- **步骤**：8 个页面用到的 NaiveUI 组件里，`components.css` **没有**对应实现的：`n-spin`（加载态）、`n-alert`（提示条）、`n-tabs/n-tab-pane`（工作区双模式切换）、`n-collapse`（准备包折叠）、`n-list/n-list-item`、`n-grid`、`n-upload`、`n-divider`。先在 `components.css` 里补 `.spinner` / `.alert[data-kind]` / `.tabs` / `.accordion` / `.list` / `.grid` / `.divider`，以及原生 `<input type=file>` 的上传样式。
- **验证**：写一个临时 `temp/components-preview.html` 把新类全部渲染一遍，深浅主题各看一次；确认后删掉临时文件。
- **理由**：不先补齐，换页时会一边换组件一边发明 CSS，导致每页样式不一致——这正是「三套 UI」的成因。

#### T4.1 逐页替换（一页一提交，从简单到复杂）

- **顺序与规模**（括号内为 fetch 数量，越少越简单）：`reviews`(1) → `review_detail`(1) → `resume_optimize`(2) → `knowledge`(3) → `resume_manage`(3，含上传) → `interview_plans`(3) → `interview_plan_detail`(5) → `interview_workspace`(5，含 WS/音频，**最后做**)。
- **每页步骤**：
  1. 按 §6 映射表把 `n-*` 组件换成原生类；保留 Vue 响应式与 `setup` 逻辑，**只换渲染层**。
  2. `useMessage()` 换成统一 toast（一个 `role="status" aria-live="polite"` 容器 + `api.js` 兜底）；建议在 `utils.js` 里加 `window.toast(msg, kind)`。
  3. 该页不再依赖 vendor 后，从 `<head>` 移除 `/vendor/naive-ui.min.js` 与 `/static/js/app-boot.js`，把 `mountApp(...)` 换成直接 `Vue.createApp(PageRoot).mount('#app')`——**注意此时仍需 Vue 本体**。Vue 目前是打在 vendor 包里的，所以在最后一页改完之前 vendor 不能删；需要先产出一个只含 Vue 的 `vendor/vue.min.js`（用 `temp/vendor-build/build.ps1`，铁律 3：必须完整版含编译器）。**这一步（拆出 vue-only 包）建议作为 T4.1 的第 0 小步先做掉**，否则每页都会卡在这里。
  4. 每页改完对齐旧版功能：增删改查、弹窗、空状态、加载态、错误提示、键盘可达。
- **验证**：每页 devtools Console 无报错、Network 无 404；深浅主题各扫一遍；全部 8 页完成后删除 `vendor/naive-ui.min.js` 文件与所有引用、删除 `app-boot.js`。
- **允许的中止边界**：若决定短期不做完，**必须**在 §11 偏离记录里写明「已迁移：xxx / 仍用 NaiveUI：yyy」，并保持两类页面各自内部一致，不要在同一页混用。

#### T4.2 收尾小项

- **FE-DRY-3**：每页改完顺手删掉那行 `var { createApp, ref, ... } = window.Vue;` 里已不使用的部分（`createApp` 由 `mountApp` 接管后就是死变量；T4.1 步骤 3 改成直接 `Vue.createApp` 后要相应保留）。跟着页面迁移做，不单独立任务。
- `workspace.html` 里 10 行 `.plan-card` 内联 `<style>` 提到 `components.css`（可直接复用 `.surface` + 少量修饰）。
- `profile.html` 的 `<link rel="stylesheet" href="/static/css/pages/settings.css">` 从 content fragment 移到 `layouts/app.html` 的 `<head>`（用 `th:if="${activePage=='settings'}"`）或合并进 `components.css`。
- `login.html` 的 `#rememberMe`：要么接上后端（登录时按勾选决定 cookie `Max-Age`，7 天 vs 会话级），要么删掉这个控件。**不要留着一个点了没反应的开关。**

- [x] **Phase 4 收口**：每页一个 commit；全部完成后验证命令 3+4 + 8 页手点 + 深浅主题各一遍 + Network 确认 vendor 已不再加载。

### Phase 5 — 真实采集来源透传与显示（不是设备扫描）

> **再强调一次**：现役产品里没有「设备扫描/枚举下拉」。要做的是把 Copilot 真实的音频来源与说话人**如实上报并显示**，替换掉当前硬编码的 `mixed`/`interviewer`。
> **前置依赖**：T1.4（`Long` 引用比较）必须先修好，否则 `copilot_set_speaker` 对 `session_id > 127` 的会话永远返回「会话不匹配」，本阶段做完也验不过。

#### T5.1 后端：从控制帧跟踪来源，去掉硬编码（BE-CAP-1）

- **v1 的错误**：v1 假设「音频帧里已带 mode/source」。**这是错的**——上行音频是裸二进制 PCM 帧（`interview_workspace.html:213` 直接 `ws.send(pcm.buffer)`），不含任何元数据，`handleBinaryMessage(byte[] data)` 除了 PCM 什么都拿不到。
- **正确做法**：来源只能由控制帧驱动的**连接级状态**推导。
- **改哪里**：`web/CopilotWs.java`
  1. 加两个实例字段（`CopilotWs` 每次 upgrade 都是新实例，天然按连接隔离，无需同步；但因为 `handleTextMessage`/`handleBinaryMessage` 与 `ANSWER_EXECUTOR` 可能并发读写，用 `volatile`）：
     ```java
     private volatile String captureSource = "microphone"; // microphone|display|mixed
     private volatile String speaker = "auto";             // auto|candidate|interviewer
     ```
  2. `handleStart(...)`：读取 `payload.getString("mode")` 与 `payload.getJSONArray("sources")`（前端已经在发 `mode:'auto'`、`sources:['mixed']`，后端目前完全忽略），归一化后写入 `captureSource`；把它一起记进 `capture_started` 事件的 payload（现在传的是空 `"{}"`）。
  3. `handleSetSpeaker(...)`：把已归一化的 `normalized` 除了写事件之外，**同时赋给 `this.speaker`**（当前只写了事件，状态没留下，这是 BE-CAP-1 的直接原因）。
  4. `publishTranscript(...)`：
     ```java
     - payload.put("source", "mixed");
     - payload.put("speaker", "interviewer");
     + payload.put("source", captureSource);
     + payload.put("speaker", speaker);
     ```
  5. `handleSetSpeaker` 回执里带上当前值，便于前端确认：`{"event":"speaker_changed","speaker":"candidate","source":"microphone"}`（用一个新的 `sendRaw` 组装，不要动通用 `send` 的签名以免影响其他事件）。
- **不做**：不引入声纹/说话人分离（diarization），`speaker=auto` 时保持后端不猜、按前端声明值透传；真实分离见 §10。
- **验证**：
  1. 起服务，打开 `/applicant/interview-workspace`，开始面试，说一句话；
  2. devtools → Network → WS → 帧列表，确认 `transcript_partial/final` 的 `source` 是 `microphone`（不再是 `mixed`）；
  3. 点切换说话人，确认收到 `speaker_changed` 且后续转写帧的 `speaker` 跟着变（**这一步就是 T1.4 的回归验证**）；
  4. SQLite 查 `copilot_events` 表，`capture_started` 的 payload 里有 `source` 字段。

#### T5.2 前端：状态徽标 + 来源单选 + 降级提示

- **改哪里**：`templates/applicant/interview_workspace.html`
  1. 顶部加一行状态徽标（用 `components.css` 的 `.status` / `.tag`）：**连接状态**（连接中/已连接/已断开，来自 `ws.readyState` 与 `ready/started/paused/ended` 事件）、**采集来源**（麦克风/系统声音/混合，来自 `transcript_*` 帧的 `source`）、**当前说话人**（我/面试官/自动）。全部 `aria-live="polite"`。
  2. 音频来源单选（3 个原生 `radio`，`fieldset+legend`，键盘可达）：`麦克风(local)` / `系统声音(remote)` / `自动(auto)`。选中后发 `copilot_set_speaker`（`local→candidate`、`remote→interviewer`、`auto→auto`），并根据回执更新徽标。
  3. **系统声音（`getDisplayMedia`）降级**：interview-server 当前只有 `getUserMedia({audio:true})`（`:202`），没有屏幕/系统音采集。两种做法，**二选一并在文档里记录选择**：
     - **A（推荐，工作量小）**：选「系统声音」时检测 `navigator.mediaDevices.getDisplayMedia` 是否存在 + 是否 `isSecureContext`，不满足就置灰该选项并显示提示「当前环境不支持系统声音采集，请使用麦克风」。**先把状态显示做对，采集能力留待后续。**
     - **B（完整）**：按根 Flask 蓝图的做法接 `getDisplayMedia({audio:true})`，双路 `AudioContext` 混流后再降采样成 PCM16/16k/mono 上行，`copilot_start` 的 `sources` 报 `['local','remote']`。注意 Chrome 只在标签页/整个屏幕共享时给音轨，Firefox 不给——必须保留 A 的降级分支。
  4. WS 断线：加一次性提示 + 「重新连接」按钮（不做自动无限重连，避免风暴）。
- **验证**：三种来源各点一遍，徽标、WS 帧、后端事件三处一致；不支持 `getDisplayMedia` 的环境下选项置灰且有文字说明；断网后有明确提示且点重连能恢复。

#### T5.3 可选：真机验收项

- Chrome 标签页共享 + 系统声音（仅在选了方案 B 时验）。
- 面试官声音走系统音、本人声音走麦克风时，转写帧的 `speaker` 与实际说话人一致。
- 麦克风被系统占用/权限拒绝时，页面有明确报错而不是静默无转写（现在 `getUserMedia` 失败路径需要确认有 catch）。

- [x] **Phase 5 收口**：验证命令 1+3+4 + 上述真机步骤；`git commit -m "feat(interview-server): Copilot 采集来源与说话人如实上报并在页面显示"`。

### Phase 6 — 收尾与验收

- [x] **T6.1 全量回归**：`mvn -o test`（Java，基线 16 passed，新增用例只增不减）；根 Flask `pytest`（基线 146 passed，本次不应触碰 Flask 代码，若有变化必须解释）。
- [x] **T6.2 求职者主线走查**（一次不间断跑完，任一步失败即视为未完成）：
  1. 注册 → 2. 登录 → 3. 上传简历 → 4. 简历优化 → 5. 建面试计划 → 6. 生成准备包 → 7. 进入 Copilot 工作区、说话看转写与建议 → 8. 模拟面试答题评分 → 9. 结束生成复盘 → 10. 复盘列表/详情 → 11. 个人设置改资料 + 录语音 → 12. 知识库增删 → 13. 登出。
- [x] **T6.3 深浅主题各扫一遍**：全部页面 + 登录/注册/落地页；确认无硬编码颜色残留（`rg -n "#[0-9a-fA-F]{6}" src/main/resources/templates` 应只剩极少数刻意例外）。
- [x] **T6.4 键盘可达性抽查**：登录、计划新建弹窗、Copilot 来源单选、知识库增删——Tab 顺序合理、焦点可见、Esc 关弹窗。
- [x] **T6.5 文档同步**：更新 `services/interview-server/README.md`（端口 18081、WS 鉴权行为变化、`/api/copilot/answer` 若删除要写明、静态资源缓存策略、UI 体系说明）；本计划文档逐项勾选并在 §11 偏离记录里写实际与计划的差异。
- [x] **T6.6 PR 摘要**（不自动 push、不自动建 PR，等用户确认）：改动分组、验证证据（命令 + 实际输出）、已知遗留、回滚方式。

---

## 6. UI 样式规范（目标单一体系）

### 6.1 设计令牌（`static/css/tokens.css`，已存在，勿另起一套）

- 颜色：`--bg` / `--bg-elevated` / `--surface` / `--border` / `--text` / `--text-muted` / `--accent` / `--accent-contrast` / `--success` / `--warning` / `--danger`
- 排版：`--font-sans`（Manrope + Noto Sans SC）/ `--radius-sm|md|lg` / `--shadow-sm|md`
- 间距：`--space-1..6`
- 主题：根元素 `data-theme="dark|light"`，由 `app-shell.js` / `auth.js` 写入并持久化到 `localStorage('interview-theme')`。**新增样式一律引用变量，禁止写死颜色。**

### 6.2 NaiveUI → 原生映射表（Phase 4 照这张表改）

| NaiveUI | 替换为 | 备注 |
| --- | --- | --- |
| `n-button` | `<button class="button">` / `.button-secondary` / `.button-danger` / `.button-ghost` | `loading` → 内嵌 `.spinner` + `disabled` |
| `n-input` / `n-input-number` | `<input class="field">` | 外层 `.field-group` + `<label for>` |
| `n-select` | `<select class="select">` | 需要搜索的场景先用原生，确有必要再做组件 |
| `n-form` / `n-form-item` | `<form>` + `.field-group` | 校验用原生 `required`/`pattern` + 自定义提示 |
| `n-card` | `<section class="surface">` | 标题用 `.surface-header` |
| `n-modal` / `n-dialog` | `<dialog class="dialog">` + `showModal()` | 原生 dialog 自带焦点陷阱与 Esc，优于自造 |
| `n-tag` | `<span class="tag">` | 语义色用 `data-kind` |
| `n-tabs` / `n-tab-pane` | `.tabs` + `role="tablist/tab/tabpanel"` | T4.0 新增 |
| `n-collapse` | `<details class="accordion">` | 原生即可，无需 JS |
| `n-spin` | `.spinner` | T4.0 新增 |
| `n-alert` | `.alert[data-kind]` | T4.0 新增，参考 `auth.css` 的 `.auth-alert` |
| `n-empty` | `.empty-state` | 已存在 |
| `n-list` / `n-grid` | `.list` / `.grid` | T4.0 新增 |
| `n-upload` | `<input type="file">` + `.button` 伪装 label | 参考 `profile.html` 的语音上传写法（已是原生实现，可直接抄） |
| `useMessage()` | `window.toast(msg, kind)` | T4.1 新增，容器带 `role="status" aria-live="polite"` |
| `n-config-provider` + `mountApp` | 直接 `Vue.createApp(PageRoot).mount('#app')` | 主题由 `data-theme` + CSS 变量接管，不需要 JS 主题对象 |

---

## 7. 菜单 / 页面 / 路由对齐表（v2 已按 `PageRoutes.java` 实际注册纠正）

侧栏 8 项，定义在 `templates/layouts/app.html:21-28`；路由定义在 `web/PageRoutes.java:55-63`。

| 侧栏项 | href | 实际路由 | 模板 | activePage | 状态 |
| --- | --- | --- | --- | --- | --- |
| 工作台 | `/applicant/workspace` | `PageRoutes:26` | `applicant/workspace` | `workspace` | ✅ 已迁 shell |
| 面试计划 | `/applicant/interview-plans` | `:55` | `applicant/interview_plans` | `plans` | NaiveUI 待迁 |
| 面试 | `/applicant/interview-workspace?mode=copilot` | `:57` | `applicant/interview_workspace` | `interview` | NaiveUI 待迁 |
| 模拟面试 | `/applicant/interview-workspace?mode=mock` | `:57`（同一路由） | 同上 | `interview` | ⚠️ FE-NAV-1：与「面试」共用 `activePage`，两项同时高亮 |
| 简历中心 | `/applicant/resume_manage`（下划线） | `:59` | `applicant/resume_manage` | `resumes` | NaiveUI 待迁 |
| 知识库 | `/applicant/knowledge` | `:58` | `applicant/knowledge` | `knowledge` | NaiveUI 待迁 |
| 复盘 | `/applicant/reviews` | `:61` | `applicant/reviews` | `reviews` | NaiveUI 待迁 |
| 个人设置 | `/applicant/profile` | `:63` | `applicant/profile` | `settings` | ✅ 原生样板页 |

无侧栏入口的页面：`/applicant/interview-plans/:planId`（`:56`，`plans`）、`/applicant/reviews/:reviewId`（`:62`，`reviews`）、`/applicant/resume-optimize`（`:60`，**连字符**，`resumes`）。

**v1 表格中的两处错误（已纠正）**：不存在 `/applicant/personal_center` 页面（`:41` 只是重定向到 `/applicant/profile`）；简历优化是 `/applicant/resume-optimize`（连字符），而简历管理是 `/applicant/resume_manage`（下划线）——**两者命名风格不一致但都是现役路由，改名会破坏 15 条旧入口重定向（`:38-52`），本次不动。**

**FE-NAV-1 修法建议**：`page()` 传入的 `activePage` 无法区分 query 参数。最小改动是在 `interview_workspace` 的路由里按 `ctx.Request.getParameter("mode")` 决定 `active_page` 为 `interview` 还是 `mock`，并给侧栏「模拟面试」项改用 `activePage == 'mock'`。

---

## 8. 采集 / 说话人协议速查（v2 重写：区分「根 Flask 蓝图」与「interview-server 实际状态」）

> v1 把两者混为一谈，导致 T5.1 的前提是错的。这一节必须分开看。

### 8.1 根 Flask 项目（参考实现，功能更全）

- 前端三选一：`auto` / `local`（麦克风）/ `remote`（`getDisplayMedia` 系统声音）。
- 双路 `MediaStream` → `AudioContext` 混流 → 降采样 PCM16/16k/mono。
- 上行帧带来源标注；有 `audio_ack`、`utterance_completed` 之类的回执事件；有断线重连。

### 8.2 interview-server 现状（**以此为改造基线**）

| 项 | 实际情况 | 位置 |
| --- | --- | --- |
| WS 端点 | `/ws/copilot`，原生 WebSocket（非 Socket.IO） | `InterviewServerApplication.java:48` |
| 上行控制帧 | 文本 JSON：`copilot_start` / `copilot_pause` / `copilot_resume` / `copilot_end` / `copilot_set_speaker` | `CopilotWs.handleTextMessage` |
| 上行音频帧 | **裸二进制 PCM16 S16LE/16kHz/mono，无任何元数据** | `interview_workspace.html:213` `ws.send(pcm.buffer)` |
| 采集能力 | **仅 `getUserMedia({audio:true})`，没有 `getDisplayMedia`** | `interview_workspace.html:202` |
| `copilot_start` 的 `mode`/`sources` | 前端固定发 `mode:'auto'`、`sources:['mixed']`，**后端完全忽略** | `interview_workspace.html:152` vs `CopilotWs.handleStart` |
| 下行转写帧 | `transcript_partial` / `transcript_final`，字段 `session_id/text/version/source/speaker` | `CopilotWs.publishTranscript` |
| `source`/`speaker` | **硬编码 `"mixed"`/`"interviewer"`** | `CopilotWs:197-198` |
| `copilot_set_speaker` | 只写 `speaker_changed` 事件到 DB，**不改变任何后续上报值**；且 `Long` 用 `!=` 比较，`session_id > 127` 时必然「会话不匹配」 | `CopilotWs:171-184`，Bug 在 `:174` |
| 回执事件 | `ready` / `started` / `paused` / `resumed` / `ended` / `speaker_changed` / `answer_started` / `answer_delta` / `answer_completed` / `error` | `CopilotWs.send` |
| **缺失** | 无 `audio_ack`、无 `utterance_completed`、无断线重连、无鉴权（任何人可连、可操作他人 session） | — |

### 8.3 铁律再确认

`/mica/voice/ws/online-asr`（`InterviewServerApplication.java:35`）是给 Python 侧用的，**协议、帧格式、事件名一个字都不能改**。上面所有改动只针对 `/ws/copilot`。

---

## 9. 风险与回滚

| 风险 | 触发点 | 缓解 | 回滚 |
| --- | --- | --- | --- |
| WS 鉴权后 Python 侧或旧前端连不上 | T1.1 | 只在 `/ws/copilot` 加鉴权，不碰 ASR 端点；先本地验证再提交 | `git revert` 该 commit |
| 删 `/api/copilot/answer` 后发现有外部调用 | T1.2 | 已确认全库零引用（仅 README/docs 提及）；仍可选方案 B（加鉴权保留） | 恢复路由注册即可，`AnswerService` 未删 |
| 事务改造引入 SQLite 锁等待/`database is locked` | T2.3 | 先加 `PRAGMA busy_timeout=5000`；事务范围尽量小；**绝不引连接池**（每个 DAO 方法各开 session，池大小=1 必死锁） | 恢复 `openSession(true)` 自动提交 |
| `defer` 打乱脚本顺序导致白屏 | T3.3 | 先改一页验证内联 `<script>` 的执行时机，再推广；8 页逐一打开确认 | 去掉 `defer` |
| Phase 4 半途而废造成「第四套 UI」 | T4.1 | 一页一提交；先补齐 `components.css`（T4.0）再动页面；未做完必须在文档记录已迁/未迁清单 | 单页 revert |
| 拆 vue-only vendor 包时用了 runtime-only 版 | T4.1 步骤 0 | **铁律 3**：必须完整版含编译器，否则模板编译失败全站白屏 | 恢复 `naive-ui.min.js` |
| 接 `getDisplayMedia` 在部分浏览器无音轨 | T5.2 方案 B | 必须保留方案 A 的置灰降级分支 | 切回方案 A |
| 大量未提交的工作区改动被误覆盖 | 全程 | **开工前先 `git status` 确认并单独提交/暂存现有改动**（T0.2 已列为前置） | — |

---

## 10. 附加待办（本计划不做，单独立项）

- [ ] **前端构建工具化**：引入 Vite/esbuild，产出按需的 Vue + 页面 bundle，彻底解决 2.4MB vendor 与手写 `<script>` 顺序问题。
- [ ] **说话人分离**：真实 diarization（声纹或双路分轨），替代当前「前端声明 + 后端透传」。
- [ ] **ASR 首字延迟**：目标 ≤1s，需要量测 chunk 大小、endpoint 判定与 `ANSWER_EXECUTOR` 排队的影响。
- [ ] **企业端页面**：`role=company` 目前登录后无处可去（BE-NAV-2 只是止血提示）。要么实现企业端，要么在注册处彻底移除该角色。
- [ ] **集成测试**：现有 16 个单测都是纯函数级；缺少 HTTP 路由级与 WS 级集成测试（鉴权、越权、双编码这类 bug 单测抓不到）。
- [ ] **`/api/copilot/answer` 若保留**：补 SSE 或流式输出，现在是一次性 JSON，与 `answer_delta` 的流式语义不一致。

---

## 11. 执行者备忘

1. **行号会漂移**：本文所有 `file:line` 是 2026-08-24 工作区快照。定位一律**按方法名/字符串搜索**，不要盲信行号。
2. **改前先确认问题还在**：v1 有 10 条诊断已经被修好（见 §0.1）。每个任务开始前先读一眼目标代码，问题不存在就直接勾掉并在下面记录。
3. **一个任务一个 commit**，格式 `<type>(scope): <中文摘要>`，摘要 ≤50 字、动词开头、不加句号。
4. **不自动 push、不自动建 PR、不动 git 历史**，等用户明确指示。
5. **每阶段收口都要跑验证命令并贴真实输出**。没有输出就不写「通过」。

### 偏离记录（执行时填写）

| 日期 | 任务 | 计划做法 | 实际做法 | 原因 |
| --- | --- | --- | --- | --- |
| 2026-08-24 | T3.3 | vendor 文件名加内容 hash | 未做 hash 命名 | Phase 4 已删除 2.4MB naive-ui vendor，仅剩 172KB vue-only 包；彻底方案见 §10 构建工具化 |
| 2026-08-24 | T4.1 | 一页一提交（8 个 commit） | 8 页合并为 1 个 commit（46248db） | 页面迁移与验证在同一次会话连续完成，合并提交更利于回滚（整块 revert） |
| 2026-08-24 | T4.1 步骤 0 | 先拆 vue-only 包再逐页迁移 | 拆包与逐页迁移同批完成 | 拆包（build-vue.ps1 + 沙箱验证 compile）先行完成，仅提交粒度合并 |
| 2026-08-24 | T5.3 | 真机验收（Chrome 标签页共享 + 系统声音实采） | 未执行真机验收 | 当前环境无浏览器；以 WS 协议级实测（source/speaker 透传、speaker_changed 回执、capture_started 落库）+ 降级分支代码覆盖替代，需人工补验 |
| 2026-08-24 | T2.2 | 替换全部 10 处 System.out/printStackTrace | ResumeExtractor.main 的 `System.out.println(JSON...)` 保留 | 该 main 是 CLI 测试入口，打印提取结果是其功能而非服务日志 |
| 2026-08-24 | T1.7 验证 | 坏 DB 路径触发错误观察客户端响应 | 仅代码级核验 + 构造 LLM 失败验证 503 结构 | 坏 DB 路径会导致服务启动失败（Db.init fail-fast），无法在运行中触发；所有 error 出口已固定文案，evaluation.message 实测为固定文案 |
| 2026-08-24 | T3.3 缓存策略 | /static 与 /vendor 均 immutable | /static 改为 max-age=3600、/vendor 保持 immutable | 安全审查 MEDIUM：/static 引用未版本化（无 ?v=），immutable 会让安全修复一年内不可达；已按建议调整（commit 0316540） |


---

## PR 摘要（T6.6，未 push 未建 PR，等待用户确认）

**分支**：`feat/interview-server-optimize`（基于 master 6fe9cdc），6 个 commit：

| commit | 内容 |
| --- | --- |
| 8c48475 | fix：WS 越权与鉴权、`/api/copilot/answer` 下线、评分双重编码、Long 引用比较、findTurn NPE、Cookie SameSite/Secure、异常原文封堵、company 登录死循环 |
| a954334 | refactor：RouteSupport 收敛 7 个 Routes、SLF4J 接入、事务+busy_timeout、LLM 去重+AtomicBoolean+线程池、死配置清理 |
| a39ad74 | feat：api.js/utils.js 统一请求封装（23 处 fetch 错误兜底）、vendor defer、静态资源 immutable 缓存、Bootstrap CDN 移除 |
| 46248db | feat：8 个 Vue 页撤 NaiveUI 全原生化（vendor 2.4MB→172KB）、components.css 补组件、rememberMe 接后端 |
| f54cf89 | feat：Copilot 采集来源/说话人如实透传 + 页面状态徽标 + 系统声音降级 + 断线重连 |
| 96b135c | docs：README 与计划文档同步（勾选 + 偏离记录） |

**验证证据**：
- Java：`mvn -q test` 16 passed（多轮）；`mvn -q clean package` 通过
- Flask 回归：`py -3 -m pytest tests/ -q` → 146 passed（两次）
- WS 实测：无 cookie 401 / 有 cookie 101；session_id=200（>127）speaker_changed 生效；`copilot_start` → capture_started 落库带 source；speaker_changed 回执带 source
- API 实测：评分失败 503 结构正确（无双重编码）；company 登录带提示无 cookie；Cookie SameSite=Lax 生效；remember 勾选 7 天/不勾会话级；知识库增删/简历上传/优化/复盘/语音/设置全链路 200
- 前端：8 页 24 个内联脚本 node --check 全过；api.js 四路径沙箱验证 ALL PASS；vue.min.js 含编译器沙箱验证
- 事务回滚探针：tx 异常整体回滚无脏数据（临时测试已删）

**已知遗留**：
- T5.3 真机验收（Chrome 标签页共享 + 系统声音实采）未执行，需人工补验
- 浏览器级视觉/键盘走查未执行（无浏览器），已由 SSR 输出 + 原生控件（dialog/radio/focus-visible）+ 代码审查替代
- LLM 超时仅丢弃回调，底层 HTTP 连接无法取消（feat chatStream 返回 void，限制已注释）
- ResumeExtractor.main 的 System.out 保留（CLI 工具行为）
- 根目录 Flask 项目文件未跟踪（git status ??），与本次分支无关

**回滚方式**：`git revert` 对应 commit 或 `git reset --hard master`（本分支未 push）。
