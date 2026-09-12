import { Zap } from "lucide-react";
import { cn } from "@/lib/utils";

/** 要点卡片流：图标底座 + 卡片行，copilot 回答要点与计划详情要点共用视觉语言 */
export function PointList({ points, className }: { points: string[]; className?: string }) {
  return (
    <ul className={cn("flex flex-col gap-1.5", className)}>
      {points.map((p, i) => (
        <li
          key={i}
          className="animate-rise flex items-start gap-2.5 rounded-lg border border-border bg-elevated px-2.5 py-2"
        >
          <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-md bg-primary-soft text-primary">
            <Zap className="size-3.5" aria-hidden />
          </span>
          <span className="text-sm leading-relaxed">{p}</span>
        </li>
      ))}
    </ul>
  );
}
