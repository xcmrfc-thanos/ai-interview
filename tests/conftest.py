"""测试进程环境基线：与开发者本地 .env 的部署开关解耦。

conftest 先于测试模块导入 app；load_project_env 用 setdefault 加载 .env，
不会覆盖这里显式设置的变量，因此测试期望（SPA 关闭、legacy 路由 302 鉴权）
在任何机器的本地 .env 配置下都保持一致。
"""
import os

# SPA 托管是部署选择（同源托管 web/dist）；开启会让页面 GET 被 index.html 接管，
# 掩盖 legacy 路由的 302 鉴权行为。测试统一按"未启用"基线断言。
os.environ["SPA_ENABLED"] = ""
