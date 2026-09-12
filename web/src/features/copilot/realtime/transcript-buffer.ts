// 转写 partial 批处理：rAF 合并 UI 更新（≤10Hz），final 立即应用；按 turn/seq 丢弃迟到事件
export type TranscriptPayload<T> = {
  data: T;
  isFinal: boolean;
  side: "candidate" | "interviewer";
};

type StaleCheckable = {
  turn_id?: number | string | null;
  turn?: number | string | null;
  seq?: number | null;
  sequence?: number | null;
};

export function createTranscriptBuffer<T extends StaleCheckable>(options: {
  minPartialIntervalMs?: number;
  onApply: (payload: TranscriptPayload<T>) => void;
}) {
  const minInterval = options.minPartialIntervalMs ?? 100;
  let pending: TranscriptPayload<T> | null = null;
  let rafId = 0;
  let lastFlushAt = 0;
  let lastTurnKey = "";
  let lastSeq = -1;

  function turnKey(data: StaleCheckable) {
    if (data.turn_id != null) return String(data.turn_id);
    if (data.turn != null) return String(data.turn);
    return "";
  }

  function seqOf(data: StaleCheckable) {
    if (data.seq != null) return Number(data.seq);
    if (data.sequence != null) return Number(data.sequence);
    return -1;
  }

  function isStale(data: StaleCheckable) {
    const tk = turnKey(data);
    const seq = seqOf(data);
    if (tk && tk !== lastTurnKey) {
      lastTurnKey = tk;
      lastSeq = -1;
    }
    if (seq >= 0) {
      if (seq < lastSeq) return true;
      lastSeq = seq;
    }
    return false;
  }

  function flush() {
    rafId = 0;
    if (!pending) return;
    const now = Date.now();
    if (now - lastFlushAt < minInterval) {
      scheduleFlush();
      return;
    }
    lastFlushAt = now;
    const payload = pending;
    pending = null;
    options.onApply(payload);
  }

  function scheduleFlush() {
    if (!rafId) {
      rafId = requestAnimationFrame(flush);
    }
  }

  return {
    pushPartial(payload: TranscriptPayload<T>) {
      if (isStale(payload.data)) return;
      pending = payload;
      scheduleFlush();
    },
    pushFinal(payload: TranscriptPayload<T>) {
      if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
      }
      pending = null;
      options.onApply(payload);
    },
    reset() {
      if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
      }
      pending = null;
      lastTurnKey = "";
      lastSeq = -1;
      lastFlushAt = 0;
    },
  };
}
