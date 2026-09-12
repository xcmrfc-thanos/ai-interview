import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { AuthLayout } from "@/components/auth-layout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api, ApiError } from "@/lib/api";

export function RegisterPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({
    email: "",
    password: "",
    confirm_password: "",
    full_name: "",
  });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  function update(key: keyof typeof form) {
    return (e: React.ChangeEvent<HTMLInputElement>) =>
      setForm((f) => ({ ...f, [key]: e.target.value }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError("");
    if (form.password !== form.confirm_password) {
      setError("两次输入的密码不一致");
      return;
    }
    setSubmitting(true);
    try {
      await api.post("/api/register", { ...form, role: "applicant" });
      navigate("/login");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "注册失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="注册"
      description="创建你的求职者账号"
      footer={
        <>
          已有账号？{" "}
          <Link to="/login" className="font-medium text-primary hover:underline">
            直接登录
          </Link>
        </>
      }
    >
      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="email">邮箱</Label>
          <Input id="email" type="email" required autoComplete="email" value={form.email} onChange={update("email")} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="full_name">姓名</Label>
          <Input id="full_name" required value={form.full_name} onChange={update("full_name")} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="password">密码</Label>
          <Input
            id="password"
            type="password"
            required
            autoComplete="new-password"
            value={form.password}
            onChange={update("password")}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="confirm_password">确认密码</Label>
          <Input
            id="confirm_password"
            type="password"
            required
            autoComplete="new-password"
            value={form.confirm_password}
            onChange={update("confirm_password")}
          />
        </div>
        {error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        ) : null}
        <Button type="submit" variant="gradient" loading={submitting}>
          注册
        </Button>
      </form>
    </AuthLayout>
  );
}
