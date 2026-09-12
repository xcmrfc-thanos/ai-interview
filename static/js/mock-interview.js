(() => {
  "use strict";

  const root = document.querySelector("[data-mock-root]");
  if (!root) return;
  const planId = Number(root.dataset.planId) || null;
  const storageKey = planId ? `mockInterview.${planId}` : "mockInterview.none";
  const state = {
    interviewId: null,
    interview: null,
    submitting: false,
    recognition: null,
    voiceActive: false,
    voiceBase: "",
    voiceText: "",
  };
  const $ = (id) => document.getElementById(id);

  async function requestJson(url, options = {}) {
    const response = await fetch(url, {
      headers: { "Content-Type": "application/json", ...(options.headers || {}) },
      ...options,
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.message || `请求失败 (${response.status})`);
    return payload;
  }

  function setStatus(message, stateName = "") {
    const status = $("mock-status");
    status.textContent = message;
    status.dataset.state = stateName;
  }

  function renderInterview(interview) {
    state.interview = interview;
    state.interviewId = interview.mock_interview_id;
    sessionStorage.setItem(storageKey, String(state.interviewId));
    const current = interview.current_question;
    const progress = interview.question_count ? interview.current_turn_number / interview.question_count : 0;
    $("mock-progress-label").textContent = `${Math.min(interview.current_turn_number, interview.question_count)} / ${interview.question_count} 题`;
    $("mock-progress-bar").style.width = `${Math.min(progress * 100, 100)}%`;
    $("mock-question-number").textContent = current ? `第 ${current.turn_number} 题` : "本轮已完成";
    $("mock-question").textContent = current?.question || "本轮题目已经完成，可以结束并进入复盘。";
    $("mock-answer").disabled = !current;
    $("mock-submit").disabled = !current;
    $("mock-voice-toggle").disabled = !current;
    $("mock-end").disabled = false;
    $("mock-start").hidden = true;
    if (!current) setStatus("模拟面试已完成，结束后可生成统一复盘。", "");
  }

  function updateAnswerCount() {
    $("mock-answer-count").textContent = `${$("mock-answer").value.trim().length} 字`;
  }

  function setVoiceButton(active) {
    const button = $("mock-voice-toggle");
    button.classList.toggle("is-recording", active);
    button.querySelector("i").className = active ? "bx bx-stop" : "bx bx-microphone";
    button.querySelector("span").textContent = active ? "停止录音" : "开始录音";
    button.setAttribute("aria-pressed", String(active));
  }

  function stopVoiceInput() {
    const wasActive = state.voiceActive;
    state.voiceActive = false;
    try { state.recognition?.stop(); } catch (_error) { /* 识别已结束时浏览器可能重复抛错 */ }
    setVoiceButton(false);
    return wasActive ? new Promise((resolve) => window.setTimeout(resolve, 120)) : Promise.resolve();
  }

  function startVoiceInput() {
    const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!Recognition) {
      setStatus("当前浏览器不支持语音输入，请直接打字回答。", "error");
      return;
    }
    if (state.voiceActive) {
      stopVoiceInput();
      setStatus("录音已停止，可以检查文字后提交。", "");
      return;
    }
    const textarea = $("mock-answer");
    state.voiceBase = textarea.value.trim();
    state.voiceText = "";
    const recognition = new Recognition();
    recognition.lang = "zh-CN";
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.onstart = () => {
      state.voiceActive = true;
      setVoiceButton(true);
      setStatus("正在录音，结束后可编辑识别文字。", "active");
    };
    recognition.onresult = (event) => {
      let interim = "";
      for (let index = event.resultIndex; index < event.results.length; index += 1) {
        const text = event.results[index][0].transcript.trim();
        if (event.results[index].isFinal) state.voiceText = `${state.voiceText}${text}`;
        else interim = `${interim}${text}`;
      }
      const parts = [state.voiceBase, state.voiceText, interim].filter(Boolean);
      textarea.value = parts.join(" ");
      updateAnswerCount();
    };
    recognition.onerror = (event) => {
      state.voiceActive = false;
      setVoiceButton(false);
      setStatus(`语音输入失败：${event.error || "请重试"}`, "error");
    };
    recognition.onend = () => {
      if (state.voiceActive) {
        try { recognition.start(); } catch (_error) { /* 浏览器可能仍在结束上一段识别 */ }
      }
    };
    state.recognition = recognition;
    try {
      recognition.start();
    } catch (error) {
      state.voiceActive = false;
      setVoiceButton(false);
      setStatus(`无法开始语音输入：${error.message}`, "error");
    }
  }

  function renderFeedback(evaluation) {
    const feedback = $("mock-feedback");
    feedback.replaceChildren();
    const names = {
      fact_consistency: "事实一致性",
      job_relevance: "岗位相关性",
      completeness: "回答完整性",
      expression: "表达清晰度",
    };
    const scoreGrid = document.createElement("div");
    scoreGrid.className = "score-grid";
    Object.entries(evaluation.scores || {}).forEach(([key, value]) => {
      const item = document.createElement("div");
      item.className = "score-item";
      const label = document.createElement("span");
      label.textContent = names[key] || key;
      const score = document.createElement("strong");
      score.textContent = Math.round(value);
      item.append(label, score);
      scoreGrid.appendChild(item);
    });
    feedback.appendChild(scoreGrid);
    appendFeedbackGroup(feedback, "做得好的地方", evaluation.strengths || []);
    appendFeedbackGroup(feedback, "下一题前可改进", evaluation.improvements || []);
    appendFeedbackGroup(feedback, "参考要点", evaluation.reference_points || []);
  }

  function appendFeedbackGroup(parent, title, items) {
    if (!items.length) return;
    const group = document.createElement("section");
    group.className = "feedback-group";
    const heading = document.createElement("h3");
    heading.textContent = title;
    const list = document.createElement("ul");
    items.forEach((value) => {
      const item = document.createElement("li");
      item.textContent = value;
      list.appendChild(item);
    });
    group.append(heading, list);
    parent.appendChild(group);
  }

  async function createInterview() {
    if (!planId || state.submitting) return;
    state.submitting = true;
    $("mock-start").disabled = true;
    setStatus("正在根据岗位和简历生成题目…");
    try {
      const payload = await fetch("/api/mock-interviews", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ plan_id: planId }),
      }).then(async (response) => {
        const data = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(data.message || "创建模拟面试失败");
        return data;
      });
      renderInterview(payload.interview);
      setStatus("题目已生成，点击麦克风开始回答，或直接打字。");
      $("mock-answer").focus();
    } catch (error) {
      setStatus(error.message, "error");
      $("mock-start").disabled = false;
    } finally {
      state.submitting = false;
    }
  }

  async function submitAnswer() {
    const textarea = $("mock-answer");
    if (!state.interviewId || state.submitting) {
      setStatus("请先开始模拟面试。", "error");
      return;
    }
    if (!textarea.value.trim()) {
      setStatus("请先填写回答。", "error");
      return;
    }
    state.submitting = true;
    await stopVoiceInput();
    const answer = textarea.value.trim();
    $("mock-submit").disabled = true;
    setStatus("正在评估回答…");
    try {
      const payload = await requestJson(`/api/mock-interviews/${state.interviewId}/answers`, {
        method: "POST",
        body: JSON.stringify({ answer }),
      });
      renderFeedback(payload.evaluation);
      renderInterview(payload.interview);
      textarea.value = "";
      $("mock-answer-count").textContent = "0 字";
      if (payload.interview.current_question) textarea.focus();
      setStatus(payload.interview.current_question ? "反馈已生成，继续下一题。" : "所有题目已完成。");
    } catch (error) {
      textarea.value = answer;
      setStatus(`${error.message}，原回答已保留，可重试。`, "error");
      $("mock-submit").disabled = false;
    } finally {
      state.submitting = false;
    }
  }

  async function endInterview() {
    if (!state.interviewId) return;
    await stopVoiceInput();
    try {
      const payload = await requestJson(`/api/mock-interviews/${state.interviewId}/end`, { method: "POST" });
      renderInterview(payload.interview);
      sessionStorage.removeItem(storageKey);
      $("mock-answer").disabled = true;
      $("mock-submit").disabled = true;
      $("mock-end").disabled = true;
      setStatus("模拟面试已结束，可前往复盘记录查看总结。");
    } catch (error) {
      setStatus(error.message, "error");
    }
  }

  async function restoreInterview() {
    const interviewId = sessionStorage.getItem(storageKey);
    if (!interviewId) return;
    try {
      const payload = await requestJson(`/api/mock-interviews/${interviewId}`);
      renderInterview(payload.interview);
      if (["ended", "completed"].includes(payload.interview.status)) sessionStorage.removeItem(storageKey);
    } catch (_error) {
      sessionStorage.removeItem(storageKey);
    }
  }

  $("mock-answer").addEventListener("input", (event) => {
    updateAnswerCount();
  });
  $("mock-start").addEventListener("click", createInterview);
  $("mock-voice-toggle").addEventListener("click", startVoiceInput);
  $("mock-submit").addEventListener("click", submitAnswer);
  $("mock-end").addEventListener("click", endInterview);
  restoreInterview();
})();
