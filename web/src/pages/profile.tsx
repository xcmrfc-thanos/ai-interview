import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Mic, Play, Square, Trash2 } from "lucide-react";
import { PageHeader, StatusBadge } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";

type UserProfile = {
  full_name?: string;
  email?: string;
  phone?: string;
  expected_position?: string;
  expected_salary?: number | string;
  work_years?: number | string;
};

type VoiceProfile = {
  available: boolean;
  audio_url?: string | null;
  original_filename?: string | null;
  updated_at?: string | null;
};

const MAX_RECORD_SECONDS = 30;

export function ProfilePage() {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [voice, setVoice] = useState<VoiceProfile | null>(null);
  const [message, setMessage] = useState("");
  const [saving, setSaving] = useState(false);

  const [recording, setRecording] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [uploadingVoice, setUploadingVoice] = useState(false);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const previewUrlRef = useRef<string | null>(null);
  const audioElRef = useRef<HTMLAudioElement | null>(null);

  const load = useCallback(() => {
    api.get<{ user: UserProfile }>("/api/users").then((data) => setUser(data.user)).catch(() => undefined);
    api
      .get<{ voice_profile: VoiceProfile }>("/api/users/voice-profile")
      .then((data) => setVoice(data.voice_profile))
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    load();
    // 卸载时释放录音资源与 Object URL（对齐 U5 资源清理）
    return () => {
      releaseRecording();
      if (previewUrlRef.current) {
        URL.revokeObjectURL(previewUrlRef.current);
        previewUrlRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function releaseRecording() {
    if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    }
    recorderRef.current = null;
  }

  async function handleSave(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const body: Record<string, string> = {};
    for (const key of ["full_name", "phone", "expected_position", "work_years", "expected_salary"]) {
      const v = String(form.get(key) ?? "").trim();
      if (v) body[key] = v;
    }
    const password = String(form.get("password") ?? "");
    if (password) body.password = password;
    setSaving(true);
    setMessage("");
    try {
      await api.post("/api/users/update", body);
      setMessage("已保存");
      load();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  }

  async function startRecording() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      chunksRef.current = [];
      const recorder = new MediaRecorder(stream);
      recorderRef.current = recorder;
      recorder.ondataavailable = (e) => {
        if (e.data.size) chunksRef.current.push(e.data);
      };
      recorder.onstop = () => void uploadRecording();
      recorder.start();
      setRecording(true);
      setElapsed(0);
      timerRef.current = setInterval(() => {
        setElapsed((s) => {
          if (s + 1 >= MAX_RECORD_SECONDS) {
            stopRecording();
            return MAX_RECORD_SECONDS;
          }
          return s + 1;
        });
      }, 1000);
    } catch {
      setMessage("无法访问麦克风，请检查浏览器权限");
    }
  }

  function stopRecording() {
    if (recorderRef.current && recorderRef.current.state !== "inactive") {
      recorderRef.current.stop();
    }
    setRecording(false);
    releaseRecording();
  }

  async function uploadRecording() {
    if (!chunksRef.current.length) return;
    const blob = new Blob(chunksRef.current, { type: chunksRef.current[0]?.type || "audio/webm" });
    const data = new FormData();
    data.set("audio", new File([blob], "voice-profile.webm", { type: blob.type }));
    setUploadingVoice(true);
    try {
      const res = await api.upload<{ voice_profile: VoiceProfile }>("/api/users/voice-profile", data);
      setVoice(res.voice_profile);
      setMessage("语音档案已更新");
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "语音档案上传失败");
    } finally {
      setUploadingVoice(false);
      chunksRef.current = [];
    }
  }

  async function deleteVoice() {
    try {
      const res = await api.delete<{ voice_profile: VoiceProfile }>("/api/users/voice-profile");
      setVoice(res.voice_profile);
      if (previewUrlRef.current) {
        URL.revokeObjectURL(previewUrlRef.current);
        previewUrlRef.current = null;
      }
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "删除失败");
    }
  }

  return (
    <>
      <PageHeader title="个人设置" description="资料、密码与声纹档案（自动模式麦克风角色识别依赖声纹档案）。" />
      <div className="grid gap-4 px-4 py-5 md:px-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>基本资料</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="grid grid-cols-1 gap-3 sm:grid-cols-2" onSubmit={handleSave}>
              <Field label="姓名" name="full_name" defaultValue={user?.full_name ?? ""} />
              <Field label="电话" name="phone" defaultValue={user?.phone ?? ""} />
              <Field label="期望岗位" name="expected_position" defaultValue={user?.expected_position ?? ""} />
              <Field label="工作年限" name="work_years" defaultValue={String(user?.work_years ?? "")} />
              <Field
                label="期望薪资"
                name="expected_salary"
                defaultValue={user?.expected_salary ? String(user.expected_salary) : ""}
              />
              <Field label="新密码（可选）" name="password" type="password" defaultValue="" />
              <div className="flex items-center gap-3 sm:col-span-2">
                <Button type="submit" disabled={saving}>
                  {saving ? "保存中…" : "保存"}
                </Button>
                {message ? <span className="text-sm text-muted-foreground">{message}</span> : null}
              </div>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle>语音档案</CardTitle>
              <StatusBadge state={voice?.available ? "confirmed" : "idle"} label={voice?.available ? "已录入" : "未录入"} />
            </div>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <p className="text-sm text-muted-foreground">
              录制约 30 秒自然说话（自我介绍即可）。实时 Copilot 自动模式会用它做声纹比对，区分「我」与「面试官」。
            </p>
            <div className="flex flex-wrap items-center gap-2">
              {!recording ? (
                <Button size="sm" onClick={startRecording} disabled={uploadingVoice}>
                  <Mic />
                  {voice?.available ? "重新录制" : "开始录制"}
                </Button>
              ) : (
                <Button size="sm" variant="destructive" onClick={stopRecording}>
                  <Square />
                  停止（{elapsed}s / {MAX_RECORD_SECONDS}s）
                </Button>
              )}
              {uploadingVoice ? <span className="text-sm text-faint">上传中…</span> : null}
              {voice?.available && voice.audio_url ? (
                <>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => {
                      if (!audioElRef.current) return;
                      audioElRef.current.play();
                    }}
                  >
                    <Play />
                    试听
                  </Button>
                  <Button size="sm" variant="ghost" onClick={deleteVoice}>
                    <Trash2 />
                    删除
                  </Button>
                </>
              ) : null}
            </div>
            {recording ? (
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-subtle">
                <div
                  className="h-full bg-primary transition-[width] duration-1000"
                  style={{ width: `${(elapsed / MAX_RECORD_SECONDS) * 100}%` }}
                />
              </div>
            ) : null}
            {voice?.audio_url ? (
              <audio ref={audioElRef} src={voice.audio_url} className="hidden" preload="none" />
            ) : null}
          </CardContent>
        </Card>
      </div>
    </>
  );
}

function Field({
  label,
  name,
  defaultValue,
  type = "text",
}: {
  label: string;
  name: string;
  defaultValue?: string;
  type?: string;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={name}>{label}</Label>
      <Input id={name} name={name} type={type} defaultValue={defaultValue} />
    </div>
  );
}
