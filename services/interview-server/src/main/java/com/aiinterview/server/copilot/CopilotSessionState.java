package com.aiinterview.server.copilot;

/** Copilot 会话运行时状态。 */
public enum CopilotSessionState {

    /** 已创建连接但未开始采集。 */
    IDLE,
    /** 正在采集与转写。 */
    RUNNING,
    /** 用户暂停采集。 */
    PAUSED,
    /** WebSocket 断开，等待短窗口内重连。 */
    RECONNECTING,
    /** 用户结束会话，等待在途回答落库。 */
    ENDING,
    /** 会话已结束。 */
    ENDED,
    /** 不可恢复的错误态。 */
    ERROR
}
