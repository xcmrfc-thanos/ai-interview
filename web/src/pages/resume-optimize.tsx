import { useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { Check, FileSearch } from "lucide-react";
import { PageHeader, StatusBadge } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { api } from "@/lib/api";

type Suggestion = { suggestion_id: string; original?: string; rewritten?: string; reason?: string };
type Optimization = {
  optimization_id: number;
  status: string;
  matches?: string[];
  gaps?: string[];
  keywords?: string[];
  suggestions?: Suggestion[];
  confirmed_suggestion_ids?: string[];
};

// 简历优化：对照 JD 生成候选改写（不覆盖原简历），逐条人工确认
export function ResumeOptimizePage() {
  const [params] = useSearchParams();
  const planId = params.get("plan_id");
  const [optimization, setOptimization] = useState<Optimization | null>(null);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState("");
  const [confirmed, setConfirmed] = useState<string[]>([]);

  const load = useCallback(() => {
    if (!planId) {
      setLoading(false);
      return;
    }
    api
      .get<{ optimization: Optimization }>(
        `/api/resume-optimizations/latest?plan_id=${planId}`,
      )
      .then((data) => {
        setOptimization(data.optimization);
        setConfirmed(data.optimization.confirmed_suggestion_ids ?? []);
      })
      .catch(() => setOptimization(null))
      .finally(() => setLoading(false));
  }, [planId]);

  useEffect(() => {
    load();
  }, [load]);

  async function generate() {
    if (!planId) return;
    setGenerating(true);
    setError("");
    try {
      const data = await api.post<{ optimization: Optimization }>("/api/resume-optimizations", {
        plan_id: Number(planId),
      });
      setOptimization(data.optimization);
      setConfirmed(data.optimization.confirmed_suggestion_ids ?? []);
    } catch (err) {
      setError(err instanceof Error ? err.message : "生成失败");
    } finally {
      setGenerating(false);
    }
  }

  async function confirmSuggestion(sid: string) {
    if (!optimization) return;
    try {
      await api.post(
        `/api/resume-optimizations/${optimization.optimization_id}/suggestions/${sid}/confirm`,
      );
      setConfirmed((list) => (list.includes(sid) ? list : [...list, sid]));
    } catch (err) {
      setError(err instanceof Error ? err.message : "确认失败");
    }
  }

  if (!planId) {
    return <PlanHint />;
  }

  return (
    <>
      <PageHeader
        title="简历优化"
        description="所有改写只是候选建议，确认前不会修改原简历。"
        actions={
          <Button onClick={generate} disabled={generating}>
            <FileSearch />
            {generating ? "生成中…" : optimization ? "重新生成" : "生成优化建议"}
          </Button>
        }
      />
      <div className="flex flex-col gap-4 px-4 py-5 md:px-6">
        {error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        ) : null}
        {loading ? <p className="text-sm text-faint">加载中…</p> : null}
        {!loading && !optimization ? (
          <Card>
            <CardContent className="py-10 text-center text-sm text-muted-foreground">
              还没有优化结果。确保计划已关联简历后，点击「生成优化建议」。
            </CardContent>
          </Card>
        ) : null}
        {optimization ? (
          <>
            <div className="flex flex-wrap items-center gap-3">
              <StatusBadge state={optimization.status === "needs_review" ? "needs_review" : optimization.status} />
              {(optimization.keywords ?? []).map((k) => (
                <Badge key={k} kind="primary">
                  {k}
                </Badge>
              ))}
            </div>
            <div className="grid gap-3 lg:grid-cols-2">
              <Card>
                <CardContent className="py-4">
                  <div className="mb-2 text-sm font-medium">岗位匹配点</div>
                  <BulletList items={optimization.matches ?? []} empty="暂无" />
                </CardContent>
              </Card>
              <Card>
                <CardContent className="py-4">
                  <div className="mb-2 text-sm font-medium">差距与风险</div>
                  <BulletList items={optimization.gaps ?? []} empty="暂无" />
                </CardContent>
              </Card>
            </div>
            <div className="flex flex-col gap-3">
              <div className="text-sm font-medium">改写建议（逐条确认）</div>
              {(optimization.suggestions ?? []).length === 0 ? (
                <p className="text-sm text-faint">暂无建议</p>
              ) : (
                (optimization.suggestions ?? []).map((s) => {
                  const done = confirmed.includes(s.suggestion_id);
                  return (
                    <Card key={s.suggestion_id}>
                      <CardContent className="flex flex-col gap-2 py-4">
                        {s.original ? (
                          <p className="text-sm text-muted-foreground line-through">{s.original}</p>
                        ) : null}
                        <p className="text-sm leading-relaxed">{s.rewritten}</p>
                        {s.reason ? <p className="text-xs text-faint">理由：{s.reason}</p> : null}
                        <div className="self-end">
                          <Button
                            size="sm"
                            variant={done ? "ghost" : "subtle"}
                            disabled={done}
                            onClick={() => confirmSuggestion(s.suggestion_id)}
                          >
                            <Check />
                            {done ? "已确认" : "确认采用"}
                          </Button>
                        </div>
                      </CardContent>
                    </Card>
                  );
                })
              )}
            </div>
          </>
        ) : null}
      </div>
    </>
  );
}

function BulletList({ items, empty }: { items: string[]; empty: string }) {
  if (!items.length) return <p className="text-sm text-faint">{empty}</p>;
  return (
    <ul className="flex flex-col gap-1.5">
      {items.map((item, i) => (
        <li key={i} className="flex gap-2 text-sm leading-relaxed">
          <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary" aria-hidden />
          <span>{item}</span>
        </li>
      ))}
    </ul>
  );
}

function PlanHint() {
  return (
    <div className="px-4 py-10 text-center">
      <p className="text-sm text-muted-foreground">
        简历优化需要围绕一个面试计划进行，请从
        <Link to="/applicant/interview-plans" className="text-primary hover:underline">
          计划详情
        </Link>
        进入。
      </p>
    </div>
  );
}
