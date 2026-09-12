(() => {
  "use strict";

  const list = document.getElementById("review-list");
  const detail = document.querySelector("[data-review-id]");

  async function loadReviews() {
    if (!list) return;
    const params = new URLSearchParams();
    const values = {
      plan_id: document.getElementById("review-plan-filter").value,
      source_type: document.getElementById("review-type-filter").value,
      date_from: document.getElementById("review-date-from").value,
      date_to: document.getElementById("review-date-to").value,
    };
    Object.entries(values).forEach(([key, value]) => value && params.set(key, value));
    const status = document.getElementById("review-list-status");
    status.textContent = "正在加载复盘记录…";
    try {
      const response = await fetch(`/api/reviews?${params.toString()}`);
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.message || "加载失败");
      renderReviews(payload.reviews || []);
      status.textContent = payload.reviews.length ? `共 ${payload.reviews.length} 条记录` : "";
    } catch (error) {
      status.textContent = error.message;
      list.replaceChildren();
    }
  }

  function renderReviews(reviews) {
    list.replaceChildren();
    if (!reviews.length) {
      const empty = document.createElement("p");
      empty.className = "empty-state";
      empty.textContent = "暂无匹配的复盘记录。完成实时辅助或模拟面试后可生成复盘。";
      list.appendChild(empty);
      return;
    }
    reviews.forEach((review) => list.appendChild(reviewRow(review)));
  }

  function reviewRow(review) {
    const row = document.createElement("a");
    row.className = "review-row";
    row.href = `/applicant/reviews/${review.review_id}`;
    const plan = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = `${review.plan?.company_name || "未命名公司"} · ${review.plan?.position_name || "未命名岗位"}`;
    const source = document.createElement("small");
    source.textContent = `复盘 #${review.review_id}`;
    plan.append(title, source);
    const type = document.createElement("span");
    type.className = "review-type";
    type.textContent = review.source_type === "copilot" ? "实时辅助" : "模拟面试";
    const time = document.createElement("time");
    time.textContent = formatDate(review.created_at);
    const summary = document.createElement("p");
    summary.textContent = review.next_actions?.[0] || review.summary || "查看复盘详情";
    const arrow = document.createElement("span");
    arrow.className = "icon-button";
    arrow.setAttribute("aria-hidden", "true");
    arrow.innerHTML = '<i class="bx bx-chevron-right"></i>';
    row.append(plan, type, time, summary, arrow);
    return row;
  }

  function formatDate(value) {
    if (!value) return "";
    return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
  }

  async function exportReview(format) {
    if (!detail) return;
    const reviewId = detail.dataset.reviewId;
    const status = document.getElementById("review-export-status");
    status.textContent = "正在生成导出文件…";
    try {
      const response = await fetch(`/api/reviews/${reviewId}/export?format=${format}`);
      if (!response.ok) throw new Error("导出失败");
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `interview-review-${reviewId}.${format === "json" ? "json" : "md"}`;
      link.click();
      URL.revokeObjectURL(url);
      status.textContent = "导出完成。";
    } catch (error) {
      status.textContent = error.message;
    }
  }

  document.getElementById("review-filter-button")?.addEventListener("click", loadReviews);
  document.getElementById("export-review-text")?.addEventListener("click", () => exportReview("text"));
  document.getElementById("export-review-json")?.addEventListener("click", () => exportReview("json"));
  loadReviews();
})();
