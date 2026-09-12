import { useEffect, useState, type FormEvent } from "react";
import { Check, KeyRound } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { toast } from "@/components/ui/toast";
import { api } from "@/lib/api";

// 模型配置页：LLM 提供商/模型/APIKey 可视化配置。
// apiKey 加密落库（.env 的 CONFIG_ENCRYPTION_KEY/APP_SECRET_KEY 派生密钥），
// 明文不回显；留空 = 保留现有密钥；未保存数据库配置时运行时回退 .env。

type LlmSettings = {
  provider: string;
  base_url: string;
  model: string;
  copilot_model: string;
  think_model: string;
  has_api_key: boolean;
  api_key_masked: string;
  api_key_source: "database" | "environment" | "none";
  updated_at: string | null;
};

const PROVIDER_OPTIONS = [
  { value: "siliconflow", label: "SiliconFlow" },
  { value: "ark", label: "火山方舟 Ark" },
  { value: "openai", label: "OpenAI 兼容" },
];

const SOURCE_LABEL: Record<LlmSettings["api_key_source"], { text: string; kind: "success" | "info" | "neutral" }> = {
  database: { text: "数据库（已加密）", kind: "success" },
  environment: { text: ".env 环境变量", kind: "info" },
  none: { text: "未配置", kind: "neutral" },
};

export function LlmSettingsPage() {
  const [config, setConfig] = useState<LlmSettings | null>(null);
  const [form, setForm] = useState({
    provider: "",
    base_url: "",
    model: "",
    copilot_model: "",
    think_model: "",
    api_key: "",
  });
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    api
      .get<{ config: LlmSettings }>("/api/settings/llm")
      .then((data) => {
        setConfig(data.config);
        setForm({
          provider: data.config.provider,
          base_url: data.config.base_url,
          model: data.config.model,
          copilot_model: data.config.copilot_model,
          think_model: data.config.think_model,
          api_key: "",
        });
      })
      .catch((err: Error) => toast.error(err.message));
  }, []);

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    try {
      const body: Record<string, string | boolean> = {
        provider: form.provider,
        base_url: form.base_url,
        model: form.model,
        copilot_model: form.copilot_model,
        think_model: form.think_model,
      };
      if (form.api_key.trim()) {
        body.api_key = form.api_key.trim();
      }
      await api.put("/api/settings/llm", body);
      const data = await api.get<{ config: LlmSettings }>("/api/settings/llm");
      setConfig(data.config);
      setForm((f) => ({ ...f, api_key: "" }));
      setSaved(true);
      toast.success("模型配置已保存");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  }

  const source = config ? SOURCE_LABEL[config.api_key_source] : SOURCE_LABEL.none;

  return (
    <>
      <PageHeader
        title="模型配置"
        description="配置 LLM 提供商、模型与 API Key。保存后立即生效，优先于 .env 环境变量。"
      />
      <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-5 md:px-6">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="flex items-center gap-2">
                <KeyRound className="size-4 text-primary" aria-hidden />
                LLM 提供商
              </CardTitle>
              <Badge kind={source.kind} title="API Key 当前来源">
                {source.text}
              </Badge>
            </div>
          </CardHeader>
          <CardContent>
            <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="provider">提供商</Label>
                <Input
                  id="provider"
                  list="provider-options"
                  placeholder="siliconflow / ark / openai 兼容"
                  value={form.provider}
                  onChange={(e) => setForm((f) => ({ ...f, provider: e.target.value }))}
                />
                <datalist id="provider-options">
                  {PROVIDER_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </datalist>
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="base_url">Base URL</Label>
                <Input
                  id="base_url"
                  type="url"
                  placeholder="https://api.siliconflow.cn/v1"
                  value={form.base_url}
                  onChange={(e) => setForm((f) => ({ ...f, base_url: e.target.value }))}
                />
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="model">主模型</Label>
                  <Input
                    id="model"
                    placeholder="qwen-plus / deepseek-v4 …"
                    value={form.model}
                    onChange={(e) => setForm((f) => ({ ...f, model: e.target.value }))}
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="copilot_model">Copilot 快模型</Label>
                  <Input
                    id="copilot_model"
                    placeholder="实时辅助低延迟模型，留空用主模型"
                    value={form.copilot_model}
                    onChange={(e) => setForm((f) => ({ ...f, copilot_model: e.target.value }))}
                  />
                </div>
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="think_model">思考模型</Label>
                <Input
                  id="think_model"
                  placeholder="复盘/优化等重任务模型，留空用主模型"
                  value={form.think_model}
                  onChange={(e) => setForm((f) => ({ ...f, think_model: e.target.value }))}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="api_key">API Key</Label>
                <Input
                  id="api_key"
                  type="password"
                  autoComplete="new-password"
                  placeholder={
                    config?.has_api_key
                      ? `已配置（${config.api_key_masked}），留空保留`
                      : "sk-…"
                  }
                  value={form.api_key}
                  onChange={(e) => setForm((f) => ({ ...f, api_key: e.target.value }))}
                />
                <p className="text-xs text-faint">
                  保存后以加密形式存入数据库（Fernet，密钥来自 .env），页面只显示末 4 位掩码。
                </p>
              </div>
              <div className="flex items-center justify-between">
                {config?.updated_at ? (
                  <span className="text-xs text-faint">上次保存：{config.updated_at.slice(0, 19).replace("T", " ")}</span>
                ) : (
                  <span className="text-xs text-faint">尚未保存过数据库配置（当前生效：.env）</span>
                )}
                <Button type="submit" loading={saving}>
                  {saved && !saving ? <Check /> : null}
                  {saving ? "保存中…" : saved ? "已保存" : "保存配置"}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
