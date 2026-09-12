// 侧边栏切换
document.getElementById('sidebarCollapse').addEventListener('click', () => {
    document.getElementById('sidebar').classList.toggle('active');
});

// 头像上传
document.querySelector('.edit-avatar').addEventListener('click', () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = (e) => {
        const file = e.target.files[0];
        if (file) {
            const reader = new FileReader();
            reader.onload = (e) => {
                document.querySelector('.profile-avatar img').src = e.target.result;
            };
            reader.readAsDataURL(file);
        }
    };
    input.click();
});

// 表单提交
document.querySelector('.profile-form').addEventListener('submit', (e) => {
    e.preventDefault();
    // 这里可以添加表单提交逻辑
    alert('个人信息已更新');
});

// 技能标签管理
const skillTags = document.querySelector('.skill-tags');
let isEditing = false;

skillTags.addEventListener('dblclick', () => {
    if (!isEditing) {
        isEditing = true;
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'form-control';
        input.placeholder = '输入技能标签，按回车添加';
        
        input.addEventListener('keypress', (e) => {
            if (e.key === 'Enter' && input.value.trim()) {
                const badge = document.createElement('span');
                badge.className = 'badge bg-primary';
                badge.textContent = input.value.trim();
                skillTags.insertBefore(badge, input);
                input.value = '';
            }
        });

        input.addEventListener('blur', () => {
            if (!input.value.trim()) {
                input.remove();
                isEditing = false;
            }
        });

        skillTags.appendChild(input);
        input.focus();
    }
}); 