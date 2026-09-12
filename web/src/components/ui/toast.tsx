import { useEffect, useState } from "react";
import { CheckCircle2, Info, X, XCircle } from "lucide-react";
import { cn } from "@/lib/utils";

// 轻量 Toast：模块级 store + <Toaster/> 挂载于 App 根部；toast.success/error/info 全站调用

export type ToastKind = "success" | "error" | "info";
type ToastItem = { id: number; kind: ToastKind; message: string };

const listeners = new Set<(items: ToastItem[]) => void>();
let items: ToastItem[] = [];
let seq = 0;

function emit() {
  listeners.forEach((listener) => listener([...items]));
}

function dismiss(id: number) {
  items = items.filter((t) => t.id !== id);
  emit();
}

function push(kind: ToastKind, message: string, duration: number) {
  const id = ++seq;
  items = [...items, { id, kind, message }];
  emit();
  window.setTimeout(() => dismiss(id), duration);
}

export const toast = {
  success: (message: string) => push("success", message, 3200),
  error: (message: string) => push("error", message, 4200),
  info: (message: string) => push("info", message, 3200),
};

const ICONS: Record<ToastKind, typeof Info> = {
  success: CheckCircle2,
  error: XCircle,
  info: Info,
};

const TONES: Record<ToastKind, string> = {
  success: "text-success",
  error: "text-destructive",
  info: "text-accent",
};

export function Toaster() {
  const [list, setList] = useState<ToastItem[]>([]);
  useEffect(() => {
    listeners.add(setList);
    return () => {
      listeners.delete(setList);
    };
  }, []);
  if (list.length === 0) return null;
  return (
    <div
      className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-full max-w-sm flex-col gap-2"
      role="status"
      aria-live="polite"
    >
      {list.map((item) => {
        const Icon = ICONS[item.kind];
        return (
          <div
            key={item.id}
            className={cn(
              "pointer-events-auto flex items-start gap-2.5 rounded-xl border border-border bg-elevated p-3 shadow-lg animate-[toast-in_180ms_ease-out]",
            )}
          >
            <Icon className={cn("mt-0.5 size-4 shrink-0", TONES[item.kind])} aria-hidden />
            <p className="min-w-0 flex-1 text-sm leading-relaxed">{item.message}</p>
            <button
              type="button"
              className="cursor-pointer text-faint transition-colors hover:text-foreground"
              aria-label="关闭提示"
              onClick={() => dismiss(item.id)}
            >
              <X className="size-3.5" />
            </button>
          </div>
        );
      })}
    </div>
  );
}
