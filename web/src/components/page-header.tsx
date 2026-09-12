import { useEffect, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  // 内页统一在此设置浏览器标签标题
  useEffect(() => {
    document.title = `${title} · 面试 Copilot`;
  }, [title]);
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-4 py-5 md:px-6">
      <div className="min-w-0">
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {description ? <p className="mt-1 text-sm text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
    </div>
  );
}

const KIND_MAP: Record<string, "neutral" | "primary" | "info" | "success" | "warning" | "danger"> = {
  idle: "neutral",
  connecting: "info",
  ready: "success",
  recording: "primary",
  paused: "warning",
  reconnecting: "warning",
  ending: "warning",
  ended: "neutral",
  error: "danger",
  running: "success",
  draft: "warning",
  needs_review: "warning",
  outdated: "danger",
  confirmed: "success",
  generating: "info",
  archived: "neutral",
  completed: "success",
  failed: "danger",
  none: "neutral",
};

const LABEL_MAP: Record<string, string> = {
  idle: "未开始",
  connecting: "连接中",
  ready: "已就绪",
  recording: "采集中",
  paused: "已暂停",
  reconnecting: "重连中",
  ending: "结束中",
  ended: "已结束",
  error: "错误",
  running: "进行中",
  draft: "草稿",
  needs_review: "待确认",
  outdated: "待更新",
  confirmed: "已确认",
  generating: "生成中",
  archived: "已归档",
  completed: "已完成",
  failed: "生成失败",
  none: "未开始",
};

// 设计系统 .status 徽章：状态 token → kind 映射
export function StatusBadge({ state, label }: { state: string; label?: string }) {
  const kind = KIND_MAP[state] ?? "neutral";
  return <Badge kind={kind}>{label ?? LABEL_MAP[state] ?? state}</Badge>;
}
