"""Socket.IO 装配：仅创建实例并挂载 Copilot 处理器。

老版数字人面试的 Socket.IO 处理器（start_interview / user_input /
evaluation_report）已随企业端遗留功能归档（见 docs/archive/legacy-company/），
通道保留为 legacy；实时链路以 utils/copilot_ws.py 的裸 WS 为准。
"""

from flask_socketio import SocketIO

from utils.copilot_socketio import register_copilot_handlers


def initialize_socketio(app):
    socketio = SocketIO(app)
    register_copilot_handlers(socketio)
    return socketio
