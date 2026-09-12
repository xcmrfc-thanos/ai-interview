import { cn } from "@/lib/utils";

/** 细条进度：准备包生成、完成度、评分占比 */
export function Progress({
  value,
  className,
  gradient = true,
  ariaLabel,
}: {
  value: number;
  className?: string;
  /** true=品牌渐变条（完成度），false=实色 primary */
  gradient?: boolean;
  ariaLabel?: string;
}) {
  const clamped = Math.max(0, Math.min(100, Math.round(value)));
  return (
    <div
      role="progressbar"
      aria-label={ariaLabel}
      aria-valuenow={clamped}
      aria-valuemin={0}
      aria-valuemax={100}
      className={cn("h-1.5 w-full overflow-hidden rounded-full bg-subtle", className)}
    >
      <div
        className={cn("h-full rounded-full transition-[width] duration-500", gradient ? "bg-brand-gradient" : "bg-primary")}
        style={{ width: `${clamped}%` }}
      />
    </div>
  );
}
