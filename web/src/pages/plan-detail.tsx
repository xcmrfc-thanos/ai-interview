import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { Mic, MessagesSquare, RefreshCw, Check, ArrowLeft, Sparkles } from "lucide-react";
import { PageHeader, StatusBadge } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { PointList } from "@/components/ui/point-list";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import {
  asStringArray,
  PACK_SECTIONS,
  techTagsOf,
  type PackSectionKey,
  type Plan,
  type PreparationPack,
} from "@/lib/plan";

export function PlanDetailPage() {
  const { planId } = useParams();
  const navigate = useNavigate();
  const id = planId ? Number(planId) : null;
  const [plan, setPlan] = useState<Plan | null>(null);
  const [error, setError] = useState("");
  const [busySection, setBusySection] = useState<PackSectionKey | null>(null);
  const [confirming, setConfirming] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(() => {
    if (!id) return;
    api
      .get<{ plan: Plan }>(`/api/interview-plans/${id}`)
      .then((data) => setPlan(data.plan))
      .catch((err: Error) => setError(err.message));
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  // 准备包生成中：2s 退避轮询直到出现 pack 或超时
  useEffect(() => {
    const generating =
      plan?.preparation_status === "generating" ||
      (!plan?.packs?.length && plan?.status === "generating");
    if (!generating) {
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = null;
      return;
    }
    if (!pollRef.current) {
      pollRef.current = setInterval(load, 2000);
    }
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = null;
    };
  }, [plan, load]);

  const pack: PreparationPack | null = plan?.packs?.length
    ? plan.packs[plan.packs.length - 1]
    : null;

  async function regenerate(field: PackSectionKey) {
    if (!id || !pack) return;
    setBusySection(field);
    try {
      await api.post(
        `/api/interview-plans/${id}/preparation/${pack.pack_id}/sections/${field}/regenerate`,
      );
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "重生成失败");
    } finally {
      setBusySection(null);
    }
  }

  async function confirmPack() {
    if (!id || !pack) return;
    setConfirming(true);
    try {
      await api.post(`/api/interview-plans/${id}/preparation/${pack.pack_id}/confirm`);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "确认失败");
    } finally {
      setConfirming(false);
    }
  }

  async function saveSection(field: PackSectionKey, value: string) {
    if (!id || !pack) return;
    try {
      await api.patch(`/api/interview-plans/${id}/preparation/${pack.pack_id}`, {
        [field]: value,
      });
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存失败");
    }
  }

  if (!plan && !error) {
    return (
      <div className="px-4 py-5 md:px-6">
        <Skeleton className="h-10 w-64" />
        <div className="mt-4 grid gap-3 lg:grid-cols-2">
          <Skeleton className="h-48" />
          <Skeleton className="h-48" />
        </div>
      </div>
    );
  }

  return (
    <>
      <PageHeader
        title={plan ? `${plan.company_name} · ${plan.position_name}` : "计划详情"}
        description={plan?.level ? `目标级别：${plan.level}` : undefined}
        actions={
          <>
            <Button variant="ghost" size="sm" onClick={() => navigate("/applicant/interview-plans")}>
              <ArrowLeft />
              返回列表
            </Button>
            <Button asChild size="sm" variant="subtle">
              <Link to={`/applicant/copilot?plan_id=${id}`} title="实时 Copilot">
                <Mic />
                开始面试
              </Link>
            </Button>
            <Button asChild size="sm" variant="subtle">
              <Link to={`/applicant/mock-interview?plan_id=${id}`}>
                <MessagesSquare />
                模拟面试
              </Link>
            </Button>
          </>
        }
      />
      <div className="flex flex-col gap-4 px-4 py-5 md:px-6">
        {error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        ) : null}

        {/* 准备进度：Progress + 时间线（P2 遗留项） */}
        {plan ? <PrepTimeline plan={plan} pack={pack} /> : null}

        {/* 岗位档案 */}
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle>岗位档案</CardTitle>
              {plan ? <StatusBadge state={plan.status === "archived" ? "archived" : (plan.preparation_status ?? "none")} /> : null}
            </div>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            <div>
              <div className="text-xs font-medium text-muted-foreground">JD</div>
              <p className="mt-1 whitespace-pre-wrap leading-relaxed">{plan?.job_description}</p>
            </div>
            {plan?.extra_requirements ? (
              <div>
                <div className="text-xs font-medium text-muted-foreground">补充要求</div>
                <p className="mt-1 whitespace-pre-wrap leading-relaxed">{plan.extra_requirements}</p>
              </div>
            ) : null}
            {plan && techTagsOf(plan).length ? (
              <div className="flex flex-wrap gap-1">
                {techTagsOf(plan).map((tag) => (
                  <span key={tag} className="rounded-full bg-subtle px-2 py-0.5 text-xs text-muted-foreground">
                    {tag}
                  </span>
                ))}
              </div>
            ) : null}
          </CardContent>
        </Card>

        {/* 准备包 */}
        {!pack && plan ? (
          <Card>
            <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
              <Sparkles className="size-8 text-primary" />
              <p className="text-sm text-muted-foreground">
                {plan.preparation_status === "generating" || plan.status === "generating"
                  ? "准备包生成中…"
                  : "还没有准备包。"}
              </p>
              {plan.preparation_status !== "generating" ? (
                <Button
                  onClick={async () => {
                    try {
                      await api.post(`/api/interview-plans/${id}/preparation`);
                      load();
                    } catch (err) {
                      setError(err instanceof Error ? err.message : "生成失败");
                    }
                  }}
                >
                  <Sparkles />
                  生成准备包
                </Button>
              ) : null}
            </CardContent>
          </Card>
        ) : null}

        {pack ? (
          <>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                准备包 v{pack.version}
                <StatusBadge state={pack.status} />
              </div>
              {pack.status !== "confirmed" ? (
                <Button size="sm" onClick={confirmPack} disabled={confirming}>
                  <Check />
                  {confirming ? "确认中…" : "确认准备包"}
                </Button>
              ) : null}
            </div>
            <div className="grid gap-3 lg:grid-cols-2">
              {PACK_SECTIONS.map(({ key, label, isArray }) => {
                const value = (pack[key as keyof PreparationPack] as string | string[] | null) ?? null;
                const items = isArray ? asStringArray(value) : [];
                return (
                  <Card key={key}>
                    <CardHeader>
                      <div className="flex items-center justify-between">
                        <CardTitle className="text-sm">{label}</CardTitle>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => regenerate(key)}
                          disabled={busySection === key}
                        >
                          <RefreshCw />
                          {busySection === key ? "生成中…" : "重生成"}
                        </Button>
                      </div>
                    </CardHeader>
                    <CardContent>
                      {isArray ? (
                        items.length ? (
                          // 要点区沿用 copilot 回答卡片的视觉语言
                          <PointList points={items} />
                        ) : (
                          <p className="text-sm text-faint">暂无内容</p>
                        )
                      ) : (
                        <SectionText
                          value={typeof value === "string" ? value : ""}
                          onSave={(v) => saveSection(key, v)}
                        />
                      )}
                    </CardContent>
                  </Card>
                );
              })}
            </div>
          </>
        ) : null}
      </div>
    </>
  );
}

/** 自我介绍等文本区块：失焦保存非空修改。 */
function SectionText({ value, onSave }: { value: string; onSave: (v: string) => void }) {
  const [text, setText] = useState(value);
  useEffect(() => setText(value), [value]);
  return (
    <Textarea
      value={text}
      rows={5}
      onChange={(e) => setText(e.target.value)}
      onBlur={() => {
        if (text.trim() && text !== value) onSave(text.trim());
      }}
    />
  );
}

// 准备主流程五阶段：done 由计划/准备包状态推导，实战与复盘在本页无数据，保持待办
const TIMELINE_STAGES = [
  { key: "created", label: "计划创建" },
  { key: "pack", label: "准备包生成" },
  { key: "confirmed", label: "确认就绪" },
  { key: "interview", label: "实战面试" },
  { key: "review", label: "复盘沉淀" },
] as const;

type StageState = "done" | "active" | "todo";

function timelineStates(plan: Plan, pack: PreparationPack | null): StageState[] {
  const generating = plan.preparation_status === "generating" || plan.status === "generating";
  const hasPack = !!pack;
  const confirmed = pack?.status === "confirmed";
  return TIMELINE_STAGES.map((_, i): StageState => {
    if (i === 0) return "done";
    if (i === 1) return hasPack ? "done" : generating ? "active" : "todo";
    if (i === 2) return confirmed ? "done" : hasPack ? "active" : "todo";
    return "todo";
  });
}

/** 准备进度卡：品牌渐变 Progress + 横向时间线（当前阶段呼吸灯） */
function PrepTimeline({ plan, pack }: { plan: Plan; pack: PreparationPack | null }) {
  const states = timelineStates(plan, pack);
  const doneCount = states.filter((s) => s === "done").length;
  const percent = Math.round((doneCount / (TIMELINE_STAGES.length - 1)) * 100);
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle>准备进度</CardTitle>
          <span className="text-xs tabular-nums text-faint">{percent}%</span>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <Progress value={percent} ariaLabel={`准备进度 ${percent}%`} />
        <ol className="flex">
          {TIMELINE_STAGES.map((stage, i) => {
            const st = states[i];
            return (
              <li key={stage.key} className="relative flex flex-1 flex-col items-center">
                {i > 0 ? (
                  <span
                    aria-hidden
                    className={cn(
                      "absolute right-1/2 top-[7px] h-px w-full",
                      states[i - 1] === "done" ? "bg-primary" : "bg-border-strong",
                    )}
                  />
                ) : null}
                <span
                  className={cn(
                    "relative z-10 flex size-4 items-center justify-center rounded-full border-2",
                    st === "done" && "border-primary bg-primary text-primary-foreground",
                    st === "active" && "border-primary bg-background text-primary",
                    st === "todo" && "border-border-strong bg-background text-faint",
                  )}
                >
                  {st === "active" ? (
                    <span aria-hidden className="absolute inline-flex size-full animate-ping rounded-full bg-primary opacity-40" />
                  ) : null}
                  {st === "done" ? (
                    <Check className="size-2.5" aria-hidden />
                  ) : (
                    <span aria-hidden className="size-1 rounded-full bg-current" />
                  )}
                </span>
                <span
                  className={cn(
                    "mt-1.5 text-center text-xs",
                    st === "active" && "font-medium text-primary",
                    st === "done" && "text-muted-foreground",
                    st === "todo" && "text-faint",
                  )}
                >
                  {stage.label}
                </span>
              </li>
            );
          })}
        </ol>
      </CardContent>
    </Card>
  );
}
