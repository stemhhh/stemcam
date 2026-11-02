class PcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
    this.target = Math.max(128, Math.round(sampleRate * 0.02));
    this.buf = new Int16Array(this.target);
    this.pos = 0;
  }

  process(inputs) {
    const channels = inputs[0];
    if (!channels || !channels.length || !channels[0])
      return true;
    const frames = channels[0].length;
    const count = channels.length;
    for (let i = 0; i < frames; i++) {
      let sample = 0;
      for (let c = 0; c < count; c++)
        sample += channels[c][i] || 0;
      sample /= count;
      if (sample > 1) sample = 1;
      else if (sample < -1) sample = -1;
      this.buf[this.pos++] = sample < 0 ? sample * 32768 : sample * 32767;
      if (this.pos >= this.target) {
        const copy = this.buf.slice(0);
        this.port.postMessage(copy, [copy.buffer]);
        this.pos = 0;
      }
    }
    return true;
  }
}

registerProcessor("pcm-worklet", PcmWorklet);
