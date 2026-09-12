import { useEffect, useState } from "react";
import { Link } from "react-router";
import { ClipboardCheck, ArrowRight } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { CardSkeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";

type Review = {
  review_id: number;
  plan_id?: number;
  source_type: string;
  source_id?: number;
  summary?: string;
  created_at?: string;
};

const SOURCE_LABEL: Record<string, string> = { copilot: "实时 Copilot", mock: "模拟面试" };

export function ReviewListPage() {
  const [reviews, setReviews] = useState<Review[]>([]);
  const [loading, setLoading] = useState(true);
  const [sourceType, setSourceType] = useState("");

  useEffect(() => {
    const params = new URLSearchParams();
    if (sourceType) params.set("source_type", sourceType);
    api
      .get<{ reviews: Review[] }>(`/api/reviews?${params}`)
      .then((data) => setReviews(data.reviews))
      .catch(() => setReviews([]))
      .finally(() => setLoading(false));
  }, [sourceType]);

  return (
    <>
      <PageHeader
        title="复盘"
        description="汇总实时辅助与模拟面试的问题、评分、风险与下一步任务。"
        actions={
          <div className="flex gap-1">
            {[
              { v: "", label: "全部" },
              { v: "copilot", label: "Copilot" },
              { v: "mock", label: "模拟面试" },
            ].map((opt) => (
              <Button
                key={opt.v}
                size="sm"
                variant={sourceType === opt.v ? "subtle" : "ghost"}
                onClick={() => setSourceType(opt.v)}
              >
                {opt.label}
              </Button>
            ))}
          </div>
        }
      />
      <div className="px-4 py-5 md:px-6">
        {loading ? (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {[0, 1, 2].map((i) => (
              <CardSkeleton key={i} />
            ))}
          </div>
        ) : reviews.length === 0 ? (
          <EmptyState
            icon={ClipboardCheck}
            title="还没有复盘"
            description="结束一场实时辅助或模拟面试后，即可生成复盘。"
          />
        ) : (
          <div className="stagger grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {reviews.map((r) => (
              <Link
                key={r.review_id}
                to={`/applicant/reviews/${r.review_id}`}
                className="group flex flex-col rounded-xl border border-border bg-card p-4 transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md"
              >
                <div className="flex items-start justify-between gap-2">
                  <Badge kind={r.source_type === "mock" ? "info" : "primary"}>
                    {SOURCE_LABEL[r.source_type] ?? r.source_type}
                  </Badge>
                  {r.created_at ? <span className="text-xs text-faint">{r.created_at}</span> : null}
                </div>
                <p className="mt-3 line-clamp-2 min-h-10 text-sm font-medium leading-relaxed">
                  {r.summary || `复盘 #${r.review_id}`}
                </p>
                <span className="mt-3 flex items-center gap-1 border-t border-border pt-3 text-xs text-faint transition-colors group-hover:text-primary">
                  查看详情
                  <ArrowRight className="size-3.5" aria-hidden />
                </span>
              </Link>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
