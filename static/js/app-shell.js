(() => {
  const root = document.documentElement;
  const themeToggles = document.querySelectorAll('[data-theme-toggle]');
  const storedTheme = localStorage.getItem('interview-theme');
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
  setTheme(storedTheme === 'light' ? 'light' : 'dark');
  themeToggles.forEach((button) => button.addEventListener('click', () => {
    setTheme(root.dataset.theme === 'light' ? 'dark' : 'light');
  }));

  const toggle = document.querySelector('[data-nav-toggle]');
  const closeTargets = document.querySelectorAll('[data-nav-close]');
  const setOpen = (open) => {
    document.body.classList.toggle('nav-open', open);
    toggle?.setAttribute('aria-expanded', String(open));
  };
  toggle?.addEventListener('click', () => setOpen(!document.body.classList.contains('nav-open')));
  closeTargets.forEach((target) => target.addEventListener('click', () => setOpen(false)));
  document.addEventListener('keydown', (event) => event.key === 'Escape' && setOpen(false));
})();
