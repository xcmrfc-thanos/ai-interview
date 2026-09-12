(() => {
  "use strict";

  const form = document.getElementById("profile-form");
  const status = document.getElementById("profile-status");

  function fill(user) {
    form.elements.full_name.value = user.full_name || "";
    form.elements.email.value = user.email || "";
    form.elements.phone.value = user.phone || "";
    form.elements.expected_position.value = user.expected_position || "";
    form.elements.expected_salary.value = user.expected_salary || "";
    document.getElementById("profile-name").textContent = user.full_name || "个人用户";
    document.getElementById("profile-position").textContent = user.expected_position || "尚未设置期望职位";
  }

  async function loadProfile() {
    try {
      const response = await fetch("/api/users");
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || "加载失败");
      fill(payload.user);
    } catch (error) {
      status.textContent = error.message;
      status.className = "form-error";
    }
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    status.className = "";
    status.textContent = "正在保存…";
    const data = Object.fromEntries(new FormData(form).entries());
    if (!data.password) delete data.password;
    try {
      const response = await fetch("/api/users/update", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
      });
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || "保存失败");
      form.elements.password.value = "";
      fill(payload.user);
      status.textContent = "设置已保存。";
    } catch (error) {
      status.textContent = error.message;
      status.className = "form-error";
    } finally {
      button.disabled = false;
    }
  });
  loadProfile();
})();
