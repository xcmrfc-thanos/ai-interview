import { useEffect, useRef, useState } from "react";

/**
 * 流式文本平滑上屏：目标文本（整块到达的流式结果）只进不改 displayed，
 * displayed 以每帧若干字的速度"追"目标，落后越多追越快（ceil(behind/24)），
 * 视觉上呈一个字一个字的打字机效果；目标变短（转写修正/重置）直接贴齐。
 */
export function useSmoothText(target: string) {
  const [displayed, setDisplayed] = useState(target);
  const lenRef = useRef(target.length);
  const targetRef = useRef(target);
  const rafRef = useRef(0);
  targetRef.current = target;

  useEffect(() => {
    const stop = () => {
      if (rafRef.current) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = 0;
      }
    };
    const tick = () => {
      rafRef.current = 0;
      const t = targetRef.current;
      if (lenRef.current >= t.length) return; // 追平即停，避免空转
      const behind = t.length - lenRef.current;
      lenRef.current += Math.min(behind, Math.max(1, Math.ceil(behind / 24)));
      setDisplayed(t.slice(0, lenRef.current));
      rafRef.current = requestAnimationFrame(tick);
    };

    if (target.length < lenRef.current) {
      // 目标回退（ASR 修正/新一轮重置）：不做动画，直接贴齐
      stop();
      lenRef.current = target.length;
      setDisplayed(target);
      return stop;
    }
    if (target.length > lenRef.current && !rafRef.current) {
      rafRef.current = requestAnimationFrame(tick);
    }
    return stop;
  }, [target]);

  return displayed;
}
