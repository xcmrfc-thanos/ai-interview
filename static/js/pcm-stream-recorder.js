/* 将已有 MediaStream 转成固定大小的 16 kHz PCM 帧。 */
(() => {
  "use strict";

  class PcmStreamRecorder {
    constructor(stream, options = {}) {
      this.stream = stream;
      this.sampleRate = options.sampleRate || 16000;
      this.frameSize = options.frameSize || 1600;
      this.onFrameRecorded = options.onFrameRecorded;
      this.context = null;
      this.processor = null;
      this.source = null;
      this.buffer = [];
      this.running = false;
      this.stopTracks = options.stopTracks !== false;
    }

    async start() {
      if (this.running) return;
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) throw new Error("当前浏览器不支持 Web Audio");
      this.context = new AudioContextClass();
      this.source = this.context.createMediaStreamSource(this.stream);
      this.processor = this.context.createScriptProcessor(4096, 1, 1);
      this.processor.onaudioprocess = (event) => this._process(event.inputBuffer.getChannelData(0));
      this.source.connect(this.processor);
      this.processor.connect(this.context.destination);
      await this.context.resume();
      this.running = true;
    }

    _process(input) {
      if (!this.running && !this.context) return;
      const ratio = this.context.sampleRate / this.sampleRate;
      const outputLength = Math.floor(input.length / ratio);
      for (let index = 0; index < outputLength; index += 1) {
        const position = index * ratio;
        const left = Math.floor(position);
        const right = Math.min(left + 1, input.length - 1);
        const weight = position - left;
        const sample = input[left] * (1 - weight) + input[right] * weight;
        this.buffer.push(Math.max(-1, Math.min(1, sample)));
      }
      while (this.buffer.length >= this.frameSize) {
        const frame = this.buffer.splice(0, this.frameSize);
        const pcm = new Int16Array(frame.length);
        frame.forEach((sample, index) => {
          pcm[index] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
        });
        this.onFrameRecorded?.(pcm.buffer);
      }
    }

    stop() {
      this.running = false;
      this.processor?.disconnect();
      this.source?.disconnect();
      this.processor = null;
      this.source = null;
      if (this.context) {
        this.context.close().catch(() => {});
        this.context = null;
      }
      if (this.stopTracks) this.stream?.getTracks().forEach((track) => track.stop());
      this.buffer = [];
    }
  }

  window.PcmStreamRecorder = PcmStreamRecorder;
})();
