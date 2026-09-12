/* interview-workspace.js
 *
 * 实时辅助 / AI 模拟面试 已拆分为两个独立路由（/applicant/copilot 与
 * /applicant/mock-interview），不再通过 interview-workspace?mode=... 的
 * query 切换。该脚本保留为空壳以便旧的 HTML 引用不会 404；具体逻辑
 * 分别由 copilot.js 与 mock-interview.js 提供。
 */
(() => {
  "use strict";
  const workspace = document.querySelector("[data-interview-workspace]");
  if (!workspace) return;
  // 防御：历史 URL 仍可能进入该容器；如果既没有 data-mode 也没有渲染面板，
  // 给出温和提示，避免页面"看似有内容实际空跑"。
  if (!workspace.dataset.mode) {
    workspace.dataset.mode = "copilot";
  }
})();