# legacy-company · 企业端遗留归档（2026-09-05 归档，git rm 隔离）

企业端（公司账号）功能为契约外遗留（见 `docs/api-contract.md` §8 D8），前端统一计划
P5 执行时从工作树移除。**所有文件均可在 git 历史中取回**：

```powershell
git log --oneline --diff-filter=D -- "routes/route_Job.py"   # 找到删除所在提交
git show <删除提交>^:routes/route_Job.py                      # 取回归档前内容
```

## 归档清单（原路径）

### 路由模块（routes/）
- `route_Job.py` — 岗位发布/列表（`/api/upload_job`、`/api/get_jobs` 等）
- `route_appliacntion.py` — 企业求职者管理（渲染 company/candidates、manage_interview 页面）
- `score_weight.py` — 企业五维评分权重（`/api/score-weights` 等）
- `route_AIInterview.py` — 数字人 AI 面试（`/api/avatar/chat`、`/api/interview_report`）
- `route_company.py` — 企业资料与反馈（`/api/companies` 等）
- `text_speech_synthesis.py` — 讯飞 TTS 占位桩（原实现含已泄漏凭据，必须轮换密钥后才可复活）

### 模板（templates/company/）
- dashboard / jobManage / companyCenter(profile) / score_weights
- application_detail / candidates / manage_interview / interview_report

## 配套移除的接线

- `routes/__init__.py`：job_bp / applicant_bp / score_weight_bp / AIInterview_bp / company_bp 的导入与注册
- `app.py`：`/company/*` 页面视图、`stream_chat`/`_get_llm_client` 死助手、`company_required` 导入
- `utils/socketio_service.py`：老 Socket.IO 面试处理器（`start_interview`/`user_input`/
  `evaluation_report`）及其 prompt/助手，仅保留 SocketIO 装配 + Copilot 处理器挂载
- `routes/route_login.py`：company 角色登录按契约拒绝（仅 applicant 可登录）

## 数据层保留

`models/` 中的 Company/Job/Application/AIInterview/InterviewScores/ResumeScores 模型
**未归档**：求职者投递流（resume_submit）仍读写 Application，历史数据仍可查询。
如需复活企业端：恢复上述文件、重新注册蓝图，并先完成讯飞密钥轮换。
