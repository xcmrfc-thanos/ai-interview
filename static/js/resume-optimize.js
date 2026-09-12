(() => {
  "use strict";

  const root = document.querySelector("[data-resume-optimize-root]");
  if (!root) return;
  const planId = Number(root.dataset.planId);
  let currentOptimization = null;
  const $ = (id) => document.getElementById(id);

  function setStatus(message, state = "") {
    const status = $("resume-optimize-status");
    status.textContent = message;
    status.dataset.state = state;
  }

  async function requestJson(url, options = {}) {
    const response = await fetch(url, {
      headers: { "Content-Type": "application/json", ...(options.headers || {}) },
      ...options,
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(payload.message || `请求失败 (${response.status})`);
      error.status = response.status;
      throw error;
    }
    return payload;
  }

  async function generateOptimization() {
    const button = $("generate-resume-optimization");
    button.disabled = true;
    setStatus("正在对照简历事实与岗位要求生成建议…");
    try {
      const payload = await requestJson("/api/resume-optimizations", {
        method: "POST",
        body: JSON.stringify({ plan_id: planId }),
      });
      renderOptimization(payload.optimization);
      setStatus("建议已生成。涉及新数字或未确认事实的内容已单独标记。");
    } catch (error) {
      setStatus(error.message, "error");
    } finally {
      button.disabled = false;
    }
  }

  async function loadLatest() {
    try {
      const payload = await requestJson(`/api/resume-optimizations/latest?plan_id=${planId}`);
      renderOptimization(payload.optimization);
      setStatus("已加载最近一次优化结果。");
    } catch (error) {
      if (error.status !== 404) setStatus(error.message, "error");
    }
  }

  function renderOptimization(optimization) {
    currentOptimization = optimization;
    $("resume-match-summary").hidden = false;
    renderList($("resume-matches"), optimization.matches || [], "暂无明确匹配项");
    renderList($("resume-gaps"), optimization.gaps || [], "暂无明确缺口");
    renderKeywords(optimization.keywords || []);
    const container = $("resume-suggestions");
    container.replaceChildren();
    if (!optimization.suggestions?.length) {
      const empty = document.createElement("p");
      empty.className = "empty-state";
      empty.textContent = "当前没有可展示的改写建议。";
      container.appendChild(empty);
      return;
    }
    optimization.suggestions.forEach((suggestion) => {
      container.appendChild(suggestionItem(suggestion, optimization.confirmed_suggestion_ids || []));
    });
  }

  function renderList(parent, values, fallback) {
    parent.replaceChildren();
    const items = values.length ? values : [fallback];
    items.forEach((value) => {
      const item = document.createElement("li");
      item.textContent = value;
      parent.appendChild(item);
    });
  }

  function renderKeywords(values) {
    const parent = $("resume-keywords");
    parent.replaceChildren();
    (values.length ? values : ["暂无关键词"]).forEach((value) => {
      const item = document.createElement("span");
      item.textContent = value;
      parent.appendChild(item);
    });
  }

  function suggestionItem(suggestion, confirmedIds) {
    const article = document.createElement("article");
    article.className = "resume-suggestion";
    article.dataset.suggestionId = suggestion.suggestion_id;
    article.dataset.confirmed = String(confirmedIds.includes(suggestion.suggestion_id));
    const body = document.createElement("div");
    body.className = "suggestion-body";
    body.append(
      suggestionColumn("原简历事实", suggestion.original_fact || "未定位到明确原文"),
      suggestionColumn("建议文本", suggestion.candidate_text || "")
    );
    const meta = document.createElement("div");
    meta.className = "suggestion-meta";
    const reason = document.createElement("p");
    reason.className = "suggestion-reason";
    reason.textContent = suggestion.rationale || "依据当前 JD 调整表达顺序";
    if (suggestion.requires_confirmation) {
      const flag = document.createElement("span");
      flag.className = "confirmation-flag";
      flag.textContent = suggestion.unverified_claims?.length
        ? `需核实：${suggestion.unverified_claims.join("、")}`
        : "含待确认事实";
      reason.appendChild(flag);
    }
    const actions = document.createElement("div");
    actions.className = "suggestion-actions";
    const copy = document.createElement("button");
    copy.className = "button button-secondary";
    copy.type = "button";
    copy.innerHTML = '<i class="bx bx-copy"></i>复制';
    copy.addEventListener("click", () => copySuggestion(suggestion.candidate_text));
    const confirm = document.createElement("button");
    confirm.className = "button button-secondary";
    confirm.type = "button";
    confirm.disabled = confirmedIds.includes(suggestion.suggestion_id);
    confirm.textContent = confirm.disabled ? "已确认" : "确认建议";
    confirm.addEventListener("click", () => confirmSuggestion(suggestion.suggestion_id));
    actions.append(copy, confirm);
    meta.append(reason, actions);
    article.append(body, meta);
    return article;
  }

  function suggestionColumn(title, text) {
    const column = document.createElement("section");
    column.className = "suggestion-column";
    const heading = document.createElement("h3");
    heading.textContent = title;
    const paragraph = document.createElement("p");
    paragraph.textContent = text;
    column.append(heading, paragraph);
    return column;
  }

  async function copySuggestion(text) {
    try {
      await navigator.clipboard.writeText(text || "");
      setStatus("建议文本已复制，请粘贴后再次核对事实。");
    } catch (_error) {
      setStatus("浏览器未允许复制，请手动选择文本。", "error");
    }
  }

  async function confirmSuggestion(suggestionId) {
    if (!currentOptimization) return;
    try {
      const payload = await requestJson(
        `/api/resume-optimizations/${currentOptimization.optimization_id}/suggestions/${suggestionId}/confirm`,
        { method: "POST" }
      );
      renderOptimization(payload.optimization);
      setStatus("建议已标记为确认，原简历仍未被修改。");
    } catch (error) {
      setStatus(error.message, "error");
    }
  }

  $("generate-resume-optimization").addEventListener("click", generateOptimization);
  loadLatest();
})();
