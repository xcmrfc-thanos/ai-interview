# 个人面试工作台验收记录

## 自动浏览器验收

运行前启动 Flask，并准备本地验收账号、简历、计划、准备包、复盘和知识条目：

```powershell
$env:E2E_BASE_URL = "http://127.0.0.1:5000"
npx --package @playwright/test playwright test tests/e2e/visual-audit.spec.js --reporter=line
```

测试覆盖 1440x900、1024x768、768x1024、375x812，逐页检查公开首页、登录、注册、工作台、计划、准备包、Copilot、模拟面试、简历中心、复盘、知识库和个人设置，并保存截图到 `tests/e2e/screenshots/`。

2026-08-21 最新结果：`4 passed (1.8m)`。除页面截图外，还检查了横向溢出、超长动态文本、44px 触控目标、可见键盘焦点、Escape 关闭抽屉/弹窗和 `prefers-reduced-motion`。

## 本地真实服务链路

### mica-voice 文件音频

- 输入：28 秒本地 MP3 转换为 `PCM S16LE / 16kHz / mono`，按 100ms 实时节奏发送。
- 结果：270 个非空 partial，首个非空 partial 1320ms，final 28422ms。
- 结论：WebSocket、增量识别和 final 结果可用；首字超过 1 秒目标。

### Copilot 文件音频闭环

- 输入：系统语音合成的中文面试问题，5.65 秒，转换为 `PCM S16LE / 16kHz / mono`。
- 音频帧：发送 57 帧，收到 57 个 ACK，最终游标为 56。
- ASR：首个非空 partial 1245ms，final 5795ms。
- 转写：`请介绍一下你在订单服务项目中如何排查性能`。
- 事件顺序：`transcript_final -> answer_started -> answer_delta -> answer_completed`。
- 持久化：完成事件后通过 HTTP 恢复到已落库 turn，并成功生成统一复盘。
- 性能：回答要点约 22327ms、完整回答约 22873ms，未达到 3 秒/5 秒目标。

### 模拟面试和简历优化

- 同一计划真实生成 8 道题并全部提交回答，四维评分和逐题反馈齐全。
- 完成后生成统一复盘，包含 6 个下一步任务和 2 个薄弱维度。
- 从同一计划生成简历优化结果；建议状态为 `needs_review`，未自动确认或覆盖原简历。

## 真实设备验收

以下项目必须记录真实证据，无法执行时保留为未验证：

- 真实麦克风与 mica-voice 增量转写。
- 真实 LLM 准备包、回答和模拟面试评分：已使用本地文件音频和真实模型验证，但实体麦克风仍未验证。
- 手机浏览器麦克风权限、后台、锁屏、距离、音量与噪声表现。
- 连续运行 30 分钟的延迟、内存、误触发和重连记录。

## 2026-08-23 Feat 集成服务验收追加

- 30 分钟连续稳定性：feat 服务连续运行 1808 秒，80 轮真实语音会话，0 错误、0 重连、640 事件（partial/final），无 stream 泄漏。通过。
- ASR 首字延迟（1 倍实时节奏、真实 wav）：首个转写事件（final）约 3.2 秒，partial 约 4.8 秒；未达到 1 秒目标。
- LLM 回答延迟：`/api/copilot/answer` 实测 9.9 秒（qwen3.7-flash 约 850 字符）；未达到 3 秒/5 秒目标。
- 待人工验收：实体麦克风、目标手机浏览器、断网恢复。
