// Copilot 实时会话 hook：移植自 Java 版 copilot-session.js（契约 docs/api-contract.md §6）
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, API_BASE } from "@/lib/api";
import { useSmoothText } from "@/lib/use-smooth-text";
import { CONN_LABELS, type ConnStateKey } from "@/features/copilot/conn-state";
import { PcmStreamRecorder } from "@/features/copilot/audio/pcm-stream-recorder";
import {
  createTranscriptBuffer,
  type TranscriptPayload,
} from "@/features/copilot/realtime/transcript-buffer";

type StaleCheckable = {
  turn_id?: number | string | null;
  turn?: number | string | null;
  seq?: number | null;
  sequence?: number | null;
};
/** 转写事件：契约 §6 transcript_partial/final 载荷。 */
type TranscriptEvent = WsEvent &
  StaleCheckable & { text?: unknown; source?: unknown; speaker?: unknown };
import { createAnswerBuffer } from "@/features/copilot/realtime/answer-buffer";
import { createFeedScroll } from "@/features/copilot/realtime/feed-scroll";

const TARGET_SAMPLE_RATE = 16000;
const FRAME_SIZE = 1600;
/** 转写气泡 DOM 上限，超出时丢弃最早消息。 */
const MAX_TRANSCRIPT_MESSAGES = 200;
/** 约 500ms 音频队列上限（16kHz mono PCM16，100ms/帧 ≈ 3200B/帧）。 */
const WS_BUFFER_MAX_BYTES = 16000;
/** v2 帧来源码：0=混合，1=麦克风，2=系统声音。 */
const SOURCE_CODES: Record<TrackKey, number> = { microphone: 1, system: 2, mixed: 0 };

export type TrackKey = "microphone" | "system" | "mixed";
export type CaptureMode = "auto" | "local" | "remote";
type Side = "candidate" | "interviewer";

export type TranscriptMessage = { text: string; side: Side; name: string; time: string };

type WsEvent = Record<string, unknown> & { event: string };

export function useCopilotSession(planId: number | null) {
  const wsRef = useRef<WebSocket | null>(null);
  const mediaStreamsRef = useRef<Map<TrackKey, MediaStream>>(new Map());
  const recordersRef = useRef<PcmStreamRecorder[]>([]);
  const audioSequenceRef = useRef(0);
  const audioProtocolRef = useRef(1);
  const wsWasConnectedRef = useRef(false);
  const wsClosedByUserRef = useRef(false);
  const sessionEndedByUserRef = useRef(false);
  const lastTranscribeAtRef = useRef(0);
  /** 已确认 final 片段前缀，与 current 合并后构成整句。 */
  const transcriptPrefixRef = useRef("");

  const [connStateKey, setConnStateKey] = useState<ConnStateKey>("idle");
  const [wsError, setWsError] = useState("");
  const [recording, setRecording] = useState(false);
  const [captureBackend, setCaptureBackend] = useState("");
  const [messages, setMessages] = useState<TranscriptMessage[]>([]);
  const [current, setCurrent] = useState<{ text: string; side: Side; name: string }>({
    text: "",
    side: "interviewer",
    name: "面试官",
  });
  const [currentTime, setCurrentTime] = useState("");
  const [answerPoints, setAnswerPoints] = useState<string[]>([]);
  const [referenceAnswer, setReferenceAnswer] = useState("");
  const [answering, setAnswering] = useState(false);
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [captureMode, setCaptureMode] = useState<CaptureMode>("auto");
  const [captureStatus, setCaptureStatus] = useState("");
  const [sourceLabel, setSourceLabel] = useState("麦克风");
  const [speakerLabel, setSpeakerLabel] = useState("自动");
  const [feedPendingCount, setFeedPendingCount] = useState(0);
  const [ariaLiveMessage, setAriaLiveMessage] = useState("");
  const [sessionEndedByUser, setSessionEndedByUser] = useState(false);
  const [endedAt, setEndedAt] = useState<string | null>(null);

  // 转写气泡与参考回答做逐字平滑：底层流仍是整块到达，上屏时逐字追赶
  const smoothCurrentText = useSmoothText(current.text);
  const smoothReferenceAnswer = useSmoothText(referenceAnswer);
  /** 平滑动画尚未追平目标时为 true，驱动打字光标/流式角标 */
  const currentStreaming = smoothCurrentText.length < current.text.length;
  const referenceStreaming = answering || smoothReferenceAnswer.length < referenceAnswer.length;

  const feedRef = useRef<HTMLDivElement | null>(null);

  const displayMediaSupported = useMemo(
    () =>
      typeof window !== "undefined" &&
      window.isSecureContext &&
      !!navigator.mediaDevices?.getDisplayMedia,
    [],
  );

  const announceLive = useCallback((message: string) => {
    if (message) setAriaLiveMessage(message);
  }, []);

  const feedScroll = useMemo(
    () =>
      createFeedScroll({
        stickThresholdPx: 48,
        getFeed: () => feedRef.current,
        onPendingChange: setFeedPendingCount,
      }),
    [],
  );

  const scrollFeed = useCallback(
    (isFinal: boolean) => {
      feedScroll.onContentUpdate(isFinal);
    },
    [feedScroll],
  );

  const scrollFeedToBottom = useCallback(() => feedScroll.scrollToBottom(), [feedScroll]);
  const onFeedScroll = useCallback(() => feedScroll.onFeedScroll(), [feedScroll]);

  const nowTime = () => {
    const d = new Date();
    const pad = (n: number) => (n < 10 ? `0${n}` : String(n));
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  };

  const markCurrentTime = useCallback(() => {
    lastTranscribeAtRef.current = Date.now();
    setCurrentTime(nowTime());
  }, []);

  const setConnState = useCallback((key: ConnStateKey) => setConnStateKey(key), []);

  const modeHintText = useCallback((m: CaptureMode) => {
    if (m === "local") return "只采集本机麦克风，全部识别为本人。";
    if (m === "remote") return "系统声音与麦克风均识别为面试官；无系统音频时回退麦克风。";
    return "系统声音=面试官；麦克风按个人中心声纹实时比对，匹配成功=本人，未录入或匹配失败=面试官。";
  }, []);

  const sourceText = useCallback((s?: string | null) => {
    if (s === "display" || s === "system") return "系统声音";
    if (s === "mixed") return "混合";
    return "麦克风";
  }, []);

  const speakerText = useCallback((s?: string | null) => {
    return s === "candidate" ? "我" : s === "interviewer" ? "面试官" : "自动";
  }, []);

  const speakerForMode = useCallback((m: CaptureMode): string | null => {
    return m === "local" ? "candidate" : m === "remote" ? "interviewer" : null;
  }, []);

  const sideForSpeaker = useCallback((sp?: string | null): Side => {
    return sp === "candidate" ? "candidate" : "interviewer";
  }, []);

  const backendSourceForTrack = useCallback((trackKey: TrackKey): string => {
    if (trackKey === "microphone") return "local";
    if (trackKey === "system") return "remote";
    return "mixed";
  }, []);

  // ---- 转写文本合并与单调性（对齐 Java 版） ----

  const trimMessages = useCallback((list: TranscriptMessage[]) => {
    if (list.length > MAX_TRANSCRIPT_MESSAGES) {
      return list.slice(list.length - MAX_TRANSCRIPT_MESSAGES);
    }
    return list;
  }, []);

  const finalizeCurrentUtterance = useCallback(
    (completedText: string | null, side: Side) => {
      setCurrent((cur) => {
        const text = String(completedText ?? cur.text ?? transcriptPrefixRef.current ?? "").trim();
        if (!text) {
          transcriptPrefixRef.current = "";
          setCurrentTime("");
          return { text: "", side: "interviewer", name: "面试官" };
        }
        const time = nowTime();
        setMessages((list) =>
          trimMessages([...list, { text, side, name: side === "candidate" ? "我" : "面试官", time }]),
        );
        announceLive(`${side === "candidate" ? "我" : "面试官"}：${text}`);
        transcriptPrefixRef.current = "";
        setCurrentTime("");
        scrollFeed(true);
        return { text: "", side: "interviewer", name: "面试官" };
      });
    },
    [announceLive, scrollFeed, trimMessages],
  );

  const commitCurrentIfSpeakerChanged = useCallback(
    (nextSide: Side) => {
      setCurrent((cur) => {
        if (cur.text && cur.side !== nextSide) {
          // 延迟到当前渲染周期外落盘，避免嵌套 setState
          queueMicrotask(() => finalizeCurrentUtterance(cur.text, cur.side));
        }
        return cur;
      });
    },
    [finalizeCurrentUtterance],
  );

  function mergeTranscriptText(base: string, next: string): string {
    const left = String(base || "").trim();
    const right = String(next || "").trim();
    if (!left) return right;
    if (!right || left.endsWith(right)) return left;
    if (right.startsWith(left)) return right;
    const longestOverlap = Math.min(left.length, right.length);
    for (let size = longestOverlap; size > 0; size -= 1) {
      if (left.slice(-size) === right.slice(0, size)) {
        return left + right.slice(size);
      }
    }
    const needsSpace = /[A-Za-z0-9]$/.test(left) && /^[A-Za-z0-9]/.test(right);
    return left + (needsSpace ? " " : "") + right;
  }

  function isMonotonicTranscriptUpdate(previous: string, candidate: string): boolean {
    const prev = String(previous || "").trim();
    const next = String(candidate || "").trim();
    return !prev || next === prev || next.indexOf(prev) === 0;
  }

  const applyTranscriptUpdate = useCallback(
    (payload: { data: WsEvent; isFinal: boolean; side: Side }) => {
      const data = payload.data;
      const side = payload.side;
      commitCurrentIfSpeakerChanged(side);
      if (payload.isFinal) {
        markCurrentTime();
      } else if (Date.now() - lastTranscribeAtRef.current > 1500) {
        markCurrentTime();
      }
      if (data.source) {
        setSourceLabel(sourceText(String(data.source)));
      }
      if (data.speaker) {
        setSpeakerLabel(speakerText(String(data.speaker)));
      }
      const segmentText = String(data.text ?? "");
      if (payload.isFinal) {
        const mergedDisplay = mergeTranscriptText(current.text, segmentText);
        transcriptPrefixRef.current = mergeTranscriptText(transcriptPrefixRef.current, mergedDisplay);
        setCurrent({
          text: transcriptPrefixRef.current,
          side,
          name: side === "candidate" ? "我" : "面试官",
        });
      } else {
        const candidate = mergeTranscriptText(transcriptPrefixRef.current, segmentText);
        if (!isMonotonicTranscriptUpdate(current.text, candidate)) {
          return;
        }
        setCurrent({ text: candidate, side, name: side === "candidate" ? "我" : "面试官" });
      }
      scrollFeed(payload.isFinal);
    },
    [commitCurrentIfSpeakerChanged, current.text, markCurrentTime, scrollFeed, sourceText, speakerText],
  );

  const transcriptBuffer = useMemo(
    () =>
      createTranscriptBuffer<TranscriptEvent>({
        minPartialIntervalMs: 100,
        onApply: applyTranscriptUpdate,
      }),
    [applyTranscriptUpdate],
  );

  const answerBuffer = useMemo(
    () =>
      createAnswerBuffer({
        minDeltaIntervalMs: 50,
        onAppendText: (chunk) => setReferenceAnswer((prev) => prev + chunk),
        onApplyPoints: (text) => {
          try {
            setAnswerPoints(JSON.parse(text));
          } catch {
            setAnswerPoints([text]);
          }
        },
      }),
    [],
  );

  const resetRealtimeBuffers = useCallback(() => {
    transcriptPrefixRef.current = "";
    transcriptBuffer.reset();
    answerBuffer.reset();
    feedScroll.reset();
  }, [answerBuffer, feedScroll, transcriptBuffer]);

  // ---- 采集 ----

  const getMicrophoneStream = () =>
    navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      video: false,
    });

  const acquireStreams = useCallback(
    async (mode: CaptureMode): Promise<Map<TrackKey, MediaStream>> => {
      const streams = new Map<TrackKey, MediaStream>();
      if (mode === "local" || mode === "auto" || mode === "remote") {
        try {
          const media = await getMicrophoneStream();
          streams.set("microphone", media);
        } catch {
          // 麦克风被拒时继续尝试其他轨
        }
      }
      if (mode === "local") return streams;

      const ensureMixedFallback = async () => {
        if (!streams.has("microphone")) {
          try {
            const media = await getMicrophoneStream();
            streams.set("mixed", media);
          } catch {
            // 无可用音频轨
          }
        }
        return streams;
      };

      if (!navigator.mediaDevices?.getDisplayMedia) {
        if (mode === "remote") return ensureMixedFallback();
        return streams;
      }
      try {
        const displayStream = await navigator.mediaDevices.getDisplayMedia({ audio: true, video: true });
        if (displayStream.getAudioTracks().length > 0) {
          streams.set("system", new MediaStream(displayStream.getAudioTracks()));
          displayStream.getVideoTracks().forEach((track) => track.stop());
        } else {
          displayStream.getTracks().forEach((track) => track.stop());
        }
        if (mode === "remote" && !streams.has("system") && !streams.has("microphone")) {
          return ensureMixedFallback();
        }
        return streams;
      } catch {
        if (mode === "remote") return ensureMixedFallback();
        return streams;
      }
    },
    [],
  );

  const refreshCaptureStatus = useCallback(
    (mode: CaptureMode, streams: Map<TrackKey, MediaStream>) => {
      const keys = [...streams.keys()];
      const hasSystem = keys.includes("system");
      const hasMic = keys.includes("microphone");
      const hint = modeHintText(mode);
      if (hasSystem && hasMic) {
        setCaptureStatus(`${hint}（麦克风 + 系统声音）`);
        setSourceLabel("麦克风 + 系统");
      } else if (hasSystem) {
        setCaptureStatus(`${hint}（系统声音）`);
        setSourceLabel("系统声音");
      } else if (keys.includes("mixed")) {
        setCaptureStatus(`${hint}（混合麦克风，系统音频不可用）`);
        setSourceLabel("混合");
      } else {
        setCaptureStatus(`${hint}（麦克风）`);
        setSourceLabel("麦克风");
      }
      setSpeakerLabel(speakerText(speakerForMode(mode) ?? "auto"));
    },
    [modeHintText, speakerForMode, speakerText],
  );

  // ---- WS 发送 ----

  const wsSend = useCallback((obj: Record<string, unknown>) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(obj));
    }
  }, []);

  const sendPcmFrame = useCallback((pcm: Int16Array, trackKey: TrackKey) => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (ws.bufferedAmount > WS_BUFFER_MAX_BYTES) {
      // 网络阻塞时丢弃音频帧，避免队列无限膨胀
      return;
    }
    if (audioProtocolRef.current >= 2) {
      const merged = new Uint8Array(8 + pcm.byteLength);
      const view = new DataView(merged.buffer);
      view.setUint8(0, 0x41);
      view.setUint8(1, 0x49);
      view.setUint8(2, 2);
      view.setUint8(3, SOURCE_CODES[trackKey] ?? 0);
      view.setUint32(4, audioSequenceRef.current++, true);
      merged.set(new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength), 8);
      ws.send(merged.buffer);
    } else {
      ws.send(pcm.buffer);
    }
  }, []);

  const stopRecording = useCallback(() => {
    recordersRef.current.forEach((recorder) => recorder.stop());
    recordersRef.current = [];
    mediaStreamsRef.current.forEach((stream) => {
      stream.getTracks().forEach((track) => track.stop());
    });
    mediaStreamsRef.current = new Map();
    setRecording(false);
    setCaptureBackend("");
    setCaptureStatus((status) => status || "");
  }, []);

  const startAllRecorders = useCallback(() => {
    const tasks: Promise<void>[] = [];
    mediaStreamsRef.current.forEach((stream, trackKey) => {
      const recorder = new PcmStreamRecorder(stream, {
        sampleRate: TARGET_SAMPLE_RATE,
        frameSize: FRAME_SIZE,
        stopTracks: false,
        onFrameRecorded: (frameBuffer) => sendPcmFrame(new Int16Array(frameBuffer), trackKey),
        onBackend: (backend) => setCaptureBackend((prev) => (prev ? `${prev}+${backend}` : backend)),
      });
      recordersRef.current.push(recorder);
      tasks.push(recorder.start());
    });
    return Promise.all(tasks).then(() => {
      setRecording(true);
      refreshCaptureStatus(captureMode, mediaStreamsRef.current);
    });
  }, [captureMode, refreshCaptureStatus, sendPcmFrame]);

  // ---- WS 事件处理 ----

  const handleTranscriptEvent = useCallback(
    (data: WsEvent) => {
      const transcript = data as TranscriptEvent;
      const side = sideForSpeaker(
        transcript.speaker ? String(transcript.speaker) : "interviewer",
      );
      const payload: TranscriptPayload<TranscriptEvent> = {
        data: transcript,
        isFinal: transcript.event === "transcript_final",
        side,
      };
      if (payload.isFinal) {
        transcriptBuffer.pushFinal(payload);
      } else {
        transcriptBuffer.pushPartial(payload);
      }
    },
    [sideForSpeaker, transcriptBuffer],
  );

  const handleUtteranceCompleted = useCallback(
    (data: WsEvent) => {
      const side = sideForSpeaker(data.speaker ? String(data.speaker) : "interviewer");
      commitCurrentIfSpeakerChanged(side);
      setCurrent((cur) => {
        const completed = mergeTranscriptText(
          mergeTranscriptText(transcriptPrefixRef.current, cur.text),
          String(data.text ?? ""),
        );
        queueMicrotask(() => finalizeCurrentUtterance(completed, side));
        return cur;
      });
      if (side === "interviewer") {
        setAnswering(true);
        setAnswerPoints(["正在生成回答…"]);
        setReferenceAnswer("正在组织参考回答…");
      }
    },
    [commitCurrentIfSpeakerChanged, finalizeCurrentUtterance, sideForSpeaker],
  );

  const handleWsEvent = useCallback(
    (data: WsEvent) => {
      switch (data.event) {
        case "transcript_partial":
        case "transcript_final":
          handleTranscriptEvent(data);
          break;
        case "utterance_completed":
          handleUtteranceCompleted(data);
          break;
        case "speaker_changed": {
          if (data.speaker) {
            setSpeakerLabel(speakerText(String(data.speaker)));
            commitCurrentIfSpeakerChanged(sideForSpeaker(String(data.speaker)));
          }
          if (data.source) {
            setSourceLabel(sourceText(String(data.source)));
          }
          break;
        }
        case "started":
        case "reconnected": {
          if (data.audio_protocol) {
            audioProtocolRef.current = Number(data.audio_protocol);
          }
          audioSequenceRef.current = 0;
          setConnState("connected");
          startAllRecorders().catch((err: Error) => {
            setWsError(`采集启动失败: ${err.message || err}`);
          });
          break;
        }
        case "ending":
          setConnState("ending");
          break;
        case "paused":
          setConnState("paused");
          break;
        case "resumed":
          setConnState("connected");
          break;
        case "ended":
          setConnState("ended");
          setEndedAt(new Date().toISOString());
          break;
        case "answer_queued":
          setAnswering(true);
          break;
        case "answer_started":
          answerBuffer.reset();
          setAnswering(true);
          setAnswerPoints([]);
          setReferenceAnswer("");
          if (Array.isArray(data.answer_points)) {
            setAnswerPoints((data.answer_points as unknown[]).map(String));
          }
          break;
        case "answer_delta":
          if (data.text) {
            answerBuffer.pushDelta(String(data.text));
          }
          break;
        case "answer_completed":
          answerBuffer.flushNow();
          setAnswering(false);
          if (Array.isArray(data.answer_points) && (data.answer_points as unknown[]).length) {
            setAnswerPoints((data.answer_points as unknown[]).map(String));
          }
          if (typeof data.reference_answer === "string") {
            setReferenceAnswer(String(data.reference_answer));
          }
          break;
        case "error": {
          const msg = String(data.message ?? data.text ?? "错误");
          setWsError(msg);
          announceLive(`连接错误：${msg}`);
          break;
        }
        default:
          break;
      }
    },
    [
      announceLive,
      answerBuffer,
      commitCurrentIfSpeakerChanged,
      handleTranscriptEvent,
      handleUtteranceCompleted,
      setConnState,
      sideForSpeaker,
      sourceText,
      speakerText,
      startAllRecorders,
    ],
  );

  // ---- 连接与会话 ----

  const connectWs = useCallback(
    (sid: number, mode: CaptureMode) => {
      setConnState("connecting");
      resetRealtimeBuffers();
      // 分离部署：VITE_API_BASE 指后端时 WS 直连后端；同源部署走当前 host
      const wsBase = API_BASE
        ? `${API_BASE.replace(/^http/, "ws")}/ws/copilot`
        : `${location.protocol === "https:" ? "wss://" : "ws://"}${location.host}/ws/copilot`;
      const ws = new WebSocket(wsBase);
      wsRef.current = ws;
      ws.onopen = () => {
        wsWasConnectedRef.current = true;
        wsClosedByUserRef.current = false;
        audioSequenceRef.current = 0;
        setConnState("connecting");
        const sources = [...mediaStreamsRef.current.keys()].map(backendSourceForTrack);
        wsSend({
          event: "copilot_start",
          session_id: sid,
          mode,
          speaker: speakerForMode(mode),
          sources: sources.length ? sources : ["mixed"],
        });
      };
      ws.onmessage = (event) => {
        try {
          handleWsEvent(JSON.parse(event.data as string));
        } catch {
          // 忽略无法解析的帧
        }
      };
      ws.onclose = () => {
        setConnState("ended");
        if (wsWasConnectedRef.current && !wsClosedByUserRef.current) {
          setWsError("连接已断开，可点击「重新连接」恢复");
        }
      };
      ws.onerror = () => {
        setWsError("WebSocket 连接失败");
        announceLive("WebSocket 连接失败");
      };
    },
    [announceLive, backendSourceForTrack, handleWsEvent, resetRealtimeBuffers, setConnState, speakerForMode, wsSend],
  );

  const createSession = useCallback(async () => {
    if (!planId) return;
    setSessionEndedByUser(false);
    setEndedAt(null);
    try {
      const data = await api.post<{
        session: { session_id: number };
        warning?: { code: string; message: string };
      }>("/api/copilot/sessions", { plan_id: planId });
      const sid = data.session.session_id;
      setSessionId(sid);
      if (data.warning) {
        announceLive(data.warning.message);
      }
      connectWs(sid, captureMode);
    } catch (err) {
      setWsError(err instanceof Error ? err.message : "创建会话失败");
    }
  }, [announceLive, captureMode, connectWs, planId]);

  const reconnect = useCallback(() => {
    setWsError("");
    if (!sessionId) return;
    connectWs(sessionId, captureMode);
  }, [captureMode, connectWs, sessionId]);

  const beginCapture = useCallback(async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setWsError("当前浏览器不支持麦克风采集");
      return;
    }
    try {
      const streams = await acquireStreams(captureMode);
      if (streams.size === 0) {
        throw new Error("未能获取任何音频轨");
      }
      mediaStreamsRef.current = streams;
      refreshCaptureStatus(captureMode, streams);
      if (!sessionId) {
        await createSession();
        return;
      }
      if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
        connectWs(sessionId, captureMode);
        return;
      }
      await startAllRecorders();
    } catch (err) {
      stopRecording();
      setWsError(`无法开始采集: ${err instanceof Error ? err.message : err}`);
    }
  }, [
    acquireStreams,
    captureMode,
    connectWs,
    createSession,
    refreshCaptureStatus,
    sessionId,
    startAllRecorders,
    stopRecording,
  ]);

  const toggleRecording = useCallback(() => {
    if (recording) {
      stopRecording();
      return;
    }
    void beginCapture();
  }, [beginCapture, recording, stopRecording]);

  const onCaptureModeChange = useCallback(
    (mode: CaptureMode) => {
      setCaptureMode(mode);
      setCurrent((cur) => {
        if (cur.text) {
          queueMicrotask(() => finalizeCurrentUtterance(cur.text, cur.side));
        }
        return cur;
      });
      refreshCaptureStatus(mode, mediaStreamsRef.current);
      const speaker = speakerForMode(mode);
      if (sessionId && wsRef.current?.readyState === WebSocket.OPEN) {
        wsSend({ event: "copilot_set_speaker", session_id: sessionId, speaker });
      }
      if (recording) {
        setCaptureStatus(`已切换模式（采集不中断）：${modeHintText(mode)}`);
      }
    },
    [finalizeCurrentUtterance, modeHintText, recording, refreshCaptureStatus, sessionId, speakerForMode, wsSend],
  );

  const endSession = useCallback(() => {
    stopRecording();
    setCurrent((cur) => {
      if (cur.text) {
        queueMicrotask(() => finalizeCurrentUtterance(cur.text, cur.side));
      }
      return cur;
    });
    resetRealtimeBuffers();
    sessionEndedByUserRef.current = true;
    setSessionEndedByUser(true);
    if (sessionId) {
      wsClosedByUserRef.current = true;
      wsSend({ event: "copilot_end", session_id: sessionId });
    }
    setConnState("ended");
  }, [finalizeCurrentUtterance, resetRealtimeBuffers, sessionId, setConnState, stopRecording, wsSend]);

  // 卸载时释放全部媒体资源（对齐 U5 资源清理）
  useEffect(() => {
    return () => {
      recordersRef.current.forEach((recorder) => recorder.stop());
      recordersRef.current = [];
      mediaStreamsRef.current.forEach((stream) => {
        stream.getTracks().forEach((track) => track.stop());
      });
      mediaStreamsRef.current = new Map();
      if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
        wsClosedByUserRef.current = true;
        wsRef.current.close();
      }
    };
  }, []);

  return {
    connState: CONN_LABELS[connStateKey],
    connStateKey,
    wsError,
    recording,
    captureBackend,
    messages,
    current: { ...current, text: smoothCurrentText },
    currentTime,
    answerPoints,
    referenceAnswer: smoothReferenceAnswer,
    referenceStreaming,
    currentStreaming,
    answering,
    sessionId,
    captureMode,
    captureStatus,
    sourceLabel,
    speakerLabel,
    feedRef,
    feedPendingCount,
    onFeedScroll,
    scrollFeedToBottom,
    ariaLiveMessage,
    displayMediaSupported,
    sessionEndedByUser,
    endedAt,
    onCaptureModeChange,
    toggleRecording,
    endSession,
    reconnect,
    createSession,
  };
}
