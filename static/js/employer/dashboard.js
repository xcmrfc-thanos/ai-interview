document.addEventListener('DOMContentLoaded', function() {
    // 初始化面试数据趋势图表


    // 初始化候选人分布图表
    const candidateCtx = document.getElementById('candidateChart').getContext('2d');
    new Chart(candidateCtx, {
        type: 'doughnut',
        data: {
            labels: ['前端开发', '后端开发', '产品经理', 'UI设计', '测试工程师'],
            datasets: [{
                data: [35, 25, 15, 10, 15],
                backgroundColor: [
                    '#6366f1',
                    '#8b5cf6',
                    '#ec4899',
                    '#f43f5e',
                    '#f59e0b'
                ]
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    position: 'right'
                }
            }
        }
    });
}); 