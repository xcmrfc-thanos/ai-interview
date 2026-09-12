import { useCallback, useEffect, useState, type FormEvent } from "react";
import { BookOpen, Plus, RefreshCw, Search, Upload } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { api } from "@/lib/api";

type KnowledgeItem = {
  item_id?: number;
  id?: number;
  title: string;
  content?: string;
  category?: string | null;
  tech_tag?: string | null;
  role_tag?: string | null;
  difficulty?: string | null;
  enabled?: boolean;
};

// 知识库：结构化关键词检索 + 新建/编辑 + 导入 + 重建索引（为空不阻塞主流程）
export function KnowledgePage() {
  const [items, setItems] = useState<KnowledgeItem[]>([]);
  const [q, setQ] = useState("");
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<KnowledgeItem | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");
  const [importResult, setImportResult] = useState("");

  const load = useCallback((query = "") => {
    setLoading(true);
    const params = new URLSearchParams();
    if (query) params.set("q", query);
    api
      .get<{ items: KnowledgeItem[] }>(`/api/knowledge?${params}`)
      .then((data) => setItems(data.items))
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function handleSave(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const body = {
      title: String(form.get("title") || "").trim(),
      content: String(form.get("content") || "").trim(),
      category: String(form.get("category") || "").trim() || null,
      tech_tag: String(form.get("tech_tag") || "").trim() || null,
    };
    try {
      if (editing && (editing.item_id ?? editing.id) != null) {
        await api.patch(`/api/knowledge/${editing.item_id ?? editing.id}`, body);
      } else {
        await api.post("/api/knowledge", body);
      }
      setEditing(null);
      setCreating(false);
      load(q);
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存失败");
    }
  }

  async function handleImport(e: FormEvent<HTMLInputElement>) {
    const input = e.currentTarget;
    const file = input.files?.[0];
    if (!file) return;
    const data = new FormData();
    data.set("file", file);
    try {
      const res = await api.upload<{ result: { created: number; updated: number; skipped: number; failed: number } }>(
        "/api/knowledge/import",
        data,
      );
      setImportResult(`导入完成：新增 ${res.result.created}，更新 ${res.result.updated}，跳过 ${res.result.skipped}，失败 ${res.result.failed}`);
      load();
    } catch (err) {
      setImportResult(err instanceof Error ? err.message : "导入失败");
    }
    input.value = "";
  }

  const idOf = (item: KnowledgeItem) => item.item_id ?? item.id;

  return (
    <>
      <PageHeader
        title="知识库"
        description="个人面试知识沉淀，检索结果作为回答与准备包的辅助证据。"
        actions={
          <>
            <label className="cursor-pointer">
              <Button variant="outline" size="sm" asChild>
                <span>
                  <Upload />
                  导入文件
                </span>
              </Button>
              <input type="file" accept=".json,.md,.txt" className="sr-only" onChange={handleImport} />
            </label>
            <Button
              variant="outline"
              size="sm"
              onClick={async () => {
                try {
                  const res = await api.post<{ checked: number; message?: string }>("/api/knowledge/rebuild-index");
                  setImportResult(`索引已重建（检查 ${res.checked} 条）`);
                } catch (err) {
                  setImportResult(err instanceof Error ? err.message : "重建失败");
                }
              }}
            >
              <RefreshCw />
              重建索引
            </Button>
            <Button size="sm" onClick={() => setCreating(true)}>
              <Plus />
              新建条目
            </Button>
          </>
        }
      />
      <div className="flex flex-col gap-4 px-4 py-5 md:px-6">
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            load(q);
          }}
        >
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" />
            <Input
              className="pl-9"
              placeholder="搜索标题、内容、标签…"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
          </div>
          <Button type="submit" variant="outline">
            搜索
          </Button>
        </form>
        {importResult ? <p className="text-sm text-muted-foreground">{importResult}</p> : null}
        {error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        ) : null}

        {loading ? (
          <p className="text-sm text-faint">加载中…</p>
        ) : items.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center gap-2 py-10 text-center">
              <BookOpen className="size-8 text-faint" />
              <p className="text-sm text-muted-foreground">没有匹配的知识条目。知识库为空不会阻塞准备包与实战。</p>
            </CardContent>
          </Card>
        ) : (
          <div className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
            {items.map((item, idx) => (
              <button
                key={idOf(item) ?? idx}
                className="flex flex-col gap-1 px-4 py-3 text-left transition-colors hover:bg-hover"
                onClick={() => setEditing(item)}
              >
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">{item.title}</span>
                  {item.tech_tag ? (
                    <span className="rounded-full bg-subtle px-2 py-0.5 text-xs text-muted-foreground">
                      {item.tech_tag}
                    </span>
                  ) : null}
                  {item.category ? (
                    <span className="rounded-full bg-subtle px-2 py-0.5 text-xs text-muted-foreground">
                      {item.category}
                    </span>
                  ) : null}
                </div>
                {item.content ? (
                  <p className="line-clamp-2 text-xs text-muted-foreground">{item.content}</p>
                ) : null}
              </button>
            ))}
          </div>
        )}
      </div>

      <Dialog
        open={creating || editing != null}
        onOpenChange={(open) => {
          if (!open) {
            setCreating(false);
            setEditing(null);
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editing ? "编辑条目" : "新建条目"}</DialogTitle>
            <DialogDescription>内容将用于准备包与实时辅助的证据检索。</DialogDescription>
          </DialogHeader>
          <form className="flex flex-col gap-3" onSubmit={handleSave}>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="title">标题 *</Label>
              <Input id="title" name="title" required defaultValue={editing?.title ?? ""} />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="content">内容</Label>
              <Textarea id="content" name="content" rows={6} defaultValue={editing?.content ?? ""} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="category">分类</Label>
                <Input id="category" name="category" defaultValue={editing?.category ?? ""} />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="tech_tag">技术标签</Label>
                <Input id="tech_tag" name="tech_tag" defaultValue={editing?.tech_tag ?? ""} />
              </div>
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  setCreating(false);
                  setEditing(null);
                }}
              >
                取消
              </Button>
              <Button type="submit">保存</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
