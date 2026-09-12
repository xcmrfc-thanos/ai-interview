(() => {
  const list = document.getElementById('plan-list');
  const dialog = document.getElementById('plan-dialog');
  const form = document.getElementById('plan-form');
  const error = document.getElementById('plan-error');
  const resumePicker = document.querySelector('[data-resume-picker]');
  const resumeInput = document.getElementById('plan-resume');
  const resumeEmpty = document.querySelector('[data-resume-empty]');
  const escape = (value) => { const node = document.createElement('span'); node.textContent = value ?? ''; return node.innerHTML; };
  const statusLabels = { confirmed: '已确认', draft: '草稿', needs_review: '待确认', pending: '待处理', failed: '失败', generating: '生成中' };
  const loadPlans = async () => {
    const response = await fetch('/api/interview-plans');
    const data = await response.json();
    list.innerHTML = data.plans.length ? data.plans.map((plan) => `<article class="surface plan-card"><div class="plan-card-head"><div><h2>${escape(plan.position_name)}</h2><p class="plan-company">${escape(plan.company_name)}</p></div><span class="status" data-state="${escape(plan.preparation_status)}">${escape(statusLabels[plan.preparation_status] || '准备中')}</span></div><p class="plan-resume"><i class="bx ${plan.resume_id ? 'bx-file-blank' : 'bx-link-external'}" aria-hidden="true"></i>${plan.resume_id ? `已关联：${escape(plan.resume_filename || `简历 #${plan.resume_id}`)}` : '尚未关联简历'}</p><div class="plan-meta"><span><i class="bx bx-calendar" aria-hidden="true"></i>${plan.updated_at ? new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(plan.updated_at)) : '最近更新'}</span><span><i class="bx bx-purchase-tag" aria-hidden="true"></i>${plan.tech_tags.length} 个技术标签</span></div><div class="tag-list">${plan.tech_tags.map((tag) => `<span class="tag">${escape(tag)}</span>`).join('')}</div><div class="plan-actions"><a class="button button-secondary" href="/applicant/interview-plans/${plan.plan_id}">查看计划 <i class="bx bx-arrow-up-right"></i></a></div></article>`).join('') : '<div class="surface empty-state"><i class="bx bx-calendar-plus" aria-hidden="true"></i><p>还没有面试计划。</p><a class="button" href="?create=1">创建第一个计划</a></div>';
  };
  const loadResumes = async () => {
    const response = await fetch('/api/get_resumes');
    const data = await response.json();
    const resumes = data.resumes || [];
    if (!resumes.length) {
      resumeEmpty.hidden = false;
      return;
    }
    resumes.forEach((resume) => {
      const option = document.createElement('button');
      option.type = 'button';
      option.className = 'resume-option';
      option.dataset.resumeOption = '';
      option.dataset.resumeId = String(resume.id);
      option.setAttribute('role', 'radio');
      option.setAttribute('aria-checked', 'false');
      option.innerHTML = `<span class="resume-option-icon"><i class="bx bx-file" aria-hidden="true"></i></span><span><strong>${escape(resume.filename)}</strong><small>${resume.upload_date ? new Intl.DateTimeFormat('zh-CN', {dateStyle: 'medium'}).format(new Date(resume.upload_date)) : '已上传'} · 可用于生成准备包</small></span><i class="bx bx-check-circle resume-option-check" aria-hidden="true"></i>`;
      resumePicker.appendChild(option);
    });
    resumePicker.querySelectorAll('[data-resume-option]').forEach((option) => option.addEventListener('click', () => {
      resumePicker.querySelectorAll('[data-resume-option]').forEach((item) => {
        const selected = item === option;
        item.classList.toggle('is-selected', selected);
        item.setAttribute('aria-checked', String(selected));
      });
      resumeInput.value = option.dataset.resumeId;
    }));
  };
  document.querySelectorAll('[data-open-plan]').forEach((button) => button.addEventListener('click', () => dialog.showModal()));
  document.querySelectorAll('[data-close-plan]').forEach((button) => button.addEventListener('click', () => dialog.close()));
  form.addEventListener('submit', async (event) => {
    event.preventDefault(); error.textContent = '';
    const submitButton = form.querySelector('button[type="submit"]');
    submitButton.disabled = true;
    submitButton.dataset.label = submitButton.textContent;
    submitButton.textContent = '保存中…';
    try {
      const data = Object.fromEntries(new FormData(form));
      if (data.resume_id) data.resume_id = Number(data.resume_id); else delete data.resume_id;
      data.tech_tags = data.tech_tags.split(',').map((tag) => tag.trim()).filter(Boolean);
      const response = await fetch('/api/interview-plans', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
      const result = await response.json();
      if (!response.ok) throw new Error(result.message || '创建失败');
      window.location.href = `/applicant/interview-plans/${result.plan.plan_id}`;
    } catch (requestError) {
      error.textContent = requestError.message || '创建失败，请稍后重试';
      submitButton.disabled = false;
      submitButton.textContent = submitButton.dataset.label || '保存并准备';
    }
  });
  Promise.all([loadPlans(), loadResumes()]).then(() => new URLSearchParams(location.search).get('create') === '1' && dialog.showModal()).catch(() => { list.innerHTML = '<div class="surface empty-state"><p>加载失败，请刷新重试。</p></div>'; });
})();
