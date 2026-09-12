(() => {
  "use strict";

  const form = document.getElementById("voice-profile-form");
  if (!form) return;
  const recordButton = document.getElementById("voice-record-button");
  const fileInput = document.getElementById("voice-file-input");
  const saveButton = document.getElementById("voice-save-button");
  const deleteButton = document.getElementById("voice-delete-button");
  const preview = document.getElementById("voice-preview");
  const stateLabel = document.getElementById("voice-profile-state");
  const meta = document.getElementById("voice-profile-meta");
  const status = document.getElementById("voice-profile-status");
  let recorder = null;
  let stream = null;
  let chunks = [];
  let pendingFile = null;
  let stopTimer = null;

  function setStatus(message, error = false) {
    status.textContent = message;
    status.dataset.state = error ? "error" : "idle";
  }

  function setPending(blob, filename) {
    pendingFile = new File([blob], filename, { type: blob.type || "audio/webm" });
    preview.src = URL.createObjectURL(pendingFile);
    preview.hidden = false;
    saveButton.disabled = false;
    setStatus("语音已准备好，点击保存语音。" );
  }

  function renderProfile(profile) {
    const available = Boolean(profile?.available);
    stateLabel.textContent = available ? "已录入" : "尚未录入";
    stateLabel.dataset.state = available ? "ready" : "empty";
    deleteButton.hidden = !available;
    if (available) {
      preview.src = `${profile.audio_url}?t=${Date.now()}`;
      preview.hidden = false;
      meta.textContent = `${profile.original_filename || "语音样本"} · 可重新录制或替换`;
    } else {
      preview.removeAttribute("src");
      preview.hidden = true;
      meta.textContent = "建议录制 10–30 秒，安静环境下正常说话即可。";
    }
  }

  async function loadProfile() {
    try {
      const response = await fetch("/api/users/voice-profile");
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || "加载语音状态失败");
      renderProfile(payload.voice_profile);
    } catch (error) {
      setStatus(error.message, true);
    }
  }

  function stopRecording() {
    if (!recorder || recorder.state === "inactive") return;
    recorder.stop();
    clearTimeout(stopTimer);
    stream?.getTracks().forEach((track) => track.stop());
    recordButton.querySelector("span").textContent = "开始录音";
    recordButton.querySelector("i").className = "bx bx-microphone";
  }

  async function startRecording() {
    if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
      setStatus("当前浏览器不支持录音，请改用上传音频。", true);
      return;
    }
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      const mimeType = ["audio/webm;codecs=opus", "audio/ogg;codecs=opus", "audio/webm"].find((type) => MediaRecorder.isTypeSupported(type));
      recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      chunks = [];
      recorder.ondataavailable = (event) => event.data.size && chunks.push(event.data);
      recorder.onstop = () => {
        const blob = new Blob(chunks, { type: recorder.mimeType || "audio/webm" });
        setPending(blob, `voice-profile.${blob.type.includes("ogg") ? "ogg" : "webm"}`);
      };
      recorder.start();
      recordButton.querySelector("span").textContent = "停止录音";
      recordButton.querySelector("i").className = "bx bx-stop-circle";
      setStatus("正在录音，最长 30 秒。" );
      stopTimer = window.setTimeout(stopRecording, 30000);
    } catch (error) {
      stream?.getTracks().forEach((track) => track.stop());
      setStatus(`无法访问麦克风：${error.message || error}`, true);
    }
  }

  recordButton.addEventListener("click", () => (recorder?.state === "recording" ? stopRecording() : startRecording()));
  fileInput.addEventListener("change", () => {
    const file = fileInput.files?.[0];
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) {
      fileInput.value = "";
      setStatus("语音文件不能超过 10MB。", true);
      return;
    }
    setPending(file, file.name);
  });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!pendingFile) return setStatus("请先录音或选择音频文件。", true);
    saveButton.disabled = true;
    setStatus("正在保存…");
    const data = new FormData();
    data.append("audio", pendingFile, pendingFile.name);
    try {
      const response = await fetch("/api/users/voice-profile", { method: "POST", body: data });
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || "保存失败");
      pendingFile = null;
      fileInput.value = "";
      renderProfile(payload.voice_profile);
      setStatus("语音已保存。" );
    } catch (error) {
      setStatus(error.message, true);
      saveButton.disabled = false;
    }
  });
  deleteButton.addEventListener("click", async () => {
    if (!window.confirm("确定删除本人语音样本吗？")) return;
    deleteButton.disabled = true;
    try {
      const response = await fetch("/api/users/voice-profile", { method: "DELETE" });
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || "删除失败");
      renderProfile(payload.voice_profile);
      setStatus("语音样本已删除。" );
    } catch (error) {
      setStatus(error.message, true);
    } finally {
      deleteButton.disabled = false;
    }
  });
  loadProfile();
})();
