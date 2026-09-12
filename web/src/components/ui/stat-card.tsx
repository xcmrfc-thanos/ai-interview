import type { ComponentType } from "react";
import { cn } from "@/lib/utils";

/** 统计卡：数值 + 标签 + 图标底座 + 趋势提示 */
export function StatCard({
  icon: Icon,
  label,
  value,
  hint,
  className,
}: {
  icon: ComponentType<{ className?: string }>;
  label: string;
  value: React.ReactNode;
  hint?: string;
  className?: string;
}) {
  return (
    <div className={cn("rounded-xl border border-border bg-card p-4 shadow-xs transition-colors hover:border-border-strong", className)}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm text-muted-foreground">{label}</span>
        <span className="flex size-8 items-center justify-center rounded-lg bg-primary-soft text-primary">
          <Icon className="size-4" aria-hidden />
        </span>
      </div>
      <div className="mt-2 text-[26px] font-semibold tracking-tight tabular-nums">{value}</div>
      {hint ? <div className="mt-0.5 text-xs text-faint">{hint}</div> : null}
    </div>
  );
}
