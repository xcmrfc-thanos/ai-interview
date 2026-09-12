import { useEffect, useState } from "react";
import { Link } from "react-router";
import {
  ArrowRight,
  BookOpen,
  Briefcase,
  Check,
  FileText,
  LineChart,
  MessagesSquare,
  Mic,
  Pencil,
  ShieldCheck,
  Sparkles,
  Zap,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { ScoreRing } from "@/components/ui/score-ring";
import { ThemeToggle } from "@/components/theme-toggle";
import { useAuth } from "@/stores/auth";

// 公开首页：打字机 Hero 演示 + 能力六宫格 + 工作流 + 复盘评分环
// 设计移植自 P5 移除的 templates/common/index.html（React + 设计令牌重写）

const DEMO_TURNS = [
  {
    who: "AI 面试官",
    mine: false,
    text: "先做个自我介绍吧——挑一段最贴近这份 JD 的项目经历。",
  },
  {
    who: "候选人",
    mine: true,
    text: "我负责订单服务的接口设计，把接口超时率从 3.2% 降到 0.8%，慢查询定位到缺失索引。",
  },
  {
    who: "AI 追问",
    mine: false,
    text: "追问：定位那次慢查询，你的排查顺序是什么？",
  },
] as const;

/** 演示面板打字机：逐气泡打出 → 停顿 → 清空循环（卸载时取消全部定时器） */
function useDemoTypewriter() {
  const [step, setStep] = useState({ bubble: 0, chars: 0 });
  useEffect(() => {
    let alive = true;
    let timer = 0;
    const sleep = (ms: number) =>
      new Promise<void>((resolve) => {
        timer = window.setTimeout(resolve, ms);
      });
    (async () => {
      while (alive) {
        for (let i = 0; i < DEMO_TURNS.length; i++) {
          setStep({ bubble: i, chars: 0 });
          for (let c = 1; c <= DEMO_TURNS[i].text.length; c++) {
            if (!alive) return;
            setStep({ bubble: i, chars: c });
            await sleep(55);
          }
          if (!alive) return;
          await sleep(i === DEMO_TURNS.length - 1 ? 2600 : 700);
        }
        if (!alive) return;
        setStep({ bubble: 0, chars: 0 });
        await sleep(400);
      }
    })();
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, []);
  return step;
}

function DemoPanel() {
  const step = useDemoTypewriter();
  return (
    <div className="relative rounded-xl border border-border bg-card shadow-lg">
      <div className="flex items-center justify-between border-b border-border px-5 py-3">
        <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Briefcase className="size-3.5 text-primary" aria-hidden />
          后端开发工程师 · 星河科技
        </span>
        <span className="flex items-center gap-1.5 text-xs font-medium text-primary">
          <span className="relative flex size-2" aria-hidden>
            <span className="absolute inline-flex size-full animate-ping rounded-full bg-primary opacity-60" />
            <span className="relative inline-flex size-2 rounded-full bg-primary" />
          </span>
          AI 实时辅助中
        </span>
      </div>
      <div className="flex min-h-[280px] flex-col gap-3 px-5 py-5" aria-hidden>
        {DEMO_TURNS.map((turn, i) => {
          if (i > step.bubble) return null;
          const isTyping = i === step.bubble;
          const text = isTyping ? turn.text.slice(0, step.chars) : turn.text;
          return (
            <div
              key={turn.who}
              className={`max-w-[92%] rounded-lg px-4 py-2.5 text-sm leading-relaxed ${
                turn.mine
                  ? "self-end bg-primary/15 text-foreground"
                  : "self-start bg-subtle text-foreground"
              }`}
            >
              <b className="mb-0.5 block text-xs text-primary">{turn.who}</b>
              {text}
              {isTyping ? (
                <span className="ml-0.5 inline-block h-4 w-[2px] translate-y-0.5 animate-[landing-blink_0.9s_steps(1)_infinite] bg-primary" />
              ) : null}
            </div>
          );
        })}
      </div>
      <div className="flex items-center justify-between border-t border-border px-5 py-3.5">
        <span className="flex items-end gap-1" aria-hidden>
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <span
              key={i}
              className="h-4 w-1 origin-bottom rounded-full bg-primary/70 animate-[landing-wave_1.1s_ease-in-out_infinite]"
              style={{ animationDelay: `${i * 0.12}s` }}
            />
          ))}
        </span>
        <span className="flex items-center gap-1 rounded-full bg-primary-soft px-2.5 py-1 text-xs font-medium text-primary">
          <Zap className="size-3" aria-hidden />
          流式输出
        </span>
      </div>
    </div>
  );
}

const CAPABILITIES = [
  { icon: FileText, t: "简历解析", d: "上传 PDF / DOCX，自动提取结构化事实，作为所有生成内容的可信边界。" },
  { icon: BookOpen, t: "面试准备包", d: "基于 JD 与简历事实生成多版本自我介绍、岗位亮点、风险点与高频问题。" },
  { icon: Mic, t: "实时辅助", d: "浏览器采集音频，增量转写，问题结束后快速返回回答要点与参考回答。" },
  { icon: MessagesSquare, t: "模拟面试", d: "按岗位生成多轮问题，逐题返回事实一致性、岗位相关性与表达评分。" },
  { icon: LineChart, t: "统一复盘", d: "汇总实时辅助与模拟面试的问题、评分、风险点与下一步任务。" },
  { icon: Pencil, t: "简历优化", d: "对照 JD 生成候选改写文本，默认不覆盖原简历，所有建议需人工确认。" },
];

const WORKFLOW = [
  { no: "01", t: "岗位与简历", d: "创建面试计划，关联 JD 与本人简历事实。" },
  { no: "02", t: "准备与练习", d: "生成准备包，进入实时辅助或模拟面试。" },
  { no: "03", t: "复盘与优化", d: "沉淀问题、评分和下一步简历优化任务。" },
];

function SectionHead({ kicker, title, copy }: { kicker: string; title: string; copy?: string }) {
  return (
    <div className="mb-10 max-w-2xl">
      <p className="text-xs font-semibold uppercase tracking-[0.2em] text-primary">{kicker}</p>
      <h2 className="mt-2 text-2xl font-semibold tracking-tight md:text-3xl">{title}</h2>
      {copy ? <p className="mt-3 leading-relaxed text-muted-foreground">{copy}</p> : null}
    </div>
  );
}

export function HomePage() {
  const { role, checked, checkRole } = useAuth();
  useEffect(() => {
    if (!checked) void checkRole();
  }, [checked, checkRole]);
  const loggedIn = checked && role === "applicant";

  return (
    <div className="flex min-h-screen flex-col">
      {/* 顶部导航 */}
      <header className="sticky top-0 z-20 border-b border-border bg-background/80 backdrop-blur">
        <div className="mx-auto flex h-14 w-full max-w-6xl items-center justify-between px-4 md:px-8">
          <Link
            to="/"
            className="flex items-center gap-2 font-semibold"
            onClick={(e) => {
              e.preventDefault();
              window.scrollTo({ top: 0, behavior: "smooth" });
            }}
          >
            <span className="flex size-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <MessagesSquare className="size-4" aria-hidden />
            </span>
            面试 Copilot
          </Link>
          <nav className="hidden items-center gap-6 text-sm text-muted-foreground md:flex" aria-label="页面导航">
            <a href="#home" className="transition-colors hover:text-foreground">首页</a>
            <a href="#capabilities" className="transition-colors hover:text-foreground">能力</a>
            <a href="#workflow" className="transition-colors hover:text-foreground">工作流</a>
            <a href="#review" className="transition-colors hover:text-foreground">复盘</a>
          </nav>
          <div className="flex items-center gap-2">
            <ThemeToggle />
            {loggedIn ? (
              <Button asChild size="sm">
                <Link to="/applicant/workspace">进入工作台</Link>
              </Button>
            ) : (
              <>
                <Button asChild variant="ghost" size="sm">
                  <Link to="/login">登录</Link>
                </Button>
                <Button asChild size="sm">
                  <Link to="/register">免费开始</Link>
                </Button>
              </>
            )}
          </div>
        </div>
      </header>

      <main className="flex-1">
        {/* Hero */}
        <section id="home" className="relative scroll-mt-14 overflow-hidden">
          <div
            aria-hidden
            className="pointer-events-none absolute -top-32 left-1/2 size-[640px] -translate-x-1/2 rounded-full opacity-[0.13] blur-3xl"
            style={{ background: "radial-gradient(circle, var(--primary) 0%, transparent 65%)" }}
          />
          <div className="mx-auto grid w-full max-w-7xl items-center gap-12 px-4 py-12 md:px-8 lg:min-h-[calc(80vh-3.5rem)] lg:grid-cols-[1fr_1.1fr] lg:py-8">
            <div>
              <p className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1 text-xs font-medium text-muted-foreground">
                <Sparkles className="size-3.5 text-primary" aria-hidden />
                PERSONAL INTERVIEW WORKSPACE · ATS SUITE
              </p>
              <h1 className="mt-5 text-4xl font-bold leading-tight tracking-tight md:text-5xl">
                面试实战中的
                <span className="bg-gradient-to-r from-primary to-accent bg-clip-text text-transparent">
                  可信作战台
                </span>
              </h1>
              <p className="mt-5 max-w-xl leading-relaxed text-muted-foreground">
                求职者围绕一份真实 JD 走完{" "}
                <strong className="font-semibold text-foreground">准备 → 实时辅助 → 模拟面试 → 复盘</strong>{" "}
                的完整闭环；所有生成内容基于简历事实，不虚构项目与经历。
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <Button asChild size="lg">
                  <Link to="/applicant/copilot">
                    <Mic aria-hidden />
                    开始面试
                  </Link>
                </Button>
                <Button asChild size="lg" variant="outline">
                  <Link to="/login">前往登录</Link>
                </Button>
              </div>
              <p className="mt-5 flex items-center gap-1.5 text-sm text-faint">
                <ShieldCheck className="size-4 text-primary" aria-hidden />
                所有生成内容基于简历事实，可核查、可追溯
              </p>
            </div>
            <DemoPanel />
          </div>
        </section>

        {/* 能力六宫格 */}
        <section id="capabilities" className="scroll-mt-14 border-t border-border bg-subtle/40">
          <div className="mx-auto w-full max-w-7xl px-4 py-20 md:px-8">
            <SectionHead kicker="CAPABILITIES" title="从简历到 Offer 的六项能力" />
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {CAPABILITIES.map(({ icon: Icon, t, d }) => (
                <div
                  key={t}
                  className="group rounded-xl border border-border bg-card p-5 transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md"
                >
                  <span className="flex size-10 items-center justify-center rounded-lg bg-primary-soft text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
                    <Icon className="size-5" aria-hidden />
                  </span>
                  <h3 className="mt-4 font-semibold">{t}</h3>
                  <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{d}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* 工作流 */}
        <section id="workflow" className="scroll-mt-14">
          <div className="mx-auto w-full max-w-7xl px-4 py-20 md:px-8">
            <SectionHead
              kicker="ONE CONTEXT, FULL LOOP"
              title="从岗位目标开始，而不是从空白对话开始"
            />
            <ol className="grid gap-4 md:grid-cols-3">
              {WORKFLOW.map(({ no, t, d }) => (
                <li key={no} className="rounded-xl border border-border bg-card p-5">
                  <span className="font-mono text-sm font-semibold text-primary">{no}</span>
                  <h3 className="mt-2 font-semibold">{t}</h3>
                  <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{d}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>

        {/* 复盘评分环 */}
        <section id="review" className="scroll-mt-14 border-t border-border bg-subtle/40">
          <div className="mx-auto grid w-full max-w-7xl items-center gap-10 px-4 py-20 md:px-8 lg:grid-cols-2">
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.2em] text-primary">REVIEW</p>
              <h2 className="mt-2 text-2xl font-semibold tracking-tight md:text-3xl">
                每一次练习，都留下可执行的复盘
              </h2>
              <p className="mt-4 leading-relaxed text-muted-foreground">
                按岗位相关性、表达结构、事实一致性多维评分；风险点与薄弱项自动沉淀为下一步任务，练习闭环而不是练完即散。
              </p>
              <ul className="mt-6 space-y-2.5 text-sm">
                {["多维评分与逐题反馈", "风险点与薄弱项自动归档", "复盘任务一键转简历优化"].map((item) => (
                  <li key={item} className="flex items-center gap-2">
                    <Check className="size-4 shrink-0 text-primary" aria-hidden />
                    {item}
                  </li>
                ))}
              </ul>
            </div>
            <div className="flex justify-center gap-10 rounded-xl border border-border bg-card p-10">
              <ScoreRing score={82} label="岗位相关性" />
              <ScoreRing score={76} label="表达结构" />
              <ScoreRing score={88} label="事实一致性" />
            </div>
          </div>
        </section>

        {/* 底部 CTA */}
        <section className="border-t border-border">
          <div className="mx-auto flex w-full max-w-7xl flex-col items-center gap-5 px-4 py-16 text-center md:px-8">
            <h2 className="text-2xl font-semibold tracking-tight md:text-3xl">
              下一场面试，带着 Copilot 上场
            </h2>
            <p className="max-w-xl text-muted-foreground">
              创建一个面试计划，五分钟生成第一份准备包。
            </p>
            <Button asChild size="lg">
              <Link to={loggedIn ? "/applicant/interview-plans?create=1" : "/register"}>
                免费开始
                <ArrowRight aria-hidden />
              </Link>
            </Button>
          </div>
        </section>
      </main>

      <footer className="border-t border-border px-4 py-6 text-center text-xs text-faint">
        面试 Copilot · 基于简历事实的 AI 面试辅助
      </footer>
    </div>
  );
}
