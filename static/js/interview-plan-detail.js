(() => {
  "use strict";

  const root = document.querySelector("[data-plan-id]");
  if (!root) return;
  const planId = root.dataset.planId;
  const status = document.getElementById("plan-detail-status");
  const preparation = window.PREPARATION || {};
  const REQUEST_TIMEOUT_MS = 70_000;
  let selectedIntro = "intro_60";

  const setStatus = (message, state = "") => {
    status.textContent = message;
    status.dataset.state = state;
  };

  const requestJson = async (url, options = {}) => {
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const response = await fetch(url, { ...options, signal: controller.signal });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload.message || `请求失败 (${response.status})`);
      return payload;
    } catch (error) {
      if (error.name === "AbortError") throw new Error("请求超时，请稍后重试。", { cause: error });
      throw error;
    } finally {
      window.clearTimeout(timeoutId);
    }
  };

  const renderList = (field, values) => {
    const section = document.querySelector(`[data-prep-section="${field}"]`);
    const list = section?.querySelector(".detail-list");
    if (!list) return;
    list.replaceChildren();
    const items = Array.isArray(values) ? values : [];
    if (!items.length) {
      const empty = document.createElement("li");
      empty.className = "prep-empty";
      empty.textContent = "暂无内容";
      list.appendChild(empty);
      return;
    }
    items.forEach((value) => {
      const item = document.createElement("li");
      item.textContent = typeof value === "object" ? value.text || value.question || JSON.stringify(value) : value;
      list.appendChild(item);
    });
  };

  const renderIntro = () => {
    document.getElementById("intro-content").textContent = preparation[selectedIntro] || "暂无内容";
    document.querySelectorAll("[data-intro]").forEach((button) => {
      button.setAttribute("aria-selected", String(button.dataset.intro === selectedIntro));
    });
  };

  const updateSectionState = (field, message, stateName = "") => {
    const section = document.querySelector(`[data-prep-section="${field}"]`);
    const indicator = section?.querySelector("[data-section-state]");
    if (indicator) {
      indicator.textContent = message;
      indicator.dataset.state = stateName;
    }
  };

  const regenerateSection = async (field, button) => {
    const packId = preparation.pack_id;
    if (!packId) return;
    const actualField = field === "intro" ? selectedIntro : field;
    button.disabled = true;
    updateSectionState(field, "正在生成…", "loading");
    setStatus(`正在重新生成${field === "intro" ? "自我介绍" : "该准备区块"}，请稍候…`);
    try {
      const payload = await requestJson(
        `/api/interview-plans/${planId}/preparation/${packId}/sections/${actualField}/regenerate`,
        { method: "POST" },
      );
      Object.assign(preparation, payload.preparation || {});
      if (field === "intro") renderIntro(); else renderList(field, preparation[actualField]);
      updateSectionState(field, "刚刚更新", "ready");
      setStatus("内容已更新，请核对事实后再确认准备包。");
    } catch (error) {
      updateSectionState(field, "生成失败", "error");
      setStatus(error.message, "error");
    } finally {
      button.disabled = false;
    }
  };

  const waitForPreparation = async () => {
    for (let attempt = 0; attempt < 60; attempt += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 2000));
      let data;
      try {
        data = await requestJson(`/api/interview-plans/${planId}`);
      } catch (error) {
        setStatus(error.message, "error");
        return;
      }
      if (data.plan?.preparation_status !== "generating") { location.reload(); return; }
      setStatus(`准备内容生成中…已等待 ${Math.round((attempt + 1) * 2)} 秒`);
    }
    setStatus("生成时间较长，请稍后刷新查看结果。", "error");
  };

  document.querySelectorAll("[data-intro]").forEach((button) => button.addEventListener("click", () => {
    selectedIntro = button.dataset.intro;
    renderIntro();
  }));
  document.querySelectorAll("[data-regenerate-field]").forEach((button) => button.addEventListener("click", () => {
    regenerateSection(button.dataset.regenerateField, button);
  }));

  document.querySelector("[data-generate-pack]")?.addEventListener("click", async (event) => {
    event.currentTarget.disabled = true;
    setStatus("正在生成准备内容…");
    try {
       const data = await requestJson(`/api/interview-plans/${planId}/preparation`, { method: "POST" });
       if (data.status === "generating") { await waitForPreparation(); event.currentTarget.disabled = false; return; }
      location.reload();
    } catch (error) {
      setStatus(error.message, "error");
      event.currentTarget.disabled = false;
    }
  });

  const attachSelect = document.querySelector("[data-attach-resume]");
  const attachButton = document.querySelector("[data-attach-resume-submit]");
  const attachStatus = document.querySelector("[data-attach-resume-status]");
  if (attachSelect && attachButton) {
    fetch("/api/get_resumes").then((response) => response.json()).then((data) => {
      const resumes = data.resumes || [];
      attachSelect.replaceChildren();
      if (!resumes.length) {
        attachSelect.add(new Option("暂无简历，请先上传", ""));
        attachButton.disabled = true;
        attachStatus.textContent = "还没有可关联的简历，请先上传简历。";
        return;
      }
      resumes.forEach((resume) => attachSelect.add(new Option(resume.filename, resume.id)));
    }).catch(() => { attachStatus.textContent = "简历加载失败，请刷新后重试。"; });
    attachButton.addEventListener("click", async () => {
      const resumeId = Number(attachSelect.value);
      if (!resumeId) { attachStatus.textContent = "请选择一份简历。"; return; }
      attachButton.disabled = true;
      attachStatus.textContent = "正在关联简历并准备内容…";
      try {
        await requestJson(`/api/interview-plans/${planId}`, {
          method: "PATCH", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ resume_id: resumeId }),
        });
        await requestJson(`/api/interview-plans/${planId}/preparation`, { method: "POST" });
        location.reload();
      } catch (error) {
        attachStatus.textContent = error.message;
        attachButton.disabled = false;
      }
    });
  }

  if (root.dataset.preparationStatus === "generating") waitForPreparation();
  document.querySelector("[data-confirm-pack]")?.addEventListener("click", async (event) => {
    event.currentTarget.disabled = true;
    try {
      await requestJson(`/api/interview-plans/${planId}/preparation/${event.currentTarget.dataset.packId}/confirm`, { method: "POST" });
      setStatus("准备包已确认");
      location.reload();
    } catch (error) {
      setStatus(error.message, "error");
      event.currentTarget.disabled = false;
    }
  });
  renderIntro();
})();
