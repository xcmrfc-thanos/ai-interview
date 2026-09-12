import { useCallback, useState } from "react";
import { Link, useSearchParams } from "react-router";
import {
  ClipboardCheck,
  FolderKanban,
  MessagesSquare,
  PhoneOff,
  RotateCcw,
  Send,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { ScoreRing } from "@/components/ui/score-ring";
import { Textarea } from "@/components/ui/textarea";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";

type MockTurn = {
  turn_id: number;
  turn_number: number;
  question?: string;
  answer?: string;
  scores?: Record<string, number>;
  strengths?: string[];
  improvements?: string[];
  status?: string;
};

type MockInterview = {
  mock_interview_id: number;
  plan_id: number;
  status: "running" | "completed" | "ended";
  current_turn_number: number;
  question_count?: number;
  current_question?: string | null;
  turns?: MockTurn[];
};

type Evaluation = {
  scores?: Record<string, number>;
  strengths?: string[];
  improvements?: string[];
  fact_risks?: string[];
  reference_points?: string[];
  retryable?: boolean;
};

const SCORE_LABELS: Record<string, string> = {
  fact_consistency: "事实一致",
  job_relevance: "岗位相关",
  completeness: "完整性",
  expression: "表达",
  overall: "综合",
};

function scoreLabel(key: string) {
  return SCORE_LABELS[key] ?? key;
}

export function MockInterviewPage() {
  const [params] = useSearchParams();
  const planIdParam = params.get("plan_id");
  const planId = planIdParam ? Number(planIdParam) : null;

  const [interview, setInterview] = useState<MockInterview | null>(null);
  const [answer, setAnswer] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [evaluation, setEvaluation] = useState<Evaluation | null>(null);
  const [ended, setEnded] = useState(false);
  const [reviewDone, setReviewDone] = useState(false);
  const [reviewGenerating, setReviewGenerating] = useState(false);

  const start = useCallback(async () => {
    if (!planId) return;
    setError("");
    setEvaluation(null);
    try {
      const data = await api.post<{ interview: MockInterview }>("/api/mock-interviews", { plan_id: planId });
      setInterview(data.interview);
      setEnded(false);
      setReviewDone(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "开局失败");
    }
  }, [planId]);

  const submit = useCallback(async () => {
    if (!interview || !answer.trim()) return;
    setSubmitting(true);
    setError("");
    try {
      const data = await api.post<{ evaluation: Evaluation; interview: MockInterview }>(
        `/api/mock-interviews/${interview.mock_interview_id}/answers`,
        { answer: answer.trim() },
      );
      setEvaluation(data.evaluation);
      setInterview(data.interview);
      setAnswer("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "提交失败");
    } finally {
      setSubmitting(false);
    }
  }, [answer, interview]);

  const end = useCallback(async () => {
    if (!interview) return;
    try {
      const data = await api.post<{ interview: MockInterview }>(
        `/api/mock-interviews/${interview.mock_interview_id}/end`,
      );
      setInterview(data.interview);
      setEnded(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "结束失败");
    }
  }, [interview]);

  const generateReview = useCallback(async () => {
    if (!interview) return;
    setReviewGenerating(true);
    try {
      await api.post("/api/reviews/generate", {
        source_type: "mock",
        source_id: interview.mock_interview_id,
      });
      setReviewDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "生成复盘失败");
    } finally {
      setReviewGenerating(false);
    }
  }, [interview]);

  if (!planId) {
    return <PlanHint />;
  }

  const running = interview?.status === "running";
  const question = interview?.current_question || "";
  const progress =
    interview && interview.question_count
      ? `${interview.current_turn_number}/${interview.question_count}`
      : interview
        ? String(interview.current_turn_number)
        : "";

  return (
    <div className="relative flex h-[calc(100vh-3.5rem)] flex-col" data-testid="mock-workspace">
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-2.5">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 text-sm font-medium">
            模拟面试
            {progress ? <Badge kind="neutral">{progress}</Badge> : null}
            {running ? <Badge kind="success">进行中</Badge> : null}
          </div>
        </div>
        {running ? (
          <Button size="sm" variant="outline" onClick={end}>
            <PhoneOff />
            结束
          </Button>
        ) : (
          <Button size="sm" onClick={start} disabled={!planId}>
            <MessagesSquare />
            {interview ? "再练一次" : "开始模拟面试"}
          </Button>
        )}
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-px overflow-hidden bg-border lg:grid-cols-2">
        {/* 题目与作答 */}
        <section className="flex min-h-0 flex-col bg-card" aria-label="题目与作答">
          <div className="border-b border-border px-4 py-2 text-sm font-medium">当前题目</div>
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
            {question ? (
              <p className="text-base leading-relaxed">{question}</p>
            ) : (
              <p className="pt-6 text-center text-sm text-faint">
                {interview ? "本轮已完成，可结束或再练一次。" : "点击「开始模拟面试」，按岗位生成多轮问题。"}
              </p>
            )}
          </div>
          <div className="border-t border-border p-3">
            <Textarea
              rows={4}
              placeholder="作答后提交，会得到四维评分与改进建议…"
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              disabled={!running}
            />
            <div className="mt-2 flex justify-end">
              <Button size="sm" onClick={submit} disabled={!running || submitting || !answer.trim()}>
                <Send />
                {submitting ? "评分中…" : "提交作答"}
              </Button>
            </div>
          </div>
        </section>

        {/* 评分与历史 */}
        <section className="flex min-h-0 flex-col overflow-y-auto bg-card" aria-label="评分与历史">
          <div className="sticky top-0 border-b border-border bg-card px-4 py-2 text-sm font-medium">评分反馈</div>
          <div className="flex flex-col gap-3 px-4 py-3">
            {error ? (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            ) : null}
            {evaluation ? (
              <Card className="animate-rise border-primary/40">
                <CardContent className="flex flex-col gap-3 py-3">
                  {/* 综合评分用 ScoreRing，分维评分用 Progress + 分数 */}
                  <div className="flex items-center gap-4">
                    {typeof evaluation.scores?.overall === "number" ? (
                      <ScoreRing score={evaluation.scores.overall} label="综合评分" size={88} />
                    ) : null}
                    <div className="min-w-0 flex-1 space-y-2">
                      {Object.entries(evaluation.scores ?? {})
                        .filter(([k]) => k !== "overall")
                        .map(([k, v]) => (
                          <ScoreRow key={k} label={scoreLabel(k)} value={v as number} />
                        ))}
                    </div>
                  </div>
                  <EvalList title="亮点" items={evaluation.strengths} />
                  <EvalList title="改进建议" items={evaluation.improvements} />
                  <EvalList title="事实风险" items={evaluation.fact_risks} />
                  <EvalList title="参考要点" items={evaluation.reference_points} />
                </CardContent>
              </Card>
            ) : null}
            {(interview?.turns ?? []).length ? (
              <>
                <div className="text-sm font-medium">历史回答</div>
                {(interview?.turns ?? []).map((t) => (
                  <div key={t.turn_id} className="animate-rise rounded-md border border-border p-3 text-sm">
                    <div className="font-medium">
                      Q{t.turn_number}：{t.question}
                    </div>
                    {t.answer ? <p className="mt-1 text-muted-foreground">{t.answer}</p> : null}
                    {t.scores && Object.keys(t.scores).length ? (
                      <div className="mt-2 space-y-1.5">
                        {Object.entries(t.scores).map(([k, v]) => (
                          <ScoreRow key={k} label={scoreLabel(k)} value={v as number} />
                        ))}
                      </div>
                    ) : null}
                  </div>
                ))}
              </>
            ) : null}
          </div>
        </section>
      </div>

      {ended ? (
        <div className="absolute inset-0 z-30 flex items-center justify-center bg-overlay" data-testid="mock-closure">
          <Card className="w-full max-w-sm p-6 text-center shadow-lg">
            <PhoneOff className="mx-auto mb-3 size-8 text-primary" />
            <h2 className="text-lg font-semibold">模拟面试已结束</h2>
            <p className="mt-1 text-sm text-muted-foreground">生成复盘，汇总问题、评分与下一步任务。</p>
            <div className="mt-5 flex flex-col gap-2">
              {error ? (
                <p role="alert" className="text-sm text-destructive">
                  {error}
                </p>
              ) : null}
              <Button onClick={generateReview} disabled={reviewGenerating || reviewDone}>
                <ClipboardCheck />
                {reviewGenerating ? "生成中…" : reviewDone ? "复盘已生成" : "生成复盘"}
              </Button>
              {reviewDone ? (
                <Button asChild variant="outline">
                  <Link to="/applicant/reviews">查看复盘</Link>
                </Button>
              ) : null}
              <Button asChild variant="outline">
                <Link to={`/applicant/interview-plans/${planId}`}>
                  <FolderKanban />
                  返回计划
                </Link>
              </Button>
              <Button variant="ghost" onClick={start}>
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

/** 单维评分行：细条进度 + 分值，≥80 绿 / ≥60 黄 / 其余红 */
function ScoreRow({ label, value }: { label: string; value: number }) {
  const tone = value >= 80 ? "text-success" : value >= 60 ? "text-warning" : "text-destructive";
  return (
    <div className="flex items-center gap-2.5">
      <span className="w-16 shrink-0 text-xs text-muted-foreground">{label}</span>
      <Progress value={value} gradient={false} className="flex-1" ariaLabel={`${label} ${value} 分`} />
      <span className={cn("w-7 text-right text-xs font-semibold tabular-nums", tone)}>{value}</span>
    </div>
  );
}

function EvalList({ title, items }: { title: string; items?: string[] }) {
  if (!items?.length) return null;
  return (
    <div>
      <div className="text-xs font-medium text-muted-foreground">{title}</div>
      <ul className="mt-1 flex flex-col gap-1">
        {items.map((item, i) => (
          <li key={i} className={cn("flex gap-2 text-sm leading-relaxed")}>
            <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary" aria-hidden />
            <span>{item}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function PlanHint() {
  return (
    <div className="px-4 py-10 text-center">
      <p className="text-sm text-muted-foreground">
        模拟面试需要围绕一个面试计划进行，请从
        <Link to="/applicant/interview-plans" className="text-primary hover:underline">
          计划详情
        </Link>
        进入。
      </p>
    </div>
  );
}
