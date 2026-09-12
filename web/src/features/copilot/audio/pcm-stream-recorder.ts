// 双轨 PCM 采集器：AudioWorklet 优先（音频线程重采样+分帧），失败回退 ScriptProcessor
export type PcmRecorderOptions = {
  sampleRate?: number;
  frameSize?: number;
  stopTracks?: boolean;
  onFrameRecorded: (frameBuffer: ArrayBuffer) => void;
  onBackend?: (backend: "worklet" | "script") => void;
};

const WORKLET_URL = "/audio/pcm16-worklet.js";
let workletModuleReady: Promise<boolean> | null = null;

function loadWorkletModule(context: AudioContext): Promise<boolean> {
  if (!workletModuleReady) {
    workletModuleReady = context.audioWorklet
      .addModule(WORKLET_URL)
      .then(() => true)
      .catch(() => false);
  }
  return workletModuleReady;
}

export class PcmStreamRecorder {
  private stream: MediaStream;
  private targetSampleRate: number;
  private frameSize: number;
  private onFrameRecorded: (frameBuffer: ArrayBuffer) => void;
  private onBackend?: (backend: "worklet" | "script") => void;
  private stopTracks: boolean;
  private context: AudioContext | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private worklet: AudioWorkletNode | null = null;
  private processor: ScriptProcessorNode | null = null;
  private buffer: number[] = [];
  private running = false;

  constructor(stream: MediaStream, options: PcmRecorderOptions) {
    this.stream = stream;
    this.targetSampleRate = options.sampleRate ?? 16000;
    this.frameSize = options.frameSize ?? 1600;
    this.onFrameRecorded = options.onFrameRecorded;
    this.onBackend = options.onBackend;
    this.stopTracks = options.stopTracks !== false;
  }

  async start(): Promise<void> {
    if (this.running) return;
    const AudioContextClass =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioContextClass) {
      throw new Error("当前浏览器不支持 Web Audio");
    }
    this.context = new AudioContextClass();
    this.source = this.context.createMediaStreamSource(this.stream);

    const useWorklet = !!(this.context.audioWorklet && (await loadWorkletModule(this.context)));
    if (useWorklet && this.context.audioWorklet) {
      this.worklet = new AudioWorkletNode(this.context, "pcm16-capture-processor", {
        numberOfInputs: 1,
        numberOfOutputs: 0,
        processorOptions: { targetSampleRate: this.targetSampleRate, frameSize: this.frameSize },
      });
      this.worklet.port.onmessage = (event: MessageEvent) => {
        const data = event.data as { type: string; pcm: ArrayBuffer };
        if (data.type === "pcm") {
          this.onFrameRecorded(data.pcm);
        }
      };
      this.source.connect(this.worklet);
      this.onBackend?.("worklet");
    } else {
      this.processor = this.context.createScriptProcessor(4096, 1, 1);
      this.processor.onaudioprocess = (event) => {
        this.processScript(event.inputBuffer.getChannelData(0));
      };
      this.source.connect(this.processor);
      this.processor.connect(this.context.destination);
      this.onBackend?.("script");
    }
    await this.context.resume();
    this.running = true;
  }

  private processScript(input: Float32Array) {
    if (!this.running || !this.context) return;
    const ratio = this.context.sampleRate / this.targetSampleRate;
    const outputLength = Math.floor(input.length / ratio);
    for (let i = 0; i < outputLength; i++) {
      const position = i * ratio;
      const left = Math.floor(position);
      const right = Math.min(left + 1, input.length - 1);
      const weight = position - left;
      const sample = input[left] * (1 - weight) + input[right] * weight;
      this.buffer.push(Math.max(-1, Math.min(1, sample)));
    }
    while (this.buffer.length >= this.frameSize) {
      const frame = this.buffer.splice(0, this.frameSize);
      const pcm = new Int16Array(frame.length);
      for (let j = 0; j < frame.length; j++) {
        pcm[j] = frame[j] < 0 ? frame[j] * 0x8000 : frame[j] * 0x7fff;
      }
      this.onFrameRecorded(pcm.buffer);
    }
  }

  stop() {
    this.running = false;
    if (this.worklet) {
      this.worklet.port.onmessage = null;
      this.worklet.disconnect();
      this.worklet = null;
    }
    if (this.processor) {
      this.processor.disconnect();
      this.processor.onaudioprocess = null;
      this.processor = null;
    }
    if (this.source) {
      this.source.disconnect();
      this.source = null;
    }
    if (this.context) {
      void this.context.close().catch(() => undefined);
      this.context = null;
    }
    if (this.stopTracks && this.stream) {
      this.stream.getTracks().forEach((track) => track.stop());
    }
    this.buffer = [];
  }
}
