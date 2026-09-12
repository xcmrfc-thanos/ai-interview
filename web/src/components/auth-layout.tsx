import { useEffect, type ReactNode } from "react";
import { Link } from "react-router";
import { MessagesSquare, Mic, LineChart, ShieldCheck } from "lucide-react";

const FEATURES = [
  { icon: Mic, text: "实时辅助：真实面试中边听边答，要点即刻呈现" },
  { icon: LineChart, text: "统一复盘：多维评分定位薄弱项与下一步任务" },
  { icon: ShieldCheck, text: "基于简历事实生成，可核查、不虚构" },
];

/** 认证页布局：左品牌区（桌面）+ 右表单区；品牌区继承首页视觉语言 */
export function AuthLayout({ title, description, children, footer }: {
  title: string;
  description: string;
  children: ReactNode;
  footer: ReactNode;
}) {
  // 认证页不经 PageHeader，这里单独设置浏览器标签标题
  useEffect(() => {
    document.title = `${title} · 面试 Copilot`;
  }, [title]);
  return (
    <div className="grid min-h-screen lg:grid-cols-[1.1fr_1fr]">
      {/* 品牌区 */}
      <div className="relative hidden overflow-hidden border-r border-border bg-card lg:flex lg:flex-col lg:justify-between lg:p-12">
        <div
          aria-hidden
          className="pointer-events-none absolute -left-24 -top-32 size-[480px] rounded-full opacity-[0.14] blur-3xl"
          style={{ background: "radial-gradient(circle, var(--primary) 0%, transparent 65%)" }}
        />
        <Link to="/" className="relative flex items-center gap-2.5 font-semibold">
          <span className="flex size-9 items-center justify-center rounded-lg bg-brand-gradient text-white shadow-sm">
            <MessagesSquare className="size-5" aria-hidden />
          </span>
          <span className="text-lg tracking-tight">面试 Copilot</span>
        </Link>
        <div className="relative">
          <h2 className="max-w-md text-3xl font-bold leading-snug tracking-tight">
            面试实战中的
            <span className="text-brand-gradient">可信作战台</span>
          </h2>
          <ul className="mt-8 flex max-w-md flex-col gap-4">
            {FEATURES.map(({ icon: Icon, text }) => (
              <li key={text} className="flex items-center gap-3 text-sm text-muted-foreground">
                <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary-soft text-primary">
                  <Icon className="size-4" aria-hidden />
                </span>
                {text}
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-faint">个人 AI 面试作战台 · ATS Suite</p>
      </div>

      {/* 表单区 */}
      <div className="flex items-center justify-center px-4 py-12">
        <div className="w-full max-w-sm rounded-2xl border border-border bg-card p-8 shadow-md">
          <div className="mb-6 flex items-center gap-2.5 lg:hidden">
            <span className="flex size-8 items-center justify-center rounded-lg bg-brand-gradient text-white">
              <MessagesSquare className="size-4" aria-hidden />
            </span>
            <span className="font-semibold">面试 Copilot</span>
          </div>
          <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
          <p className="mb-6 mt-1.5 text-sm text-muted-foreground">{description}</p>
          {children}
          <div className="mt-6 text-center text-sm text-muted-foreground">{footer}</div>
        </div>
      </div>
    </div>
  );
}
