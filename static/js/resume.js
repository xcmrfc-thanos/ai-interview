// 侧边栏切换
document.getElementById('sidebarCollapse').addEventListener('click', () => {
    document.getElementById('sidebar').classList.toggle('active');
});

// 文件上传处理
const uploadArea = document.getElementById('uploadArea');
const fileInput = document.getElementById('fileInput');

uploadArea.addEventListener('click', () => {
    fileInput.click();
});

// 拖拽上传
uploadArea.addEventListener('dragover', (e) => {
    e.preventDefault();
    uploadArea.classList.add('dragover');
});

uploadArea.addEventListener('dragleave', () => {
    uploadArea.classList.remove('dragover');
});

uploadArea.addEventListener('drop', (e) => {
    e.preventDefault();
    uploadArea.classList.remove('dragover');
    
    const files = e.dataTransfer.files;
    if (files.length) {
        handleFile(files[0]);
    }
});

fileInput.addEventListener('change', (e) => {
    if (e.target.files.length) {
        handleFile(e.target.files[0]);
    }
});

function handleFile(file) {
    // 检查文件类型
    const allowedTypes = ['application/pdf', 'application/msword', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'image/jpeg', 'image/png'];
    if (!allowedTypes.includes(file.type)) {
        alert('请上传PDF、Word或图片格式的文件');
        return;
    }

    // 显示文件预览
    if (file.type.startsWith('image/')) {
        const reader = new FileReader();
        reader.onload = (e) => {
            document.getElementById('resumePreview').src = e.target.result;
        };
        reader.readAsDataURL(file);
    } else {
        // 对于PDF和Word文件，可以使用第三方库进行预览
        // 这里仅作示例
        document.getElementById('resumePreview').src = 'assets/pdf-preview.png';
    }

    // 模拟分析过程
    simulateAnalysis();
}

function simulateAnalysis() {
    // 模拟分析延迟
    const analysisResult = document.querySelector('.analysis-result');
    analysisResult.style.opacity = '0.5';
    
    setTimeout(() => {
        // 更新分析结果
        analysisResult.style.opacity = '1';
        
        // 更新匹配度动画
        const circle = document.querySelector('.circle');
        circle.style.strokeDasharray = '85, 100';
        
        // 更新技能匹配度
        updateSkillMatch();
    }, 2000);
}

function updateSkillMatch() {
    const skills = [
        { name: 'JavaScript', score: 90 },
        { name: 'React', score: 85 },
        { name: 'Node.js', score: 75 }
    ];

    const skillMatches = document.querySelectorAll('.skill-match');
    skillMatches.forEach((match, index) => {
        const progressBar = match.querySelector('.progress-bar');
        progressBar.style.width = `${skills[index].score}%`;
    });
} 