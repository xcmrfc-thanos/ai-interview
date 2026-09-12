// 侧边栏切换
document.getElementById('sidebarCollapse').addEventListener('click', () => {
    document.getElementById('sidebar').classList.toggle('active');
});

// 面试计时器
let seconds = 0;
let minutes = 15;
let hours = 0;

function updateTimer() {
    if (seconds > 0) {
        seconds--;
    } else {
        if (minutes > 0) {
            minutes--;
            seconds = 59;
        } else {
            if (hours > 0) {
                hours--;
                minutes = 59;
                seconds = 59;
            }
        }
    }

    const timeString = `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
    document.getElementById('timer').textContent = timeString;
}

setInterval(updateTimer, 1000);

// 面试对话处理
const chatMessages = document.getElementById('chatMessages');
const messageInput = document.querySelector('.input-area textarea');
const sendButton = document.querySelector('.input-area button');

function addMessage(content, isUser = false) {
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${isUser ? 'user' : 'ai'}`;
    
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = content;
    
    messageDiv.appendChild(bubble);
    chatMessages.appendChild(messageDiv);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

// 发送消息
sendButton.addEventListener('click', () => {
    const message = messageInput.value.trim();
    if (message) {
        addMessage(message, true);
        messageInput.value = '';
        
        // 模拟AI回复
        setTimeout(() => {
            addMessage('感谢您的回答。下一个问题是...');
        }, 1000);
    }
});

// 结束面试
document.getElementById('endInterview').addEventListener('click', () => {
    if (confirm('确定要结束本次面试吗？')) {
        window.location.href = 'reports.html';
    }
});

// 这里可以添加Three.js相关代码来实现3D数字人模型 