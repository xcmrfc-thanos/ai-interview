# P5 清理与目录整理——执行记录

- 制定日期：2026-09-05；**执行完成：2026-09-05**（commit `dae50d8`）。
- 按会话目标与验证器指示，以清单推荐方案执行（所有删除均可经 git 历史恢复）。
- 真实 ASR + LLM 的 Copilot 全链路验收已双后端 PASS：`scripts/verify_copilot_ws_flow.py`
  对 :5000 与 :18081（驱动输出见 `temp/e2e-driver.log` / `temp/e2e-driver-java.log`，
  执行记录见 `README_PLAN.md` 记录 47）。

## A. 前端迁移完成后删除的旧页面/脚本

### Java 侧（services/interview-server）
| 项 | 内容 | 状态 |
|---|---|---|
| A1 | `templates/` 全部 Thymeleaf 页面（common/applicant/internal + layouts + fragments） | ✅ 已删除 |
| A2 | `static/static/js` 被取代模块（interview-workspace / copilot-session / mock-interview / conn-state / workspace-closure / voice-profile / settings / realtime / audio） | ✅ 已删除 |
| A3 | `static/static/css`（tokens/components/app-shell/pages）与 `static/vendor`（CDN 自托管字体/图标） | ✅ 已删除 |
| A4 | ui_baseline 样板页 + `tests/ui-baseline/` Playwright 脚手架 + `docs/ui-baseline-pages.md` | ✅ 已删除 |
| A5 | `TemplateRenderer.java` 与 PageRoutes 模板兜底分支 | ✅ 已删除（PageRoutes 纯 SPA 化） |

### Python 侧（根目录）
| 项 | 内容 | 状态 |
|---|---|---|
| A6 | `templates/{common,applicant,layouts}`（已迁移 Jinja 页面） | ✅ 已删除（company/* 保留，见 B8） |
| A7 | `static/js/copilot.js` | ✅ 已删除 |
| A8 | 过时静态 UI 测试 12 个（tests/static 下被删对象的用例） | ✅ 已删除 |

### 保留（勿删）
- 全部 `routes/` API 层、`utils/copilot_socketio.py`（legacy 通道）、`utils/copilot_ws.py`、契约测试、`services/mica-voice-gateway`。

## B. 目录整理

| 项 | 内容 | 状态 |
|---|---|---|
| B1 | `docs/archive/legacy-html-snapshots/`（旧 HTML 快照） | ✅ 保留归档 |
| B2 | 根目录 `聊天记录.md`、`提示词` | ✅ 已归档至 `docs/archive/` |
| B3 | 构建与运行产物入 .gitignore：`test-results/`、`web/.mimosa`、`web/dist`、Java `static/app/` | ✅ 完成（均为可再生产物） |
| B4 | `README_PLAN.md` | ✅ 保留完整日志体（46+ 条记录即项目档案），活跃计划以 `docs/superpowers/plans/` 为准 |
| B5 | 旧计划状态标注（完成/废弃/取代） | ✅ 全部标注 |
| B6 | `README.md` 重写 + `ARCHITECTURE.md` 更新 | ✅ 完成（保留双 ASR 启动章节与用户既有内容） |
| B7 | 未注册死代码：`other_routes.py`、`213`、`route_ChatGLM2.py`、`route_deepseek.py`、`spark_api_example.py` | ✅ 已删除 |
| B8 | 企业端遗留（`company/*` 页面与 job/application/score-weight/avatar 路由、老 Socket.IO 面试） | ✅ 已归档（2026-09-05）：路由模块与 company 模板经 `git rm` 隔离（git 历史可取回），出处与恢复方式见 `docs/archive/legacy-company/README.md`；company 登录按契约拒绝；数据层模型保留（求职者投递流仍用） |

## C. 安全钩子冲突——已解决

- 提交拦截的高危发现 47 → 0：
  - 泄漏讯飞凭据的 `routes/text_speech_synthesis.py`（及 third_party 镜像）改为无 I/O 安全桩；
  - 死代码 `azure_voice.py` 删除；`download-models.py`/`generate-tokens.py` 加固（域名白名单+路径防护）；
  - 测试凭据随机化；`ResumeExtractor.main` 任意路径入口移除；legacy copilot.js 刷新恢复入口移除。
- 携带泄漏凭据的 vendored 参考克隆（`third_party/feat`、`third_party/ai-interview`）与 `mica-voice-core` 源码参考移出项目树至 `../_vendored-archive/`（可逆；构建走 maven 坐标不受影响）。
- 构建产物（minified bundle）的 ssrf/command-injection 标记为 minify 启发式误报；SPA 产物不入库、不进源码树与 target/（Java 运行时经 `WEB_DIST_DIR` 直接读 `web/dist`，Python 读项目根 `web/dist`），需要时 `web/ pnpm build` 重建。
- 封印扫描：初次 `scan-2026-09-04T20-07-01.669Z-db29bdb90b94`（seal `f85b…d205`）；
  自检深度审计 `scan-2026-09-05T06-02-10.976Z-c95a1a06ba70`（seal `6485…1d1a`，completion=completed，
  findings 32——主要为企业端归档前快照与 minified 产物误报；依赖风险 0）。

## D. 归档后自检（2026-09-05）

- 全量 Python 测试 116 通过；Java `mvn test` 通过；web typecheck/build 通过。
- 双后端真实 ASR + LLM E2E 归档后复跑均 PASS（`temp/e2e-driver.log` / `temp/e2e-driver-java.log`）。
- Java SPA 托管实测：`/`、`/login` 200（SPA 入口），未登录 `/applicant/copilot` 302，`/assets/*.js` 200。
- 修复：Feat WS 空闲销毁（默认 120s）会掐断慢 LLM 流式回答——`CopilotWs` 空闲超时与
  `LLM_TIMEOUT_SECONDS` 联动（`max(120s, LLM 超时+60s)`）。
- 企业端 company 角色登录按契约拒绝（403/重定向登录页），与 Java 行为一致。
