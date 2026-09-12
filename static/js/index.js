(() => {
  "use strict";
  const root = document.documentElement;
  const themeToggles = document.querySelectorAll('[data-theme-toggle]');
  const setTheme = (theme) => {
    root.dataset.theme = theme;
    localStorage.setItem('interview-theme', theme);
    themeToggles.forEach((button) => {
      const light = theme === 'light';
      button.setAttribute('aria-label', light ? '切换为深色主题' : '切换为浅色主题');
      const icon = button.querySelector('i');
      if (icon) icon.className = light ? 'bx bx-moon' : 'bx bx-sun';
      const label = button.querySelector('[data-theme-label]');
      if (label) label.textContent = light ? '深色' : '浅色';
    });
  };
  setTheme(localStorage.getItem('interview-theme') === 'light' ? 'light' : 'dark');
  themeToggles.forEach((button) => button.addEventListener('click', () => setTheme(root.dataset.theme === 'light' ? 'dark' : 'light')));

  const header = document.querySelector("[data-public-header]");
  const toggle = document.querySelector("[data-public-nav-toggle]");
  const nav = document.querySelector("[data-public-nav]");
  const setMenu = (open) => {
    header?.classList.toggle("nav-open", open);
    toggle?.setAttribute("aria-expanded", String(open));
    toggle?.setAttribute("aria-label", open ? "关闭导航" : "打开导航");
  };
  const updateHeader = () => header?.classList.toggle("is-scrolled", window.scrollY > 12);
  toggle?.addEventListener("click", () => setMenu(!header.classList.contains("nav-open")));
  nav?.addEventListener("click", (event) => event.target.closest("a") && setMenu(false));
  window.addEventListener("scroll", updateHeader, {passive: true});
  updateHeader();

  const introCopy = {
    "30": ["30 秒精简版本", "快速给出职业定位与最相关的一项岗位匹配事实。"],
    "60": ["30 / 60 / 90 秒自我介绍", "默认使用 60 秒版本，既说明职业定位，也用岗位相关的真实项目事实建立可信度。"],
    "90": ["90 秒详细版本", "在标准版本基础上补充项目职责、技术判断和求职动机。"],
  };
  const introTabs = Array.from(document.querySelectorAll("[data-intro-tab]"));
  const selectIntroTab = (button, moveFocus = false) => {
    introTabs.forEach((tab) => {
      const selected = tab === button;
      tab.setAttribute("aria-selected", String(selected));
      tab.setAttribute("tabindex", selected ? "0" : "-1");
    });
    const content = document.querySelector("[data-intro-content]");
    const copy = introCopy[button.dataset.introTab];
    if (!content || !copy) return;
    content.setAttribute("aria-labelledby", button.id);
    content.querySelector("strong").textContent = copy[0];
    content.querySelector("p").textContent = copy[1];
    if (moveFocus) button.focus();
  };
  introTabs.forEach((button, index) => {
    button.addEventListener("click", () => selectIntroTab(button));
    button.addEventListener("keydown", (event) => {
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
      event.preventDefault();
      const nextIndex = event.key === "Home" ? 0 : event.key === "End" ? introTabs.length - 1 : (index + (event.key === "ArrowRight" ? 1 : -1) + introTabs.length) % introTabs.length;
      selectIntroTab(introTabs[nextIndex], true);
    });
  });
})();
