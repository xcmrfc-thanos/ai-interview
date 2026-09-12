import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router";
import {
  Mic,
  MicOff,
  Pause,
  PhoneOff,
  RefreshCw,
  ClipboardCheck,
  FolderKanban,
  RotateCcw,
  Zap,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { PointList } from "@/components/ui/point-list";
import { api } from "@/lib/api";
import { useCopilotSession, type CaptureMode } from "@/features/copilot/use-copilot-session";
import { kindFor } from "@/features/copilot/conn-state";
import { cn } from "@/lib/utils";
import { asStringArray } from "@/lib/plan";

type Plan = {
  plan_id: number;
  company_name: string;
  position_name: string;
  level?: string | null;
  status?: string;
  tech_tags?: string | string[] | null;
};

type ModeOption = { value: CaptureMode; label: string };

const MODE_OPTIONS: ModeOption[] = [
  { value: "auto", label: "自动" },
  { value: "local", label: "本人" },
  { value: "remote", label: "面试官" },
];

const STATUS_KIND: Record<string, "neutral" | "primary" | "info" | "success" | "warning" | "danger"> = {
  active: "success",
  connecting: "info",
  warning: "warning",
  error: "danger",
  idle: "neutral",
};

export function CopilotPage() {
  const [params] = useSearchParams();
  const planIdParam = params.get("plan_id");
  const planId = planIdParam ? Number(planIdParam) : null;
  const [plan, setPlan] = useState<Plan | null>(null);
  const [planError, setPlanError] = useState("");

  const session = useCopilotSession(planId);
  const [generatingReview, setGeneratingReview] = useState(false);
  const [reviewDone, setReviewDone] = useState(false);
  const [reviewError, setReviewError] = useState("");

  useEffect(() => {
    if (!planId) return;
    api
      .get<{ plan: Plan }>(`/api/interview-plans/${planId}`)
      .then((data) => setPlan(data.plan))
      .catch((err: Error) => setPlanError(err.message));
  }, [planId]);

  const techTags = useMemo(() => asStringArray(plan?.tech_tags), [plan]);

  const generateReview = useCallback(async () => {
    if (!session.sessionId) return;
    setGeneratingReview(true);
    setReviewError("");
    try {
      await api.post("/api/reviews/generate", { source_type: "copilot", source_id: session.sessionId });
      setReviewDone(true);
    } catch (err) {
      setReviewError(err instanceof Error ? err.message : "生成复盘失败");
    } finally {
      setGeneratingReview(false);
    }
  }, [session]);

  const connKind = kindFor(session.connStateKey);
  const closed = session.sessionEndedByUser && session.connStateKey === "ended";

  if (!planId) {
    return <PlanPicker />;
  }

  return (
    <div className="relative flex h-[calc(100vh-3.5rem)] flex-col" data-testid="copilot-workspace">
      {/* 上下文栏 */}
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-2.5">
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium">
            {plan ? `${plan.company_name} · ${plan.position_name}` : "实时 Copilot"}
          </div>
          <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
            <Badge kind={STATUS_KIND[connKind] ?? "neutral"} data-state={connKind} className="gap-1.5">
              {/* 连接呼吸灯：对齐首页演示面板「AI 实时辅助中」 */}
              {connKind === "active" ? (
                <span className="relative flex size-2" aria-hidden>
                  <span className="absolute inline-flex size-full animate-ping rounded-full bg-primary opacity-60" />
                  <span className="relative inline-flex size-2 rounded-full bg-primary" />
                </span>
              ) : null}
              {session.connState}
            </Badge>
            <span>{session.sourceLabel}</span>
            <span aria-hidden>·</span>
            <span>说话人：{session.speakerLabel}</span>
            {techTags.map((tag) => (
              <Badge key={tag} kind="neutral">
                {tag}
              </Badge>
            ))}
            {session.captureBackend ? (
              <>
                <span aria-hidden>·</span>
                <span title={session.captureBackend.includes("worklet") ? "AudioWorklet" : "ScriptProcessor 降级"}>
                  采集：{session.captureBackend.includes("worklet") ? "Worklet" : "降级"}
                </span>
              </>
            ) : null}
          </div>
        </div>
        <div className="flex items-center gap-1" role="group" aria-label="采集模式">
          {MODE_OPTIONS.map((opt) => (
            <Button
              key={opt.value}
              size="sm"
              variant={session.captureMode === opt.value ? "subtle" : "ghost"}
              onClick={() => session.onCaptureModeChange(opt.value)}
            >
              {opt.label}
            </Button>
          ))}
        </div>
        <div className="flex items-center gap-2">
          {session.recording ? (
            <Button size="sm" variant="outline" onClick={session.endSession}>
              <Pause />
              结束会话
            </Button>
          ) : (
            <Button size="sm" onClick={() => session.toggleRecording()} disabled={!session.displayMediaSupported && session.captureMode !== "local"}>
              <Mic />
              {session.sessionId ? "开始采集" : "开始面试"}
            </Button>
          )}
          {session.sessionId && !session.recording && session.connStateKey !== "connected" ? (
            <Button size="sm" variant="outline" onClick={session.reconnect}>
              <RefreshCw />
              重新连接
            </Button>
          ) : null}
        </div>
      </div>

      {planError ? (
        <div className="px-4 py-2 text-sm text-destructive" role="alert">
          计划加载失败：{planError}
        </div>
      ) : null}
      {session.captureStatus ? (
        <div className="border-b border-border px-4 py-1.5 text-xs text-muted-foreground">
          {session.captureStatus}
        </div>
      ) : null}

      {/* 主体：桌面 58/42 双栏，窄屏上下堆叠 */}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-px overflow-hidden bg-border xl:grid-cols-[58fr_42fr]">
        {/* 转写区 */}
        <section className="flex min-h-0 flex-col bg-card" aria-label="实时转写">
          <div className="flex items-center justify-between border-b border-border px-4 py-2">
            <h2 className="text-sm font-medium">实时转写</h2>
            <span className="text-xs text-faint">{session.currentTime}</span>
          </div>
          <div
            ref={session.feedRef}
            onScroll={session.onFeedScroll}
            className="relative min-h-0 flex-1 overflow-y-auto px-4 py-3"
          >
            {session.messages.length === 0 && !session.current.text ? (
              <p className="pt-10 text-center text-sm text-faint">
                点击「开始面试」后，这里的每一句话都会实时转写。
                <br />
                系统声音=面试官；麦克风按声纹识别是否为本人。
              </p>
            ) : null}
            <div className="flex flex-col gap-2.5">
              {session.messages.map((m, i) => (
                <Bubble key={i} side={m.side} name={m.name} time={m.time} text={m.text} />
              ))}
              {session.current.text ? (
                <Bubble
                  side={session.current.side}
                  name={session.current.name}
                  time={session.currentTime}
                  text={session.current.text}
                  live
                  streaming={session.currentStreaming}
                />
              ) : null}
            </div>
          </div>
          {session.feedPendingCount > 0 ? (
            <div className="pointer-events-none relative">
              <Button
                size="sm"
                className="pointer-events-auto absolute bottom-3 left-1/2 -translate-x-1/2 shadow-lg"
                onClick={session.scrollFeedToBottom}
              >
                有 {session.feedPendingCount} 条新内容
              </Button>
            </div>
          ) : null}
        </section>

        {/* 回答区 */}
        <section className="flex min-h-0 flex-col bg-card" aria-label="回答要点">
          <div className="flex items-center justify-between border-b border-border px-4 py-2">
            <h2 className="text-sm font-medium">回答要点</h2>
            {session.answering ? <Badge kind="primary">生成中</Badge> : null}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
            {session.answerPoints.length ? (
              <PointList points={session.answerPoints} className="mb-3" />
            ) : (
              <p className="pt-6 text-center text-sm text-faint">面试官提问后，这里会给出回答要点与参考回答。</p>
            )}
            {session.referenceAnswer ? (
              <div className="mt-2 rounded-lg border border-border bg-subtle p-3">
                <div className="mb-1.5 flex items-center justify-between">
                  <span className="text-xs font-medium text-muted-foreground">参考回答</span>
                  {session.referenceStreaming ? (
                    <span className="flex items-center gap-1 rounded-full bg-primary-soft px-2 py-0.5 text-[11px] font-medium text-primary">
                      <Zap className="size-3" aria-hidden />
                      流式输出
                    </span>
                  ) : null}
                </div>
                <p className="whitespace-pre-wrap text-sm leading-relaxed">
                  {session.referenceAnswer}
                  {session.referenceStreaming ? <TypeCursor /> : null}
                </p>
              </div>
            ) : null}
          </div>
        </section>
      </div>

      {session.wsError ? (
        <div className="flex items-center gap-2 border-t border-border bg-destructive-soft px-4 py-2 text-sm text-destructive" role="alert">
          <MicOff className="size-4" />
          {session.wsError}
        </div>
      ) : null}

      {/* 读屏播报区：仅 final 转写与连接错误 */}
      <div aria-live="polite" className="sr-only">
        {session.ariaLiveMessage}
      </div>

      {/* 结束闭环面板（U5） */}
      {closed ? (
        <div className="absolute inset-0 z-30 flex items-center justify-center bg-overlay" data-testid="session-closure">
          <Card className="w-full max-w-sm p-6 text-center shadow-lg">
            <PhoneOff className="mx-auto mb-3 size-8 text-primary" />
            <h2 className="text-lg font-semibold">面试已结束</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              生成复盘可汇总本次的问题、风险与下一步任务。
            </p>
            <div className="mt-5 flex flex-col gap-2">
              {reviewError ? (
                <p role="alert" className="text-sm text-destructive">
                  {reviewError}
                </p>
              ) : null}
              <Button onClick={generateReview} disabled={generatingReview || reviewDone}>
                <ClipboardCheck />
                {generatingReview ? "生成中…" : reviewDone ? "复盘已生成" : "生成复盘"}
              </Button>
              {reviewDone ? (
                <Button asChild variant="outline">
                  <Link to="/applicant/reviews">查看复盘</Link>
                </Button>
              ) : null}
              <Button asChild variant="outline">
                <Link to={planId ? `/applicant/interview-plans/${planId}` : "/applicant/interview-plans"}>
                  <FolderKanban />
                  返回计划
                </Link>
              </Button>
              <Button
                variant="ghost"
                onClick={() => window.location.reload()}
              >
                <RotateCcw />
                再练一次
              </Button>
            </div>
          </Card>
        </div>
      ) : null}
    </div>
  );
}

/** 打字光标：对齐首页演示面板 */
function TypeCursor() {
  return (
    <span
      aria-hidden
      className="ml-0.5 inline-block h-4 w-[2px] translate-y-0.5 animate-[landing-blink_0.9s_steps(1)_infinite] bg-primary"
    />
  );
}

function Bubble({
  side,
  name,
  time,
  text,
  live,
  streaming,
}: {
  side: "candidate" | "interviewer";
  name: string;
  time: string;
  text: string;
  live?: boolean;
  streaming?: boolean;
}) {
  const isCandidate = side === "candidate";
  // 不加入场动画：live 气泡落定为已提交消息时 DOM 重建，动画重播会造成每句一次的闪烁
  return (
    <div className={cn("flex flex-col", isCandidate ? "items-end" : "items-start")}>
      <div className="mb-0.5 flex items-center gap-2 text-xs text-faint">
        {time ? <span>{time}</span> : null}
        {live ? <Badge kind="primary">转写中</Badge> : null}
      </div>
      {/* 视觉分级对齐首页演示面板：我=primary 底右侧，面试官=subtle 底左侧；说话人名牌在气泡内 */}
      <div
        className={cn(
          "max-w-[85%] rounded-lg px-3 py-2 text-sm leading-relaxed",
          isCandidate ? "bg-primary/15 text-foreground" : "bg-subtle text-foreground",
        )}
      >
        <b className="mb-0.5 block text-xs text-primary">{name}</b>
        {text}
        {live && streaming ? <TypeCursor /> : null}
      </div>
    </div>
  );
}

function PlanPicker() {
  const [plans, setPlans] = useState<Plan[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api
      .get<{ plans: Plan[] }>("/api/interview-plans")
      .then((data) => setPlans(data.plans.filter((p) => p.status !== "archived")))
      .catch(() => setPlans([]))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <h1 className="text-lg font-semibold">选择面试计划</h1>
      <p className="mt-1 text-sm text-muted-foreground">实时 Copilot 围绕一个面试计划的岗位与简历上下文工作。</p>
      <div className="mt-4 flex flex-col gap-2">
        {loading ? <p className="text-sm text-faint">加载中…</p> : null}
        {!loading && plans.length === 0 ? (
          <Card className="p-6 text-center text-sm text-muted-foreground">
            还没有面试计划，
            <Link to="/applicant/interview-plans" className="text-primary hover:underline">
              去创建一个
            </Link>
            。
          </Card>
        ) : null}
        {plans.map((p) => (
          <Link
            key={p.plan_id}
            to={`/applicant/copilot?plan_id=${p.plan_id}`}
            className="rounded-lg border border-border bg-card p-4 transition-colors hover:bg-hover"
          >
            <div className="font-medium">{p.company_name}</div>
            <div className="text-sm text-muted-foreground">{p.position_name}</div>
          </Link>
        ))}
      </div>
    </div>
  );
}
