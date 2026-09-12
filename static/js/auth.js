(function () {
  "use strict";

  var root = document.documentElement;
  var themeToggles = document.querySelectorAll("[data-theme-toggle]");
  function setTheme(theme) {
    root.dataset.theme = theme;
    localStorage.setItem("interview-theme", theme);
    themeToggles.forEach(function (button) {
      var light = theme === "light";
      button.setAttribute("aria-label", light ? "切换为深色主题" : "切换为浅色主题");
      var icon = button.querySelector("i");
      if (icon) icon.className = light ? "bx bx-moon" : "bx bx-sun";
      var label = button.querySelector("[data-theme-label]");
      if (label) label.textContent = light ? "深色" : "浅色";
    });
  }
  setTheme(localStorage.getItem("interview-theme") === "light" ? "light" : "dark");
  themeToggles.forEach(function (button) {
    button.addEventListener("click", function () {
      setTheme(root.dataset.theme === "light" ? "dark" : "light");
    });
  });

  function setPasswordVisibility(button) {
    var input = document.getElementById(button.dataset.passwordToggle);
    if (!input) return;

    var show = input.type === "password";
    input.type = show ? "text" : "password";
    button.setAttribute("aria-pressed", String(show));
    button.setAttribute("aria-label", show ? "隐藏密码" : "显示密码");
    var icon = button.querySelector("i");
    if (icon) icon.className = show ? "bx bx-hide" : "bx bx-show";
    input.focus();
  }

  function validatePasswordMatch(form) {
    var password = form.querySelector('[name="password"]');
    var confirmation = form.querySelector('[name="confirm_password"]');
    var message = form.querySelector("[data-password-message]");
    if (!password || !confirmation) return true;

    var matches = !confirmation.value || password.value === confirmation.value;
    confirmation.setCustomValidity(matches ? "" : "两次输入的密码不一致");
    if (message) {
      message.textContent = matches ? "" : "两次输入的密码不一致";
      message.dataset.state = matches ? "" : "error";
    }
    return matches;
  }

  function updatePasswordStrength(input) {
    var meter = input.closest(".auth-field").querySelector(".auth-password-meter");
    if (!meter) return;
    var value = input.value;
    var strength = 0;
    if (value.length >= 8) strength++;
    if (/[A-Z]/.test(value) && /[a-z]/.test(value)) strength++;
    if (/\d/.test(value)) strength++;
    if (/[^A-Za-z0-9]/.test(value)) strength++;
    meter.dataset.strength = String(strength);
  }

  document.querySelectorAll("[data-password-toggle]").forEach(function (button) {
    button.addEventListener("click", function () { setPasswordVisibility(button); });
  });

  document.querySelectorAll("[data-password-match]").forEach(function (form) {
    form.addEventListener("input", function () { validatePasswordMatch(form); });
    var password = form.querySelector('[name="password"]');
    if (password) password.addEventListener("input", function () { updatePasswordStrength(password); });
  });

  document.querySelectorAll("[data-auth-form]").forEach(function (form) {
    form.addEventListener("submit", function (event) {
      if (!validatePasswordMatch(form) || !form.checkValidity()) {
        event.preventDefault();
        form.reportValidity();
        return;
      }

      var button = form.querySelector('button[type="submit"]');
      var status = form.querySelector("[data-form-status]");
      if (!button) return;
      button.disabled = true;
      button.setAttribute("aria-busy", "true");
      var label = button.querySelector("span");
      if (label) label.textContent = button.dataset.submitPending || "正在提交";
      if (status) status.textContent = "正在安全提交，请稍候。";
    });
  });
})();
