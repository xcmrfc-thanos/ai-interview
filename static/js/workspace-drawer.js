(() => {
  const root = document.querySelector("[data-drawers]");
  if (!root) return;
  const backdrop = root.querySelector("[data-drawer-backdrop]");
  const drawers = root.querySelectorAll("[data-drawer]");
  const resumeId = root.dataset.resumeId || "";

  const close = () => {
    drawers.forEach((drawer) => { drawer.hidden = true; });
    if (backdrop) backdrop.hidden = true;
  };
  const open = async (name) => {
    drawers.forEach((drawer) => { drawer.hidden = drawer.dataset.drawer !== name; });
    if (backdrop) backdrop.hidden = false;
    const panel = root.querySelector(`[data-drawer="${name}"]`);
    if (panel) panel.querySelector("button, a")?.focus();
    if (name === "resume") await loadResumePreview(panel);
  };

  root.querySelectorAll("[data-drawer-open]").forEach((button) => {
    button.addEventListener("click", () => open(button.dataset.drawerOpen));
  });
  root.querySelectorAll("[data-drawer-close]").forEach((button) => {
    button.addEventListener("click", close);
  });
  backdrop?.addEventListener("click", close);
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") close();
  });

  const loaded = {};
  async function loadResumePreview(panel) {
    const body = panel?.querySelector("[data-resume-preview]");
    if (!body) return;
    if (!resumeId) {
      body.innerHTML = '<p class="empty-state">当前未选择面试计划，先关联简历并进入计划详情。</p>';
      return;
    }
    if (loaded[resumeId]) { body.innerHTML = loaded[resumeId]; return; }
    body.innerHTML = '<p class="preview-loading">正在加载简历…</p>';
    try {
      const response = await fetch(`/api/resumes/${resumeId}/preview`);
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || "加载简历失败");
      const holder = document.createElement("div");
      window.renderResumePreview(holder, payload.preview);
      loaded[resumeId] = holder.innerHTML;
      body.innerHTML = loaded[resumeId];
    } catch (error) {
      body.innerHTML = `<p class="form-error">${error.message}</p>`;
    }
  }
})();