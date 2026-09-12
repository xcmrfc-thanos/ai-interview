// Copilot 连接状态常量与 .status kind 映射（对齐 Java copilot-conn-state.js）
export const CONN_LABELS = {
  idle: "未连接",
  connecting: "连接中",
  connected: "已连接",
  paused: "已暂停",
  ending: "结束中",
  ended: "已断开",
  error: "连接失败",
} as const;

export type ConnStateKey = keyof typeof CONN_LABELS;

const KIND_MAP: Record<ConnStateKey, "active" | "error" | "connecting" | "warning" | "idle"> = {
  connected: "active",
  ended: "error",
  connecting: "connecting",
  ending: "connecting",
  paused: "warning",
  idle: "idle",
  error: "error",
};

export function kindFor(key: ConnStateKey) {
  return KIND_MAP[key] ?? "idle";
}
