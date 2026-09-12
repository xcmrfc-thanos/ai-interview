(() => {
  "use strict";

  const list = document.getElementById("resume-list");
  const uploadPanel = document.getElementById("resume-upload-panel");
  const uploadForm = document.getElementById("resume-upload-form");
  const preview = document.getElementById("resume-preview");
  let resumes = [];
  const terminalAnalysisStates = new Set(["completed", "failed"]);

  function setUploadStatus(message, error = false) {
    const status = document.getElementById("resume-upload-status");
    status.textContent = message;
    status.className = error ? "form-error" : "";
  }

  async function readJsonPayload(response) {
    const text = await response.text();
    if (!text.trim()) throw new Error("服务未返回结果，请稍后重试");
    try {
      return JSON.parse(text);
    } catch (_error) {
      throw new Error("服务返回格式异常，请稍后重试");
    }
  }

  async function uploadResume(formData) {
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), 60000);
    try {
      return await fetch("/api/upload_resume", {
        method: "POST",
        body: formData,
        signal: controller.signal,
      });
    } catch (error) {
      if (error.name === "AbortError") {
        throw new Error("上传解析超时，请检查模型服务配置后重试");
      }
      throw error;
    } finally {
      window.clearTimeout(timeoutId);
    }
  }

  async function loadResumes() {
    try {
      const response = await fetch("/api/get_resumesbyUser");
      const payload = await readJsonPayload(response);
      if (!response.ok || !payload.success) throw new Error(payload.message || "加载简历失败");
      resumes = payload.resumes || [];
      renderResumes(resumes);
    } catch (error) {
      list.innerHTML = `<p class="empty-state">${escapeText(error.message)}</p>`;
      document.getElementById("resume-count").textContent = "加载失败";
    }
  }

  function renderResumes(values) {
    list.replaceChildren();
    document.getElementById("resume-count").textContent = `${values.length} 份简历`;
    if (!values.length) {
      const empty = document.createElement("p");
      empty.className = "empty-state";
      empty.textContent = "还没有简历。上传后即可创建面试计划。";
      list.appendChild(empty);
      return;
    }
    values.forEach((resume) => list.appendChild(resumeRow(resume)));
  }

  function resumeRow(resume) {
    const row = document.createElement("article");
    row.className = "resume-row";
    const icon = document.createElement("span");
    icon.className = "resume-file-icon";
    icon.innerHTML = '<i class="bx bx-file"></i>';
    const identity = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = resume.filename;
    const subtitle = document.createElement("small");
    subtitle.textContent = `简历 #${resume.resume_id}${resume.report ? " · 已有旧版分析报告" : ""}`;
    identity.append(title, subtitle);
    const status = document.createElement("div");
    status.className = "resume-row-status";
    const badge = document.createElement("span");
    badge.className = "resume-status";
    badge.dataset.state = resume.analysis_status || "pending";
    badge.textContent = analysisLabel(resume.analysis_status);
    const keywords = document.createElement("div");
    keywords.className = "resume-keywords";
    (resume.keywords || []).slice(0, 4).forEach((keyword) => {
      const tag = document.createElement("span");
      tag.textContent = keyword;
      keywords.appendChild(tag);
    });
    status.append(badge, keywords);
    const time = document.createElement("time");
    time.textContent = formatDate(resume.upload_date);
    const actions = document.createElement("div");
    actions.className = "resume-row-actions";
    const filePreview = document.createElement("button");
    filePreview.className = "button button-secondary";
    filePreview.type = "button";
    filePreview.innerHTML = '<i class="bx bx-file-blank"></i>预览';
    filePreview.addEventListener("click", () => openFilePreview(resume.resume_id));
    actions.appendChild(filePreview);
    const view = document.createElement("button");
    view.className = "button button-secondary";
    view.type = "button";
    view.innerHTML = '<i class="bx bx-show"></i>查看事实';
    view.addEventListener("click", () => viewResume(resume.resume_id));
    actions.appendChild(view);
    if (resume.report) {
      const report = document.createElement("button");
      report.className = "button button-secondary";
      report.type = "button";
      report.textContent = "旧版报告";
      report.addEventListener("click", () => openLegacyReport(resume.resume_id));
      actions.appendChild(report);
    }
    row.append(icon, identity, status, time, actions);
    return row;
  }

  function analysisLabel(status) {
    return {
      pending: "等待 AI 分析",
      analyzing: "AI 分析中",
      completed: "AI 分析完成",
      failed: "AI 分析失败（不影响上传）",
    }[status] || "已上传，可查看本地关键词";
  }

  async function pollResumeStatus(resumeId) {
    for (let attempt = 0; attempt < 30; attempt += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 2000));
      try {
        const response = await fetch(`/api/resumes/${resumeId}/status`);
        const payload = await readJsonPayload(response);
        if (!response.ok || !payload.success) return;
        if (terminalAnalysisStates.has(payload.analysis_status)) {
          await loadResumes();
          if (payload.analysis_status === "failed") {
            setUploadStatus(`文件已上传，本地关键词可用；AI 分析暂未完成：${payload.analysis_error || "稍后可重试"}`, true);
          }
          return;
        }
      } catch (_error) {
        return;
      }
    }
  }

  async function openFilePreview(resumeId) {
    const dialog = document.getElementById("resume-file-preview");
    const content = document.getElementById("resume-file-preview-content");
    content.innerHTML = '<p class="empty-state">正在加载…</p>';
    dialog.showModal();
    try {
      const response = await fetch(`/api/resumes/${resumeId}/preview`);
      const payload = await readJsonPayload(response);
      if (!response.ok || !payload.success) throw new Error(payload.message || "加载预览失败");
      const holder = document.createElement("div");
      window.renderResumePreview(holder, payload.preview);
      content.replaceChildren(...holder.childNodes);
    } catch (error) {
      content.innerHTML = `<p class="form-error">${error.message}</p>`;
    }
  }

  async function viewResume(resumeId) {
    try {
      const response = await fetch(`/api/get_parsed_data?resume_id=${resumeId}`);
      const payload = await readJsonPayload(response);
      if (!response.ok || !payload.success) throw new Error(payload.message || "读取简历失败");
      let value = payload.parsed_data;
      if (typeof value === "string") {
        try { value = JSON.parse(value); } catch (_error) { /* Keep original text. */ }
      }
      document.getElementById("resume-preview-content").textContent =
        typeof value === "string" ? value : JSON.stringify(value, null, 2);
      preview.showModal();
    } catch (error) {
      setUploadStatus(error.message, true);
    }
  }

  async function openLegacyReport(resumeId) {
    try {
      const response = await fetch("/api/analyze_resumeReport", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ resume_id: resumeId }),
      });
      const payload = await readJsonPayload(response);
      if (response.ok && payload.success) window.location.href = "/applicant/resume_report";
      else setUploadStatus(payload.message || "旧版报告不可用", true);
    } catch (error) {
      setUploadStatus(error.message, true);
    }
  }

  function formatDate(value) {
    return value ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium" }).format(new Date(value)) : "";
  }

  function escapeText(value) {
    const element = document.createElement("span");
    element.textContent = value;
    return element.innerHTML;
  }

  document.querySelector("[data-upload-toggle]").addEventListener("click", () => {
    uploadPanel.hidden = !uploadPanel.hidden;
    if (!uploadPanel.hidden) document.getElementById("resume-file").focus();
  });
  document.querySelector("[data-preview-close]").addEventListener("click", () => preview.close());
  document.querySelector("[data-file-preview-close]").addEventListener("click", () => document.getElementById("resume-file-preview").close());
  document.getElementById("resume-search").addEventListener("input", (event) => {
    const query = event.target.value.trim().toLocaleLowerCase();
    renderResumes(resumes.filter((resume) => resume.filename.toLocaleLowerCase().includes(query)));
  });
  uploadForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = uploadForm.querySelector('button[type="submit"]');
    button.disabled = true;
    setUploadStatus("正在上传文件并提取本地关键词…");
    try {
      const response = await uploadResume(new FormData(uploadForm));
      const payload = await readJsonPayload(response);
      if (!response.ok || !payload.success) throw new Error(payload.message || "上传失败");
      setUploadStatus("上传完成，关键词已提取；AI 深度分析将在后台进行。");
      uploadForm.reset();
      await loadResumes();
      pollResumeStatus(payload.resume_id);
    } catch (error) {
      setUploadStatus(error.message, true);
    } finally {
      button.disabled = false;
    }
  });
  loadResumes();
})();
