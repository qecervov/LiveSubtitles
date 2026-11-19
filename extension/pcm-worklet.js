// 16 kHz mono PCM, one 200 ms frame per message. Fractional box resampling
// works with both 44.1 and 48 kHz input without drift or duplicated samples.
class PcmCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / 16000;
    this.port.onmessage = () => this.reset();
    this.reset();
  }
  reset() {
    this.area = 0; this.width = 0; this.position = 0;
    this.frame = new Int16Array(3200);
  }
  process(inputs) {
    const channels = inputs[0];
    if (!channels?.length) return true;
    for (let i = 0; i < channels[0].length; i++) {
      let value = 0;
      for (const channel of channels) value += channel[i] / channels.length;
      let remaining = 1;
      while (remaining > 1e-9) {
        const take = Math.min(remaining, this.ratio - this.width);
        this.area += value * take;
        this.width += take;
        remaining -= take;
        if (this.width >= this.ratio - 1e-9) {
          const sample = Math.max(-1, Math.min(1, this.area / this.ratio));
          this.frame[this.position++] = Math.round(sample * (sample < 0 ? 32768 : 32767));
          this.area = 0; this.width = 0;
          if (this.position === this.frame.length) {
            this.port.postMessage(this.frame.buffer, [this.frame.buffer]);
            this.frame = new Int16Array(3200); this.position = 0;
          }
        }
      }
    }
    return true;
  }
}
registerProcessor('pcm-capture', PcmCapture);
