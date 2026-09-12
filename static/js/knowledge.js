(() => {
  "use strict";

  const list = document.getElementById("knowledge-list");
  const status = document.getElementById("knowledge-status");
  const form = document.getElementById("knowledge-form");
  const dialog = document.getElementById("knowledge-dialog");
  const importDialog = document.getElementById("knowledge-import-dialog");
  let items = [];

  const split = (value) => String(value || "").split(/[,，\n]/).map((item) => item.trim()).filter(Boolean);
  const escapeHtml = (value) => String(value ?? "").replace(/[&<>'"]/g, (char) => ({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"}[char]));
  const request = async (url, options) => {
    const response = await fetch(url, options);
    const payload = await response.json();
    if (!response.ok || !payload.success) throw new Error(payload.message || "操作失败");
    return payload;
  };

  function render(payload) {
    items = payload.items;
    const state = payload.knowledge_status;
    status.textContent = state.enhancement_enabled
      ? `已启用 ${state.enabled} 条，共 ${state.total} 条知识。`
      : "知识增强未启用，面试仍可使用简历和岗位上下文继续。";
    if (!items.length) {
      list.innerHTML = '<div class="empty-state"><p>没有匹配的知识条目。</p></div>';
      return;
    }
    list.innerHTML = items.map((item) => `
      <article class="knowledge-item" data-item-id="${item.item_id}">
        <div><h2>${escapeHtml(item.title)}</h2><p>${escapeHtml(item.question)}</p>
          <div class="knowledge-meta"><span>${escapeHtml(item.category || "未分类")}</span><span>${escapeHtml(item.difficulty || "未定级")}</span><span>${escapeHtml(item.source || "未注明来源")}</span><span>${item.is_enabled ? "已启用" : "已停用"}</span></div>
        </div>
        <div class="knowledge-item-actions"><button class="button button-secondary" type="button" data-edit>编辑</button><button class="button button-secondary" type="button" data-toggle>${item.is_enabled ? "停用" : "启用"}</button></div>
      </article>`).join("");
  }

  async function load(query = "") {
    status.textContent = "正在加载…";
    try { render(await request(`/api/knowledge?q=${encodeURIComponent(query)}`)); }
    catch (error) { status.textContent = error.message; status.className = "knowledge-status form-error"; }
  }

  function openEditor(item = null) {
    form.reset();
    form.elements.item_id.value = item?.item_id || "";
    document.getElementById("knowledge-dialog-title").textContent = item ? "编辑知识" : "新增知识";
    for (const name of ["title", "question", "category", "difficulty", "core_conclusion", "standard_answer", "source", "source_url"]) {
      form.elements[name].value = item?.[name] || "";
    }
    form.elements.tech_tags.value = (item?.tech_tags || []).join(", ");
    form.elements.role_tags.value = (item?.role_tags || []).join(", ");
    form.elements.answer_points.value = (item?.answer_points || []).join("\n");
    dialog.showModal();
  }

  document.querySelector("[data-open-knowledge]").addEventListener("click", () => openEditor());
  document.querySelector("[data-open-import]").addEventListener("click", () => importDialog.showModal());
  document.querySelectorAll("[data-close-knowledge]").forEach((button) => button.addEventListener("click", () => dialog.close()));
  document.querySelectorAll("[data-close-import]").forEach((button) => button.addEventListener("click", () => importDialog.close()));
  document.getElementById("knowledge-search-form").addEventListener("submit", (event) => { event.preventDefault(); load(event.currentTarget.elements.q.value); });

  list.addEventListener("click", async (event) => {
    const article = event.target.closest("[data-item-id]");
    if (!article) return;
    const item = items.find((candidate) => candidate.item_id === Number(article.dataset.itemId));
    if (event.target.closest("[data-edit]")) openEditor(item);
    if (event.target.closest("[data-toggle]")) {
      await request(`/api/knowledge/${item.item_id}`, {method: "PATCH", headers: {"Content-Type": "application/json"}, body: JSON.stringify({is_enabled: !item.is_enabled})});
      load(document.getElementById("knowledge-search").value);
    }
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    const itemId = form.elements.item_id.value;
    const data = Object.fromEntries(new FormData(form).entries());
    delete data.item_id;
    data.tech_tags = split(data.tech_tags); data.role_tags = split(data.role_tags); data.answer_points = split(data.answer_points);
    button.disabled = true;
    try {
      await request(itemId ? `/api/knowledge/${itemId}` : "/api/knowledge", {method: itemId ? "PATCH" : "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(data)});
      dialog.close(); await load();
    } catch (error) { document.getElementById("knowledge-form-error").textContent = error.message; }
    finally { button.disabled = false; }
  });

  document.getElementById("knowledge-import-form").addEventListener("submit", async (event) => {
    event.preventDefault(); const button = event.currentTarget.querySelector('button[type="submit"]'); button.disabled = true;
    try { const payload = await request("/api/knowledge/import", {method: "POST", body: new FormData(event.currentTarget)}); document.getElementById("knowledge-import-status").textContent = `新增 ${payload.result.created}，更新 ${payload.result.updated}，失败 ${payload.result.failed}。`; await load(); }
    catch (error) { document.getElementById("knowledge-import-status").textContent = error.message; }
    finally { button.disabled = false; }
  });
  document.querySelector("[data-rebuild-index]").addEventListener("click", async () => { const payload = await request("/api/knowledge/rebuild-index", {method: "POST"}); document.getElementById("knowledge-import-status").textContent = payload.message; });
  load();
})();
