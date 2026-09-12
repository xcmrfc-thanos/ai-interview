# API 契约（唯一事实源）

- 版本：1.0.0（2026-09-05 冻结）
- 适用：`web/` 前端（唯一消费者）与两个后端实现（Java `services/interview-server`、Python Flask `app.py`）
- 规则：**任何接口变更先改本文档，再改实现**。新增字段向后兼容；实现差异必须登记在「§8 实现差异」。
- 基线决策：
  - REST 统一 `/api` 前缀；页面路由由前端 SPA 承担，后端只做静态托管 + SPA fallback。
  - Copilot 实时通道 = **裸 WebSocket**（非 Socket.IO），协议以 Java 版为基线（v2 音频帧、串行事件、恢复语义）。
  - 鉴权 = HttpOnly 会话 Cookie，前端不感知 Cookie 细节（同源请求默认携带）。
  - 字段命名统一：来源 `microphone | system | mixed`；WS 错误字段 `message`。

---

## 0. 通用约定

- Base URL：开发态 `http://127.0.0.1:18081`（Java）或 `http://127.0.0.1:5000`（Python），Vite dev 经 proxy 无感。
- 成功响应：HTTP 200/201/202 + `{"success": true, ...业务字段}`（业务字段可省 success，见各条；新实现一律带 success）。
- 错误响应：`{"success": false, "message": "<中文文案>"}` + 语义状态码：
  - 400 参数缺失/非法；401 未登录；403 越权；404 不存在；409 状态冲突；503 依赖不可用（LLM/ASR/评分解析）。
- 分页：本期列表接口不分页（个人版数据量），预留 `?limit=&offset=` 扩展位。
- 时间：ISO-8601 字符串（`2026-09-05T12:00:00`）。
- 鉴权：登录后后端下发 HttpOnly 会话 Cookie（实现名可不同：Java `ai_session` / Flask `session`）；未登录访问 API → 401 JSON；未登录访问页面路由 → 302 `/login`。
- WS 鉴权：升级时校验同一 Cookie，失败 401 不升级。

### SPA 页面路由（前端路由表，后端 SPA fallback 到 `index.html`）

```
/                          首页（公开）
/login  /register          登录/注册（公开）
/applicant/workspace       工作台
/applicant/interview-plans            计划列表
/applicant/interview-plans/:planId    计划详情
/applicant/copilot?plan_id=           Copilot 实时工作区
/applicant/mock-interview?plan_id=    模拟面试工作区
/applicant/resumes         简历中心
/applicant/resume-optimize?plan_id=   简历优化
/applicant/knowledge       知识库
/applicant/reviews         复盘列表
/applicant/reviews/:reviewId          复盘详情
/applicant/profile         个人设置
```

兼容重定向（后端 302，一个发布周期）：`/loginView→/login`、`/registerView→/register`、`/applicant/dashboard→/applicant/workspace`、`/applicant/interview_manage→/applicant/interview-plans`、`/applicant/personal_center→/applicant/profile`、`/applicant/resume_manage→/applicant/resumes`、`/applicant/upload_resume→/applicant/resumes`、`/applicant/reports→/applicant/reviews`、`/applicant/sim_interview|avatar|interviewindex|TestConfig→/applicant/mock-interview`、`/applicant/interview→/applicant/interview-plans`、`/applicant/interview-workspace?mode=`→按 mode 转 copilot/mock-interview。

---

## 1. 认证与会话

| 方法+路径 | 鉴权 | 请求 | 响应 |
|---|---|---|---|
| POST `/api/register` | 无 | JSON：`email`*、`password`*、`confirm_password`*、`role`(默认 applicant)、`full_name`、`gender`、`birthdate`、`education_level`、`work_years`、`expected_position`、`expected_salary` | 201 `{success}`；错误 400 `{message}` |
| POST `/api/login` | 无 | JSON：`email`*、`password`*、`remember?` | 200 `{success, role:"applicant"}`；失败 401 `{message:"邮箱或密码错误"}` |
| POST `/api/logout` | 无 | - | 200 `{success}` |
| GET `/api/check_role` | 可选 | - | 200 `{success, role}` / 401 |

约定：请求体统一 JSON（旧 form 编码为实现差异，前端只发 JSON）。仅 `applicant` 角色可登录（企业端未开放）。

## 2. 个人资料与声纹

| 方法+路径 | 请求 | 响应 |
|---|---|---|
| GET `/api/users` | - | `{user:{full_name,email,phone,expected_position,expected_salary,work_years}}` |
| POST `/api/users/update` | JSON 同名字段，`password?` 可选 | `{success, user}` |
| GET `/api/users/voice-profile` | - | `{voice_profile:{available,audio_url,original_filename,updated_at}}` |
| GET `/api/users/voice-profile/audio` | - | 音频流；404 未录入 |
| POST `/api/users/voice-profile` | multipart `audio`（wav/webm/ogg/mp3/m4a ≤10MB） | 201 `{success, voice_profile}` |
| DELETE `/api/users/voice-profile` | - | `{success, voice_profile:{available:false}}` |

## 3. 简历

| 方法+路径 | 请求 | 响应 |
|---|---|---|
| POST `/api/resumes` | multipart `file`（doc/docx/txt/pdf）、`storage_name` | 201 `{resume_id, resume_data, status, analysis_status:"pending"}`；解析异步，见 status |
| GET `/api/resumes` | - | `{resumes:[{resume_id, filename, upload_date, file_url, status, analysis_status, analysis_error, keywords}]}` |
| GET `/api/resumes/:resumeId/status` | - | `{resume_id, status, analysis_status, analysis_error, keywords}`（轮询） |
| GET `/uploads/:filename` | - | 本人文件流（归属校验），404 无权 |

## 4. 面试计划与准备包

对象 `plan`：`plan_id, user_id, resume_id, company_name, position_name, job_description, extra_requirements, level, tech_tags(string[]), status, preparation_status, created_at, updated_at`。
对象 `pack`（preparation）：`pack_id, plan_id, version, status(draft|needs_review|outdated|confirmed), intro_30, intro_60, intro_90, highlights[], project_followups[], risk_points[], frequent_questions[], star_stories[], review_topics[], source_evidence[], confirmed_at`（数组字段传输为 JSON 数组）。

| 方法+路径 | 请求 | 响应 |
|---|---|---|
| GET `/api/interview-plans` | - | `{plans:[plan]}` |
| POST `/api/interview-plans` | JSON 必填 `company_name, position_name, job_description`；可选 `resume_id, extra_requirements, level, tech_tags[]` | 201 `{plan, preparation, next_action:"attach_resume"|"poll_preparation"|"review_preparation"|"retry_preparation"}` |
| GET `/api/interview-plans/:planId` | - | `{plan}`（含 `packs:[pack]`） |
| PATCH `/api/interview-plans/:planId` | JSON 子集字段 | `{plan}`（源字段变更 → 准备包置 outdated） |
| DELETE `/api/interview-plans/:planId` | - | `{plan}`（软删除 archived） |
| POST `/api/interview-plans/:planId/preparation` | - | 同步 201 `{preparation}` 或异步 202 `{status:"generating", next_action:"poll_preparation"}` |
| PATCH `/api/interview-plans/:planId/preparation/:packId` | JSON 区块字段（intro_30…source_evidence） | `{preparation}`（confirmed 编辑退回 draft） |
| POST `.../preparation/:packId/sections/:field/regenerate` | - | 202 `{field, status:"generating", next_action:"poll_preparation"}`（整包异步重生成，新包落库前旧包可用） |
| POST `.../preparation/:packId/confirm` | - | `{preparation}` |

## 5. Copilot 会话（REST 生命周期）

对象 `session`：`session_id, plan_id, resume_id, preparation_pack_id, status(running|paused|reconnecting|ended), current_turn_number, created_at, updated_at, ended_at`。
对象 `turn`：`turn_id, turn_number, status, partial_transcript, transcript, speaker, answer_points[], reference_answer, follow_up, knowledge_item_ids[], created_at, completed_at`。

| 方法+路径 | 请求 | 响应 |
|---|---|---|
| POST `/api/copilot/sessions` | JSON `{plan_id}` | 201 `{session, warning?:{code:"PREPARATION_OUTDATED", message}}`；409 计划未就绪 |
| GET `/api/copilot/sessions/:sessionId` | - | `{session}`（含 `turns:[turn]`，刷新恢复用） |
| POST `/api/copilot/sessions/:sessionId/pause` | - | `{session}` |
| POST `/api/copilot/sessions/:sessionId/resume` | - | `{session}`；ended → 409 |
| POST `/api/copilot/sessions/:sessionId/end` | - | `{session}`；已结束 → 409 |

## 6. Copilot WebSocket（`/ws/copilot`）

### 握手
`GET /ws/copilot`，Cookie 校验后升级，服务端即推 `{"event":"ready"}`。重连同一会话时由 `copilot_start` 触发 `reconnected`（替代 `started`）。

### 客户端 → 服务端（JSON 文本帧）

```jsonc
{"event":"copilot_start", "session_id":1, "mode":"auto|local|remote",
 "speaker":"candidate|interviewer|auto", "sources":["microphone","system","mixed"],
 "source_sequences":{"microphone":12,"system":7}}   // 断线续传时带上次 ACK 游标
{"event":"copilot_set_speaker","session_id":1,"speaker":"candidate|interviewer"} // 其他值→auto
{"event":"copilot_pause"}  {"event":"copilot_resume"}  {"event":"copilot_end"}
```

### 音频二进制帧（PCM16LE / 16kHz / 单声道；推荐 100ms/帧 ≈ 3200 字节）
- v2（推荐）：8 字节头 + PCM。`[0]=0x41 'A'`，`[1]=0x49 'I'`，`[2]=2`，`[3]=source 码`（0=mixed, 1=microphone, 2=system），`[4..7]=sequence uint32 小端`（每来源独立从 0 递增）。
- v1（兼容）：整帧裸 PCM，来源=连接级 copilot_start 来源。服务端在 `started/reconnected` 下发 `audio_protocol:2` 后客户端必须用 v2。
- 规则：paused 拒收；重复 sequence 幂等丢弃但仍回 ack（reason=duplicate）。

### 服务端 → 客户端事件全集

| event | payload |
|---|---|
| `ready` | `{}` |
| `started` / `reconnected` | `{session_id, audio_protocol:2}` |
| `transcript_partial` | `{session_id, text, version, source:"microphone"\|"system"\|"mixed", speaker:"candidate"\|"interviewer", voice_match?}`（partial 可合并为最新值） |
| `transcript_final` | 同上（final 不丢） |
| `utterance_completed` | `{session_id, text, speaker, reason:"question_feature"\|"silence_boundary"}`（仅完整话轮） |
| `answer_queued` | `{session_id, turn_id, generation_id}` |
| `answer_started` | `{session_id, turn_id, generation_id, answer_points:string[]}`（首字节前可先发占位要点） |
| `answer_delta` | `{session_id, turn_id, generation_id, text}` |
| `answer_completed` | `{session_id, turn_id, generation_id, answer_points[], reference_answer, follow_up, knowledge_item_ids?, error_code?, timing?:{ttfb_ms,total_ms}}`；`error_code∈{LLM_TIMEOUT, LLM_STREAM_ERROR, FACT_GUARD_FALLBACK, STRUCTURED_OUTPUT_FALLBACK}` |
| `audio_ack` | `{session_id, sequence, source, accepted, reason?:"duplicate"}` |
| `ending` | `{session_id}`（end 排水开始） |
| `paused` / `resumed` / `ended` | `{session_id?}` |
| `speaker_changed` | `{session_id, speaker, source}` |
| `error` | `{session_id?, message}` |

会话语义：断连 ≤30s 内重连同 session → `reconnected` 并恢复话轮/版本；`copilot_end` → `ending` → 等待在途回答落库（≤5s 硬超时）→ `ended`。迟到 generation 事件由客户端按 turn_id/generation_id 屏蔽。

## 7. 模拟面试 / 复盘 / 简历优化 / 知识库 / 观测

对象 `interview`：`mock_interview_id, plan_id, status(running|completed|ended), current_turn_number, question_count, current_question, question_outline[], turns[]`；`turn`：`turn_id, turn_number, question, answer, reference_points[], scores{}, strengths[], improvements[], status`。

| 方法+路径 | 请求 | 响应 |
|---|---|---|
| POST `/api/mock-interviews` | `{plan_id}` | 201 `{interview}`；503 LLM 不可用 |
| GET `/api/mock-interviews/:id` | - | `{interview}` |
| POST `/api/mock-interviews/:id/answers` | `{answer}` | 200 `{evaluation:{scores, reference_points, strengths, improvements, fact_risks?, retryable}, interview}`；评分失败 503 + `evaluation.retryable=true` 可重试 |
| POST `/api/mock-interviews/:id/end` | - | `{interview}` |
| POST `/api/reviews/generate` | `{source_type:"copilot"\|"mock", source_id}` | 201 `{review}`（幂等：已存在 200） |
| GET `/api/reviews?plan_id=&source_type=&date_from=&date_to=` | - | `{reviews:[…]}` |
| GET `/api/reviews/:id` | - | `{review:{summary, question_categories[], scores{technical,expression,preparation}, fact_risks[], expression_issues[], weak_topics[], next_actions[], diagnostics[]}}` |
| GET `/api/reviews/:id/export?format=json\|txt` | - | JSON 或 text/plain 附件 |
| POST `/api/resume-optimizations` | `{plan_id}` | 201 `{optimization:{matches[], gaps[], keywords[], suggestions[], confirmed_suggestion_ids[], status:"needs_review"}}`；无简历 409 |
| GET `/api/resume-optimizations/latest?plan_id=` | - | `{optimization}` / 404 |
| POST `/api/resume-optimizations/:id/suggestions/:suggestionId/confirm` | - | `{optimization}` |
| GET `/api/knowledge?q=&tech_tag=&role_tag=&difficulty=&category=` | - | `{items:[…], knowledge_status:{total,enabled,enhancement_enabled}}` |
| POST `/api/knowledge` | JSON 条目（`title`*） | 201 `{item}` |
| PATCH `/api/knowledge/:itemId` | JSON 子集 | `{item}` |
| DELETE `/api/knowledge/:itemId` | - | `{success}` |
| POST `/api/knowledge/import` | multipart `file`（json/md/txt） | `{result:{created,updated,skipped,failed}}` |
| POST `/api/knowledge/rebuild-index` | - | `{checked, message}` |
| GET `/api/ready` | - | `{ready, db, asr, asr_provider, speaker?}`；未就绪 503 |
| GET `/api/metrics` | - | 进程指标（copilot_connections, counters, executors） |
| GET `/api/health` | - | `{status:"ok"}` |

事实边界（生成类接口共同约束）：所有 LLM 产出不得虚构候选人项目/职责/数字；缺真实实践时用「理论理解」或「如果由我设计」表达；守卫触发以 `FACT_GUARD_FALLBACK` / `fact_guard_triggered` / `fact_risks` 呈现，不静默丢弃。

---

## 8. 实现差异登记（迁移中允许，目标为清零）

| # | 差异 | 现状 | 收敛动作 | 状态 |
|---|---|---|---|---|
| D1 | 传输层 | Python 原为 Socket.IO；契约=裸 WS | 新增 `utils/copilot_ws.py` 裸 WS `/ws/copilot`（flask-sock），复用同一服务栈；Socket.IO 保留 legacy | 已完成(P4) |
| D2 | REST 前缀 | Java 原 API 多数无 `/api` 前缀 | `RouteSupport.route()` 注册即带 `/api` 别名；登录/注册支持 JSON body（Content-Type 嗅探，旧 form 流式保留） | 已完成(P4) |
| D3 | 来源命名 | Java 事件原用 `display` | 出站事件统一 `system`（`externalSource`）；v2 帧源码 2 不变，入站 display/system/local/remote 均收 | 已完成(P4) |
| D4 | WS 错误字段 | Java 原 `error.text` | 出站同时带 `message` + `text`（过渡期） | 已完成(P4) |
| D5 | 缺失事件 | Python 原 Socket.IO 版无 `answer_queued`/`generation_id`/`ending`/`reconnected`/`ready`/`audio_protocol` | 裸 WS 通道已全部实现 | 已完成(P4) |
| D6 | REST 补齐 | Java 缺准备包 PATCH/regenerate、knowledge import/rebuild-index、resume-optimize 单条 confirm、/api/health、/api/resumes 别名、logout JSON | 已全部补齐；另 register/login JSON body、/api/check_role 已就绪 | 已完成(P4) |
| D7 | 认证载体 | Java `ai_session` 内存 session；Flask 签名 cookie session | 属实现细节，行为对齐契约即可；不强制统一存储 | 长期共存 |
| D8 | 遗留接口 | Python 企业端(job/application/company/score-weight/avatar chat 等)与老 Socket.IO 面试 | 不入契约、前端不调用；P5 清理时评估归档 | 待办(P5) |
| D9 | SPA 托管开关 | Java=classpath 存在 `static/app` 即启用（`pnpm build:server` 产出）；Python=`SPA_ENABLED=1` 环境变量启用 | 默认关闭时两后端保持旧页面行为（测试兼容），P5 删除旧页面后默认启用 | P5 收敛 |
| D10 | 区块重生成语义 | 契约原文 `{field, preparation}`；实现为整包异步重生成 | 契约已修订为 `{field, status:"generating", next_action}`；新包落库前旧包可用 | 已对齐 |
