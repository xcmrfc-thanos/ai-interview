import { cn } from "@/lib/utils";

/** 评分环：conic-gradient 进度 + 中心数值，复盘/模拟面试共用 */
export function ScoreRing({
  score,
  label,
  className,
  size = 96,
}: {
  score: number;
  label?: string;
  className?: string;
  size?: number;
}) {
  const clamped = Math.max(0, Math.min(100, Math.round(score)));
  return (
    <div className={cn("flex flex-col items-center gap-2", className)}>
      <div
        className="relative rounded-full"
        style={{
          width: size,
          height: size,
          background: `conic-gradient(var(--primary) ${clamped}%, var(--subtle) 0)`,
        }}
      >
        <div className="absolute inset-1.5 flex items-center justify-center rounded-full bg-card">
          <strong className="text-xl">{clamped}</strong>
        </div>
      </div>
      {label ? <small className="text-xs text-muted-foreground">{label}</small> : null}
    </div>
  );
}
