import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { ArrowLeft, Download } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScoreRing } from "@/components/ui/score-ring";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";

type Review = {
  review_id: number;
  source_type: string;
  summary?: string;
  question_categories?: string[];
  scores?: { technical?: number; expression?: number; preparation?: number };
  fact_risks?: string[];
  expression_issues?: string[];
  weak_topics?: string[];
  next_actions?: string[];
  diagnostics?: string[];
};

function ScoreStat({ label, value }: { label: string; value?: number }) {
  if (typeof value !== "number") {
    return (
      <div className="flex flex-col items-center gap-2">
        <div className="flex size-24 items-center justify-center rounded-full border border-dashed border-border-strong text-sm text-faint">
          --
        </div>
        <small className="text-xs text-muted-foreground">{label}</small>
      </div>
    );
  }
  return <ScoreRing score={value} label={label} />;
}

function RiskList({ title, items, tone }: { title: string; items?: string[]; tone: "danger" | "warning" | "primary" }) {
  const list = items ?? [];
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        {list.length === 0 ? (
          <p className="text-sm text-faint">暂无</p>
        ) : (
          <ul className="flex flex-col gap-1.5">
            {list.map((item, i) => (
              <li key={i} className="flex gap-2 text-sm leading-relaxed">
                <span
                  className={`mt-1.5 size-1.5 shrink-0 rounded-full ${
                    tone === "danger" ? "bg-destructive" : tone === "warning" ? "bg-warning" : "bg-primary"
                  }`}
                  aria-hidden
                />
                <span>{item}</span>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

export function ReviewDetailPage() {
  const { reviewId } = useParams();
  const [review, setReview] = useState<Review | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!reviewId) return;
    api
      .get<{ review: Review }>(`/api/reviews/${reviewId}`)
      .then((data) => setReview(data.review))
      .catch((err: Error) => setError(err.message));
  }, [reviewId]);

  if (error) {
    return (
      <div className="px-4 py-6 text-sm text-destructive" role="alert">
        {error}
      </div>
    );
  }
  if (!review) {
    return (
      <div className="px-4 py-5 md:px-6">
        <Skeleton className="h-10 w-64" />
        <div className="mt-4 grid gap-3 lg:grid-cols-3">
          <Skeleton className="h-32" />
          <Skeleton className="h-32" />
          <Skeleton className="h-32" />
        </div>
      </div>
    );
  }

  return (
    <>
      <PageHeader
        title={review.summary || `复盘 #${review.review_id}`}
        description={review.source_type === "mock" ? "来源：模拟面试" : "来源：实时 Copilot"}
        actions={
          <>
            <Button asChild variant="ghost" size="sm">
              <Link to="/applicant/reviews">
                <ArrowLeft />
                返回列表
              </Link>
            </Button>
            <Button asChild size="sm" variant="outline">
              <a href={`/api/reviews/${review.review_id}/export?format=txt`}>
                <Download />
                导出
              </a>
            </Button>
          </>
        }
      />
      <div className="flex flex-col gap-4 px-4 py-5 md:px-6">
        <Card variant="elevated">
          <CardContent className="flex items-center justify-around py-6">
            <ScoreStat label="技术" value={review.scores?.technical} />
            <ScoreStat label="表达" value={review.scores?.expression} />
            <ScoreStat label="准备度" value={review.scores?.preparation} />
          </CardContent>
        </Card>
        {review.question_categories?.length ? (
          <div className="flex flex-wrap gap-1.5">
            {review.question_categories.map((c) => (
              <span key={c} className="rounded-full bg-subtle px-2.5 py-0.5 text-xs text-muted-foreground">
                {c}
              </span>
            ))}
          </div>
        ) : null}
        <div className="grid gap-3 lg:grid-cols-2">
          <RiskList title="事实风险" items={review.fact_risks} tone="danger" />
          <RiskList title="表达问题" items={review.expression_issues} tone="warning" />
          <RiskList title="薄弱主题" items={review.weak_topics} tone="warning" />
          <RiskList title="下一步行动" items={review.next_actions} tone="primary" />
        </div>
        <RiskList title="诊断说明" items={review.diagnostics} tone="primary" />
      </div>
    </>
  );
}
