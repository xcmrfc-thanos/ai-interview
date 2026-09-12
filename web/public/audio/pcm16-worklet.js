/** PCM16 采集 AudioWorklet：线性插值重采样至 16kHz 并按 100ms 分帧输出。 */
class Pcm16CaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    var opts = (options && options.processorOptions) || {};
    this.targetSampleRate = opts.targetSampleRate || 16000;
    this.frameSize = opts.frameSize || 1600;
    this.resampleRatio = sampleRate / this.targetSampleRate;
    this.audioBuffer = [];
  }

  flushFrame(frameSamples) {
    var pcm = new Int16Array(frameSamples.length);
    for (var j = 0; j < frameSamples.length; j++) {
      var s = frameSamples[j];
      pcm[j] = s < 0 ? s * 0x8000 : s * 0x7fff;
    }
    this.port.postMessage({ type: "pcm", pcm: pcm.buffer }, [pcm.buffer]);
  }

  process(inputs) {
    var input = inputs[0];
    if (!input || !input[0] || input[0].length === 0) {
      return true;
    }
    var channel = input[0];
    var inputLen = channel.length;
    var outputLen = Math.floor(inputLen / this.resampleRatio);
    for (var i = 0; i < outputLen; i++) {
      var position = i * this.resampleRatio;
      var left = Math.floor(position);
      var right = Math.min(left + 1, inputLen - 1);
      var weight = position - left;
      var sample = channel[left] * (1 - weight) + channel[right] * weight;
      this.audioBuffer.push(Math.max(-1, Math.min(1, sample)));
    }
    while (this.audioBuffer.length >= this.frameSize) {
      var frame = this.audioBuffer.splice(0, this.frameSize);
      this.flushFrame(frame);
    }
    return true;
  }
}

registerProcessor("pcm16-capture-processor", Pcm16CaptureProcessor);
