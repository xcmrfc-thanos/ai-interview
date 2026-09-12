// 回答流 delta 批处理：文本 ≤20Hz rAF 合并追加，JSON 要点立即解析
export function createAnswerBuffer(options: {
  minDeltaIntervalMs?: number;
  onAppendText: (chunk: string) => void;
  onApplyPoints: (text: string) => void;
}) {
  const minInterval = options.minDeltaIntervalMs ?? 50;
  let pendingText = "";
  let rafId = 0;
  let lastFlushAt = 0;

  function flush() {
    rafId = 0;
    if (!pendingText) return;
    const now = Date.now();
    if (now - lastFlushAt < minInterval) {
      scheduleFlush();
      return;
    }
    lastFlushAt = now;
    const chunk = pendingText;
    pendingText = "";
    options.onAppendText(chunk);
  }

  function scheduleFlush() {
    if (!rafId) {
      rafId = requestAnimationFrame(flush);
    }
  }

  return {
    pushDelta(text: string) {
      if (!text) return;
      if (text.indexOf("[") === 0) {
        options.onApplyPoints(text);
        return;
      }
      pendingText += text;
      scheduleFlush();
    },
    flushNow() {
      if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
      }
      if (pendingText) {
        options.onAppendText(pendingText);
        pendingText = "";
      }
    },
    reset() {
      if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
      }
      pendingText = "";
      lastFlushAt = 0;
    },
  };
}
