import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { Plus, FolderKanban, ArrowRight } from "lucide-react";
import { PageHeader, StatusBadge } from "@/components/page-header";
import { Avatar } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { CardSkeleton } from "@/components/ui/skeleton";
import { toast } from "@/components/ui/toast";
import { api, ApiError } from "@/lib/api";
import { techTagsOf, type Plan } from "@/lib/plan";

export function PlanListPage() {
  const [plans, setPlans] = useState<Plan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [searchParams, setSearchParams] = useSearchParams();

  // 首页/顶栏「新建计划」入口带 ?create=1，进页自动打开弹窗
  useEffect(() => {
    if (searchParams.get("create") === "1") {
      setCreating(true);
      setSearchParams({}, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  const load = useCallback(() => {
    setLoading(true);
    api
      .get<{ plans: Plan[] }>("/api/interview-plans")
      .then((data) => setPlans(data.plans))
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function handleCreate(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setSubmitting(true);
    setError("");
    try {
      const data = await api.post<{ plan: Plan; next_action?: string }>("/api/interview-plans", {
        company_name: String(form.get("company_name") || "").trim(),
        position_name: String(form.get("position_name") || "").trim(),
        job_description: String(form.get("job_description") || "").trim(),
        level: String(form.get("level") || "").trim() || undefined,
        tech_tags: String(form.get("tech_tags") || "")
          .split(/[,，]/)
          .map((t) => t.trim())
          .filter(Boolean),
      });
      setCreating(false);
      toast.success(`已创建「${data.plan.company_name}」的计划，准备包生成中`);
      load();
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "创建失败，请稍后重试";
      setError(message);
      toast.error(message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <PageHeader
        title="面试计划"
        description="集中维护公司、岗位、JD 与简历，作为准备包和实战的统一上下文。"
        actions={
          <Button variant="gradient" onClick={() => setCreating(true)}>
            <Plus />
            新建计划
          </Button>
        }
      />
      <div className="px-4 py-5 md:px-6">
        {error && !creating ? (
          <p role="alert" className="mb-3 text-sm text-destructive">
            {error}
          </p>
        ) : null}
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
            description="创建第一个计划，开始准备包与实战闭环。"
            action={
              <Button variant="gradient" onClick={() => setCreating(true)}>
                <Plus />
                新建计划
              </Button>
            }
          />
        ) : (
          <div className="stagger grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {plans.map((plan) => (
              <Link
                key={plan.plan_id}
                to={`/applicant/interview-plans/${plan.plan_id}`}
                className="group flex flex-col rounded-xl border border-border bg-card p-4 transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md"
              >
                <div className="flex items-start gap-3">
                  <Avatar name={plan.company_name} />
                  <div className="min-w-0 flex-1">
                    <div className="truncate font-medium transition-colors group-hover:text-primary">
                      {plan.company_name}
                    </div>
                    <div className="mt-0.5 truncate text-sm text-muted-foreground">{plan.position_name}</div>
                  </div>
                  <StatusBadge state={plan.status === "archived" ? "archived" : (plan.preparation_status ?? "none")} />
                </div>
                <div className="mt-3 flex min-h-6 flex-wrap gap-1">
                  {techTagsOf(plan)
                    .slice(0, 4)
                    .map((tag) => (
                      <span
                        key={tag}
                        className="rounded-full bg-subtle px-2 py-0.5 text-xs text-muted-foreground"
                      >
                        {tag}
                      </span>
                    ))}
                </div>
                <div className="mt-3 flex items-center justify-between border-t border-border pt-3 text-xs text-faint">
                  <span>{plan.level || "未设级别"}</span>
                  <span className="flex items-center gap-1 transition-colors group-hover:text-primary">
                    查看详情
                    <ArrowRight className="size-3.5" aria-hidden />
                  </span>
                </div>
              </Link>
            ))}
          </div>
        )}
      </div>

      <Dialog open={creating} onOpenChange={setCreating}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>新建面试计划</DialogTitle>
            <DialogDescription>公司、岗位与 JD 是准备包生成的事实来源。</DialogDescription>
          </DialogHeader>
          <form className="flex flex-col gap-3" onSubmit={handleCreate}>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="company_name">公司 *</Label>
              <Input id="company_name" name="company_name" required />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="position_name">岗位 *</Label>
              <Input id="position_name" name="position_name" required />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="job_description">JD *</Label>
              <Textarea id="job_description" name="job_description" required rows={5} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="level">级别</Label>
                <Input id="level" name="level" placeholder="如：P6 / 高级" />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="tech_tags">技术标签</Label>
                <Input id="tech_tags" name="tech_tags" placeholder="逗号分隔" />
              </div>
            </div>
            {error ? (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            ) : null}
            <DialogFooter>
              <Button type="button" variant="ghost" onClick={() => setCreating(false)}>
                取消
              </Button>
              <Button type="submit" loading={submitting}>
                创建计划
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
