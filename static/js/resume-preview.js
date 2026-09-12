(() => {
  const escapeHtml = (value) => {
    const node = document.createElement("span");
    node.textContent = value ?? "";
    return node.innerHTML;
  };

  const renderMarkdown = (markdown) => {
    const lines = String(markdown || "").split("\n");
    let html = "";
    let inList = false;
    const closeList = () => {
      if (inList) { html += "</ul>"; inList = false; }
    };
    for (const raw of lines) {
      const line = raw.trim();
      if (!line) { closeList(); continue; }
      const heading = line.match(/^(#{1,4})\s+(.*)$/);
      if (heading) {
        closeList();
        const level = heading[1].length + 1;
        html += `<h${level}>${escapeHtml(heading[2])}</h${level}>`;
        continue;
      }
      if (line.startsWith("- ")) {
        if (!inList) { html += "<ul>"; inList = true; }
        html += `<li>${escapeHtml(line.slice(2))}</li>`;
        continue;
      }
      if (line.startsWith("|")) {
        closeList();
        const cells = line.replace(/^\||\|$/g, "").split("|").map((cell) => escapeHtml(cell.trim())).join(" · ");
        html += `<p class="preview-table-row">${cells}</p>`;
        continue;
      }
      closeList();
      html += `<p>${escapeHtml(line)}</p>`;
    }
    closeList();
    return html;
  };

  window.renderResumePreview = (container, preview) => {
    const filename = escapeHtml(preview.filename || "我的简历");
    if (preview.pdf_url) {
      container.innerHTML = `<p class="preview-filename">${filename}</p><iframe class="preview-iframe" src="${escapeHtml(preview.pdf_url)}" title="简历 PDF" loading="lazy"></iframe>`;
    } else if (preview.md) {
      container.innerHTML = `<p class="preview-filename">${filename}</p><div class="preview-md">${renderMarkdown(preview.md)}</div>`;
    } else {
      container.innerHTML = `<p class="empty-state">暂无可预览的内容，请到简历中心重新上传。</p>`;
    }
  };
})();