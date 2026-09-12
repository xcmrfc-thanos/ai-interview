import { useEffect, useState } from "react";
import { Link } from "react-router";
import {
  Mic,
  MessagesSquare,
  FileSearch,
  FolderKanban,
  ClipboardCheck,
  Plus,
  ArrowRight,
  Sparkles,
} from "lucide-react";
import { PageHeader, StatusBadge } from "@/components/page-header";
import { Avatar } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { CardSkeleton, Skeleton } from "@/components/ui/skeleton";
import { StatCard } from "@/components/ui/stat-card";
import { api } from "@/lib/api";
import { techTagsOf, type Plan } from "@/lib/plan";

// 工作台：欢迎条 + 数据概览 + 行动入口 + 进行中计划（准备 → 实战 → 复盘 → 优化 闭环起点）

const ENTRY_CARDS = [
  { to: "/applicant/interview-plans", icon: FolderKanban, title: "面试计划", desc: "维护岗位与简历" },
  { to: "/applicant/copilot", icon: Mic, title: "实时 Copilot", desc: "边听边给要点", planKey: "copilot" },
  { to: "/applicant/mock-interview", icon: MessagesSquare, title: "模拟面试", desc: "多轮问答评分", planKey: "mock" },
  { to: "/applicant/resume-optimize", icon: FileSearch, title: "简历优化", desc: "对照 JD 改写", planKey: "optimize" },
] as const;

function entryTo(card: (typeof ENTRY_CARDS)[number], latest: Plan | undefined): string {
  if (!latest || !("planKey" in card)) return card.to;
  return `${card.to}?plan_id=${latest.plan_id}`;
}

function greeting(): string {
  const h = new Date().getHours();
  if (h < 6) return "夜深了";
  if (h < 12) return "早上好";
  if (h < 14) return "中午好";
  if (h < 18) return "下午好";
  return "晚上好";
}

export function WorkspacePage() {
  const [plans, setPlans] = useState<Plan[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api
      .get<{ plans: Plan[] }>("/api/interview-plans")
      .then((data) => setPlans(data.plans.filter((p) => p.status !== "archived")))
      .catch(() => setPlans([]))
      .finally(() => setLoading(false));
  }, []);

  const latest = plans[0];
  const countBy = (...states: string[]) =>
    plans.filter((p) => states.includes(p.preparation_status ?? "none")).length;

  return (
    <>
      <PageHeader title="工作台" description="围绕同一份岗位、JD 与简历完成准备、实战与复盘。" />
      <div className="flex flex-col gap-4 px-4 py-5 md:px-6">
        {/* 欢迎条：品牌 moment */}
        <div className="relative overflow-hidden rounded-xl border border-border bg-card p-5">
          <div
            aria-hidden
            className="pointer-events-none absolute -right-16 -top-24 size-64 rounded-full opacity-[0.12] blur-3xl"
            style={{ background: "radial-gradient(circle, var(--primary) 0%, transparent 65%)" }}
          />
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <p className="flex items-center gap-1.5 text-xs font-medium text-primary">
                <Sparkles className="size-3.5" aria-hidden />
                {greeting()}，准备上场
              </p>
              <p className="mt-1.5 text-lg font-semibold tracking-tight">
                {latest ? (
                  <>
                    下一站：
                    <span className="text-brand-gradient">
                      {latest.company_name} · {latest.position_name}
                    </span>
                  </>
                ) : (
                  "创建第一个面试计划，开始闭环练习"
                )}
              </p>
            </div>
            <Button asChild variant="gradient">
              <Link to={latest ? `/applicant/copilot?plan_id=${latest.plan_id}` : "/applicant/interview-plans"}>
                {latest ? "进入实时辅助" : "去创建计划"}
                <ArrowRight />
              </Link>
            </Button>
          </div>
        </div>

        {/* 数据概览 */}
        <div className="stagger grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {loading ? (
            [0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-24" />)
          ) : (
            <>
              <StatCard icon={FolderKanban} label="全部计划" value={plans.length} hint="不含已归档" />
              <StatCard icon={Sparkles} label="准备中" value={countBy("draft", "generating")} hint="准备包生成/草稿" />
              <StatCard icon={Mic} label="可实战" value={countBy("confirmed", "ready")} hint="已确认即可开练" />
              <StatCard
                icon={ClipboardCheck}
                label="待确认"
                value={countBy("needs_review", "outdated")}
                hint="建议先过目再实战"
              />
            </>
          )}
        </div>

        {/* 行动入口 */}
        <div className="stagger grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {ENTRY_CARDS.map((card) => {
            const Icon = card.icon;
            return (
              <Link
                key={card.title}
                to={entryTo(card, latest)}
                className="group rounded-xl border border-border bg-card p-4 transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md"
              >
                <span className="flex size-10 items-center justify-center rounded-lg bg-primary-soft text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
                  <Icon className="size-5" aria-hidden />
                </span>
                <div className="mt-3 font-medium">{card.title}</div>
                <div className="mt-0.5 text-sm text-muted-foreground">{card.desc}</div>
              </Link>
            );
          })}
        </div>

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle>进行中的计划</CardTitle>
              <Button asChild size="sm" variant="ghost">
                <Link to="/applicant/interview-plans">
                  <Plus />
                  新建
                </Link>
              </Button>
            </div>
          </CardHeader>
          <CardContent>
            {loading ? (
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {[0, 1, 2].map((i) => (
                  <CardSkeleton key={i} />
                ))}
              </div>
            ) : plans.length === 0 ? (
              <EmptyState
                icon={FolderKanban}
                title="还没有面试计划"
                description="创建计划并关联 JD 与简历，生成准备包后即可开始实战与复盘。"
                action={
                  <Button asChild>
                    <Link to="/applicant/interview-plans">
                      <Plus />
                      创建一个计划
                    </Link>
                  </Button>
                }
              />
            ) : (
              <div className="flex flex-col divide-y divide-border">
                {plans.slice(0, 5).map((plan) => (
                  <Link
                    key={plan.plan_id}
                    to={`/applicant/interview-plans/${plan.plan_id}`}
                    className="group flex items-center gap-3 py-3 transition-colors first:pt-0 last:pb-0"
                  >
                    <Avatar name={plan.company_name} />
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-sm font-medium transition-colors group-hover:text-primary">
                        {plan.company_name}
                      </div>
                      <div className="truncate text-xs text-muted-foreground">
                        {plan.position_name}
                        {techTagsOf(plan).length ? ` · ${techTagsOf(plan).slice(0, 3).join(" / ")}` : ""}
                      </div>
                    </div>
                    <StatusBadge state={plan.preparation_status ?? "none"} />
                    <ArrowRight
                      className="size-4 shrink-0 text-faint transition-colors group-hover:text-primary"
                      aria-hidden
                    />
                  </Link>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </>
  );
}
