// YIN fundamental-frequency detector on audio decimated to 12 kHz. Ported from Pitch.java.
import { Biquad } from './dsp.js';

export const NOTES = ['C', 'C#', 'D', 'Eb', 'E', 'F', 'F#', 'G', 'Ab', 'A', 'Bb', 'B'];
export const RATE = 12000;
export const N = 512;
const W = N / 2;
const TAU_MIN = Math.floor(RATE / 1000), TAU_MAX = Math.floor(RATE / 60);
const THRESH = 0.15;

export class Pitch {
  constructor() { this.d = new Float32Array(TAU_MAX + 2); this.conf = 0; }

  /** Detects f0 of x[off .. off+N). Returns Hz, or -1 if unvoiced/too quiet. */
  detect(x, off) {
    let energy = 0;
    for (let i = 0; i < N; i++) energy += x[off + i] * x[off + i];
    if (energy / N < 1e-5) { this.conf = 0; return -1; }
    const d = this.d;
    d[0] = 1;
    let run = 0, tau = -1;
    for (let t = 1; t <= TAU_MAX; t++) {
      let s = 0;
      for (let j = 0; j < W; j++) {
        const v = x[off + j] - x[off + j + t];
        s += v * v;
      }
      run += s;
      d[t] = run > 0 ? s * t / run : 1;
    }
    for (let t = TAU_MIN; t < TAU_MAX; t++) {
      if (d[t] < THRESH) {
        while (t + 1 < TAU_MAX && d[t + 1] < d[t]) t++;
        tau = t;
        break;
      }
    }
    if (tau < 0) { this.conf = 0; return -1; }
    const a = d[tau - 1], b = d[tau], c = d[tau + 1];
    const den = a - 2 * b + c;
    let shift = den !== 0 ? 0.5 * (a - c) / den : 0;
    if (shift > 1 || shift < -1) shift = 0;
    this.conf = 1 - b;
    return RATE / (tau + shift);
  }
}

/** Streaming 48 kHz -> 12 kHz decimator with a gentle anti-alias low-pass. Input is float -1..1. */
export class Decimator {
  constructor(inRate) {
    this.lp1 = new Biquad(); this.lp2 = new Biquad();
    this.lp1.lowpass(4800, 0.54, inRate);
    this.lp2.lowpass(4800, 1.31, inRate);
    this.phase = 0;
  }
  process(inp, n, gain, out, outPos) {
    let w = 0;
    for (let i = 0; i < n; i++) {
      const v = this.lp2.process(this.lp1.process(inp[i] * gain));
      if (++this.phase === 4) { this.phase = 0; out[outPos + w++] = v; }
    }
    return w;
  }
}

/** Live pitch from a mic stream, publishing about every 21 ms (smoothed, conf > 0.7). */
export class PitchTap {
  constructor(sr) {
    this.dec = new Decimator(sr);
    this.det = new Pitch();
    this.ring = new Float32Array(N * 2);
    this.fill = 0; this.since = 0; this.smooth = -1; this.hz = -1;
    this.tmp = new Float32Array(256);
  }
  feed(inp, n, g) {
    if (this.tmp.length < (n >> 2) + 2) this.tmp = new Float32Array((n >> 2) + 2);
    const w = this.dec.process(inp, n, g, this.tmp, 0);
    const ring = this.ring;
    for (let i = 0; i < w; i++) {
      if (this.fill === ring.length) { ring.copyWithin(0, N); this.fill = N; }
      ring[this.fill++] = this.tmp[i];
      if (++this.since >= 256 && this.fill >= N) {
        this.since = 0;
        const hz = this.det.detect(ring, this.fill - N);
        if (hz > 0 && this.det.conf > 0.7) {
          this.smooth = (this.smooth > 0 && Math.abs(midi(hz) - midi(this.smooth)) < 0.5)
            ? this.smooth * 0.6 + hz * 0.4 : hz;
          this.hz = this.smooth;
        } else {
          this.smooth = -1;
          this.hz = -1;
        }
      }
    }
  }
}

export const midi = hz => 69 + 12 * Math.log2(hz / 440);
export const noteName = m => NOTES[((m % 12) + 12) % 12] + (Math.floor(m / 12) - 1);
