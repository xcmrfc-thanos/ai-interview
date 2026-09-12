(() => {
  const root = document.querySelector('[data-view-root]');
  if (!root) return;
  const key = `view-mode:${root.dataset.viewKey || 'default'}`;
  const toggles = document.querySelectorAll('[data-view-toggle]');
  const apply = (view) => {
    root.classList.toggle('is-list', view === 'list');
    toggles.forEach((toggle) => toggle.classList.toggle('is-active', toggle.dataset.viewToggle === view));
    localStorage.setItem(key, view);
  };
  const stored = localStorage.getItem(key);
  if (stored === 'list' || stored === 'card') apply(stored);
  toggles.forEach((toggle) => toggle.addEventListener('click', () => apply(toggle.dataset.viewToggle)));
})();