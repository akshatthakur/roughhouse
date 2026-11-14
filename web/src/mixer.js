// Renders the jam (tracks + FX + reverb bus + metronome) to interleaved stereo float. Ported from Mixer.java.
import { Fx, Reverb } from './dsp.js';
import { beatFrames, quarterFrames, floorMod } from './timing.js';

/** Transparent below 0.8, smooth saturation above: a cheap safety limiter. */
export function soft(x) {
  const a = Math.abs(x);
  if (a <= 0.8) return x;
  const u = (a - 0.8) * 5;
  const y = 0.8 + 0.2 * u / (1 + u);
  return x < 0 ? -y : y;
}

export class Mixer {
  /** s: { tracks: [{id, data: Int16Array, vol, pan, lowCut, ...}], bpm, beatsPerBar, beatUnit, metroVol, master } */
  constructor(s, sr) {
    this.s = s;
    this.sr = sr;
    this.calib = false;
    this.fx = new Map();
    this.rev = new Reverb(sr);
    this.revIn = new Float32Array(256);
    this.revTail = 0;
    this.lastBeat = -Infinity;
    this.clickT = -1;
    this.accent = false;
    this.peakL = 0;
    this.peakR = 0;
  }

  /**
   * @param out     interleaved stereo output, length >= n*2
   * @param p       timeline frame for each output frame (may be negative during count-in)
   * @param exclude id of the track not to play (the one being recorded), or -1
   * @param click   whether to add metronome clicks
   */
  render(out, p, n, exclude, click) {
    const s = this.s, sr = this.sr;
    out.fill(0, 0, n * 2);
    if (this.revIn.length < n) this.revIn = new Float32Array(n);
    const revIn = this.revIn;
    revIn.fill(0, 0, n);

    if (!this.calib) {
      let anySolo = false;
      for (const t of s.tracks) if (t.solo) { anySolo = true; break; }
      const qf = quarterFrames(s, sr);
      let anyRev = false;
      for (const t of s.tracks) {
        if (t.id === exclude || t.mute || (anySolo && !t.solo)) continue;
        let f = this.fx.get(t.id);
        if (!f) { f = new Fx(sr); this.fx.set(t.id, f); }
        const d = t.data, len = d.length;
        if (p[n - 1] < 0 || p[0] >= len || len === 0) {
          if (f.idle > sr * 4) { t.meter = (t.meter || 0) * 0.9; continue; }
          f.idle += n;
        } else {
          f.idle = 0;
        }
        f.update(t, qf);
        const vol = t.vol, ang = (t.pan + 1) * Math.PI / 4;
        const gl = Math.cos(ang) * Math.SQRT2 * vol, gr = Math.sin(ang) * Math.SQRT2 * vol;
        const rs = t.rev * vol;
        if (rs > 0) anyRev = true;
        let pk = 0;
        for (let i = 0; i < n; i++) {
          const q = p[i];
          const x = (q >= 0 && q < len) ? d[q] * (1 / 32768) : 0;
          const y = f.process(x);
          const ay = y < 0 ? -y : y;
          if (ay > pk) pk = ay;
          out[2 * i] += y * gl;
          out[2 * i + 1] += y * gr;
          revIn[i] += y * rs;
        }
        pk *= vol;
        t.meter = Math.max(pk, (t.meter || 0) * 0.92);
      }
      if (anyRev || this.revTail > 0) {
        this.rev.process(revIn, out, n);
        this.revTail = anyRev ? sr * 4 : this.revTail - n;
      }
    }

    if (this.calib) {
      const interval = sr / 2, len = Math.floor(sr * 3 / 1000);
      for (let i = 0; i < n; i++) {
        const q = p[i];
        if (q > 0 && q % interval === 0) this.clickT = 0;
        if (this.clickT >= 0 && this.clickT < len) {
          const v = Math.sin(2 * Math.PI * 1000 * (this.clickT + 0.25) / sr) * 0.9;
          out[2 * i] += v;
          out[2 * i + 1] += v;
          this.clickT++;
        } else {
          this.clickT = -1;
        }
      }
    } else if (click) {
      const bf = beatFrames(s, sr), bpb = Math.max(1, s.beatsPerBar), mv = s.metroVol;
      const clen = Math.floor(sr * 0.04);
      for (let i = 0; i < n; i++) {
        const q = p[i];
        const beat = Math.floor(q / bf);
        if (beat !== this.lastBeat) {
          this.lastBeat = beat;
          if (q - beat * bf < sr * 0.01) {
            this.clickT = 0;
            this.accent = floorMod(beat, bpb) === 0;
          }
        }
        if (this.clickT >= 0) {
          if (this.clickT < clen) {
            const t = this.clickT / sr;
            const v = Math.sin(2 * Math.PI * (this.accent ? 1760 : 1320) * t) * Math.exp(-t * 120) * mv;
            out[2 * i] += v;
            out[2 * i + 1] += v;
            this.clickT++;
          } else {
            this.clickT = -1;
          }
        }
      }
    }

    const m = s.master;
    let pl = 0, pr = 0;
    for (let i = 0; i < n * 2; i += 2) {
      const a = soft(out[i] * m), b = soft(out[i + 1] * m);
      out[i] = a;
      out[i + 1] = b;
      const aa = Math.abs(a), ab = Math.abs(b);
      if (aa > pl) pl = aa;
      if (ab > pr) pr = ab;
    }
    this.peakL = pl;
    this.peakR = pr;
  }
}
