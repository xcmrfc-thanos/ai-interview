import { cn } from "@/lib/utils";

// 公司/用户首字头像：按名称哈希取色，暗亮主题通用（底色均取 600 系，白字可读）
const PALETTE = ["#0d9488", "#0284c7", "#4f46e5", "#7c3aed", "#db2777", "#d97706", "#059669"];

export function Avatar({ name, className }: { name: string; className?: string }) {
  const text = String(name || "").trim() || "?";
  const char = [...text][0].toUpperCase();
  let hash = 0;
  for (const c of text) hash = (hash * 31 + c.charCodeAt(0)) % 997;
  return (
    <span
      aria-hidden
      className={cn(
        "flex size-9 shrink-0 items-center justify-center rounded-lg text-sm font-semibold text-white select-none",
        className,
      )}
      style={{ backgroundColor: PALETTE[hash % PALETTE.length] }}
    >
      {char}
    </span>
  );
}
