import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Upload, FileText } from "lucide-react";
import { PageHeader, StatusBadge } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { api } from "@/lib/api";

type Resume = {
  resume_id: number;
  filename: string;
  upload_date?: string;
  file_url?: string;
  status?: string;
  analysis_status?: string;
  analysis_error?: string;
  keywords?: string[];
};

// 简历中心：上传 → 异步解析/分析状态轮询 → 列表
export function ResumeManagePage() {
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(() => {
    api
      .get<{ resumes: Resume[] }>("/api/resumes")
      .then((data) => setResumes(data.resumes))
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // 有待完成解析时轮询（解析为后台任务）
  useEffect(() => {
    const pending = resumes.some((r) => r.analysis_status === "pending" || r.analysis_status === "running");
    if (pending && !pollRef.current) {
      pollRef.current = setInterval(load, 2500);
    }
    if (!pending && pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = null;
    };
  }, [resumes, load]);

  async function handleUpload(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const file = form.get("file");
    if (!(file instanceof File) || !file.size) return;
    const data = new FormData();
    data.set("file", file);
    data.set("storage_name", file.name);
    setUploading(true);
    setError("");
    try {
      await api.upload("/api/resumes", data);
      load();
      e.currentTarget.reset();
    } catch (err) {
      setError(err instanceof Error ? err.message : "上传失败");
    } finally {
      setUploading(false);
    }
  }

  return (
    <>
      <PageHeader title="简历中心" description="上传简历后自动做 LLM 结构化解析，作为计划与实战的事实来源。" />
      <div className="flex flex-col gap-4 px-4 py-5 md:px-6">
        <Card>
          <CardContent className="py-4">
            <form className="flex flex-wrap items-center gap-3" onSubmit={handleUpload}>
              <label className="flex h-9 cursor-pointer items-center gap-2 rounded-md border border-input bg-card px-3 text-sm hover:bg-hover">
                <Upload className="size-4" />
                选择文件（doc/docx/txt/pdf）
                <input type="file" name="file" accept=".doc,.docx,.txt,.pdf" className="sr-only" />
              </label>
              <Button type="submit" disabled={uploading}>
                {uploading ? "上传解析中…" : "上传"}
              </Button>
              <p className="text-xs text-faint">上传即提交解析，解析完成后自动作为计划简历候选。</p>
            </form>
            {error ? (
              <p role="alert" className="mt-2 text-sm text-destructive">
                {error}
              </p>
            ) : null}
          </CardContent>
        </Card>

        {loading ? (
          <p className="text-sm text-faint">加载中…</p>
        ) : resumes.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center gap-2 py-10 text-center">
              <FileText className="size-8 text-faint" />
              <p className="text-sm text-muted-foreground">还没有简历，上传第一份开始。</p>
            </CardContent>
          </Card>
        ) : (
          <div className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
            {resumes.map((r) => (
              <div key={r.resume_id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <FileText className="size-4 shrink-0 text-primary" />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium">{r.filename}</div>
                  <div className="text-xs text-muted-foreground">
                    {r.upload_date ?? ""}
                    {r.analysis_error ? ` · ${r.analysis_error}` : ""}
                  </div>
                </div>
                {r.file_url ? (
                  <Button asChild size="sm" variant="ghost">
                    <a href={r.file_url} target="_blank" rel="noreferrer">
                      查看
                    </a>
                  </Button>
                ) : null}
                <StatusBadge
                  state={
                    r.analysis_status === "pending"
                      ? "generating"
                      : r.analysis_status === "failed"
                        ? "error"
                        : r.analysis_status === "completed"
                          ? "confirmed"
                          : (r.analysis_status ?? "idle")
                  }
                  label={
                    r.analysis_status === "pending"
                      ? "解析中"
                      : r.analysis_status === "completed"
                        ? "已解析"
                        : r.analysis_status ?? "未知"
                  }
                />
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
