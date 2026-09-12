// 麦克风控制
function toggleMicrophone() {
    const micBtn = document.getElementById('toggleMic');
    micBtn.classList.toggle('active');
    // 实际的麦克风开关逻辑
}

// 摄像头控制
function toggleCamera() {
    const camBtn = document.getElementById('toggleCamera');
    camBtn.classList.toggle('active');
    // 实际的摄像头开关逻辑
}

// 设置面板控制
function toggleSettings() {
    settingsModal.show();
}

// 全屏控制
function toggleFullscreen() {
    if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen();
    } else {
        document.exitFullscreen();
    }
}

// 结束面试
function endInterview() {
    if (confirm('确定要结束本次面试吗？')) {
        window.location.href = 'interviewCenter.html';
    }
}

// 设置相关的函数
let settingsModal;

document.addEventListener('DOMContentLoaded', function() {
    // 初始化模态框
    settingsModal = new bootstrap.Modal(document.getElementById('settingsModal'));
    
    // 初始化设备列表
    initializeDevices();
});

// 初始化设备列表
async function initializeDevices() {
    try {
        // 请求设备权限
        await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
        
        // 获取设备列表
        const devices = await navigator.mediaDevices.enumerateDevices();
        
        // 填充麦克风选项
        const micSelect = document.getElementById('micSelect');
        devices.filter(device => device.kind === 'audioinput')
            .forEach(device => {
                const option = document.createElement('option');
                option.value = device.deviceId;
                option.text = device.label || `麦克风 ${micSelect.length + 1}`;
                micSelect.appendChild(option);
            });
            
        // 填充摄像头选项
        const cameraSelect = document.getElementById('cameraSelect');
        devices.filter(device => device.kind === 'videoinput')
            .forEach(device => {
                const option = document.createElement('option');
                option.value = device.deviceId;
                option.text = device.label || `摄像头 ${cameraSelect.length + 1}`;
                cameraSelect.appendChild(option);
            });
            
        // 初始化测试视频
        startVideoPreview();
        
    } catch (err) {
        console.error('获取设备失败:', err);
        alert('无法访问设备，请确保已授予相关权限');
    }
}

// 开始摄像头预览
async function startVideoPreview() {
    const testVideo = document.getElementById('testVideo');
    const cameraSelect = document.getElementById('cameraSelect');
    
    try {
        const stream = await navigator.mediaDevices.getUserMedia({
            video: { deviceId: cameraSelect.value }
        });
        testVideo.srcObject = stream;
    } catch (err) {
        console.error('摄像头预览失败:', err);
    }
}

// 保存设置
function saveSettings() {
    // 保存设备选择和麦克风模式
    const micSelect = document.getElementById('micSelect');
    const cameraSelect = document.getElementById('cameraSelect');
    const micMode = document.querySelector('input[name="micMode"]:checked').value;
    
    // 这里可以添加实际的设置保存逻辑
    
    // 关闭模态框
    settingsModal.hide();
} 