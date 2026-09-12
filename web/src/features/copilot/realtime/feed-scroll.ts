// 转写 feed 智能滚动：贴底（≤48px）时自动跟随，否则累计未读 final 条数
const DEFAULT_STICK_THRESHOLD_PX = 48;

export type FeedScrollController = {
  scrollToBottom: () => void;
  onFeedScroll: () => void;
  onContentUpdate: (isFinal: boolean) => void;
  reset: () => void;
};

export function createFeedScroll(options: {
  stickThresholdPx?: number;
  getFeed: () => HTMLElement | null;
  onPendingChange: (count: number) => void;
}): FeedScrollController {
  const threshold = options.stickThresholdPx ?? DEFAULT_STICK_THRESHOLD_PX;
  let pinnedToBottom = true;
  let pendingCount = 0;

  function notifyPending() {
    options.onPendingChange(pendingCount);
  }

  function clearPending() {
    if (pendingCount !== 0) {
      pendingCount = 0;
      notifyPending();
    }
  }

  function isNearBottom(el: HTMLElement) {
    return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold;
  }

  function scrollToBottom() {
    requestAnimationFrame(() => {
      const el = options.getFeed();
      if (!el) return;
      el.scrollTop = el.scrollHeight;
      pinnedToBottom = true;
      clearPending();
    });
  }

  function onFeedScroll() {
    const el = options.getFeed();
    if (!el) return;
    pinnedToBottom = isNearBottom(el);
    if (pinnedToBottom) clearPending();
  }

  function onContentUpdate(isFinal: boolean) {
    requestAnimationFrame(() => {
      const el = options.getFeed();
      if (!el) return;
      if (isNearBottom(el)) {
        el.scrollTop = el.scrollHeight;
        pinnedToBottom = true;
        clearPending();
        return;
      }
      pinnedToBottom = false;
      if (isFinal) {
        pendingCount += 1;
        notifyPending();
      }
    });
  }

  function reset() {
    pinnedToBottom = true;
    clearPending();
  }

  return { scrollToBottom, onFeedScroll, onContentUpdate, reset };
}
