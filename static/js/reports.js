// 侧边栏切换
document.getElementById('sidebarCollapse').addEventListener('click', () => {
    document.getElementById('sidebar').classList.toggle('active');
});

// 能力雷达图
const ctx = document.getElementById('abilityRadar').getContext('2d');
new Chart(ctx, {
    type: 'radar',
    data: {
        labels: [
            '技术能力',
            '问题解决',
            '沟通表达',
            '学习能力',
            '团队协作',
            '项目经验'
        ],
        datasets: [{
            label: '能力评估',
            data: [85, 90, 80, 85, 75, 70],
            fill: true,
            backgroundColor: 'rgba(108, 99, 255, 0.2)',
            borderColor: 'rgb(108, 99, 255)',
            pointBackgroundColor: 'rgb(108, 99, 255)',
            pointBorderColor: '#fff',
            pointHoverBackgroundColor: '#fff',
            pointHoverBorderColor: 'rgb(108, 99, 255)'
        }]
    },
    options: {
        elements: {
            line: {
                borderWidth: 3
            }
        },
        scales: {
            r: {
                angleLines: {
                    display: true
                },
                suggestedMin: 0,
                suggestedMax: 100
            }
        }
    }
});

// 导出PDF功能
document.querySelector('.btn-primary').addEventListener('click', () => {
    alert('正在生成PDF报告...');
    // 这里可以添加实际的PDF导出逻辑
});

// 分享功能
document.querySelector('.btn-outline-primary').addEventListener('click', () => {
    // 这里可以添加分享功能的实现
    const shareData = {
        title: 'AI面试报告',
        text: '查看我的面试报告',
        url: window.location.href
    };

    if (navigator.share) {
        navigator.share(shareData)
            .catch((error) => console.log('分享失败', error));
    } else {
        alert('复制链接成功，可以分享给他人了');
    }
}); 