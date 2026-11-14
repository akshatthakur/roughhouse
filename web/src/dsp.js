// Small DSP blocks used by the mixer. Ported from Dsp.java.

export const DIV_BEATS = [0.25, 0.5, 0.75, 1.0, 2.0];
export const DIV_NAMES = ['1/16', '1/8', '1/8 dotted', '1/4', '1/2'];

/** RBJ biquad, transposed direct form II. */
export class Biquad {
  constructor() { this.b0 = 1; this.b1 = 0; this.b2 = 0; this.a1 = 0; this.a2 = 0; this.z1 = 0; this.z2 = 0; }

  highpass(f, q, sr) {
    const w = 2 * Math.PI * f / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q), a0 = 1 + al;
    this.b0 = (1 + c) / 2 / a0; this.b1 = -(1 + c) / a0; this.b2 = this.b0;
    this.a1 = -2 * c / a0; this.a2 = (1 - al) / a0;
  }

  lowpass(f, q, sr) {
    const w = 2 * Math.PI * f / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q), a0 = 1 + al;
    this.b0 = (1 - c) / 2 / a0; this.b1 = (1 - c) / a0; this.b2 = this.b0;
    this.a1 = -2 * c / a0; this.a2 = (1 - al) / a0;
  }

  shelf(low, f, gainDb, sr) {
    const A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f / sr;
    const c = Math.cos(w), al = Math.sin(w) / 2 * Math.SQRT2, sq = 2 * Math.sqrt(A) * al;
    let nb0, nb1, nb2, a0, na1, na2;
    if (low) {
      nb0 = A * ((A + 1) - (A - 1) * c + sq);
      nb1 = 2 * A * ((A - 1) - (A + 1) * c);
      nb2 = A * ((A + 1) - (A - 1) * c - sq);
      a0 = (A + 1) + (A - 1) * c + sq;
      na1 = -2 * ((A - 1) + (A + 1) * c);
      na2 = (A + 1) + (A - 1) * c - sq;
    } else {
      nb0 = A * ((A + 1) + (A - 1) * c + sq);
      nb1 = -2 * A * ((A - 1) + (A + 1) * c);
      nb2 = A * ((A + 1) + (A - 1) * c - sq);
      a0 = (A + 1) - (A - 1) * c + sq;
      na1 = 2 * ((A - 1) - (A + 1) * c);
      na2 = (A + 1) - (A - 1) * c - sq;
    }
    this.b0 = nb0 / a0; this.b1 = nb1 / a0; this.b2 = nb2 / a0; this.a1 = na1 / a0; this.a2 = na2 / a0;
  }

  process(x) {
    const y = this.b0 * x + this.z1;
    let z1 = this.b1 * x - this.a1 * y + this.z2;
    let z2 = this.b2 * x - this.a2 * y;
    if (z1 < 1e-15 && z1 > -1e-15) z1 = 0;
    if (z2 < 1e-15 && z2 > -1e-15) z2 = 0;
    this.z1 = z1; this.z2 = z2;
    return y;
  }
}

/** Per-track chain: low cut -> bass/treble shelves -> compressor -> echo. */
export class Fx {
  constructor(sr) {
    this.sr = sr;
    this.hp = new Biquad(); this.lo = new Biquad(); this.hi = new Biquad();
    this.hpOn = false; this.loOn = false; this.hiOn = false;
    this.cAmt = 0; this.thrLin = 1; this.slope = 0; this.makeup = 1; this.env = 0; this.cg = 1; this.cc = 0;
    this.idle = 0;
    this.att = Math.exp(-1 / (0.003 * sr));
    this.rel = Math.exp(-1 / (0.12 * sr));
    this.dl = new Float32Array(Math.floor(sr * 2.6));
    this.dw = 0; this.dlen = 1; this.emix = 0; this.efb = 0; this.lp = 0;
    this.cLow = -1; this.cBass = 99; this.cTreble = 99;
  }

  update(t, quarterFrames) {
    const lc = t.lowCut, b = t.bass, tr = t.treble;
    this.hpOn = lc > 1;
    if (this.hpOn && lc !== this.cLow) { this.hp.highpass(lc, 0.707, this.sr); this.cLow = lc; }
    this.loOn = Math.abs(b) > 0.1;
    if (this.loOn && b !== this.cBass) { this.lo.shelf(true, 150, b, this.sr); this.cBass = b; }
    this.hiOn = Math.abs(tr) > 0.1;
    if (this.hiOn && tr !== this.cTreble) { this.hi.shelf(false, 5000, tr, this.sr); this.cTreble = tr; }

    const a = t.comp;
    if (a !== this.cAmt) {
      this.cAmt = a;
      const thrDb = -30 * a, ratio = 1 + 5 * a;
      this.thrLin = Math.pow(10, thrDb / 20);
      this.slope = 1 - 1 / ratio;
      this.makeup = Math.pow(10, (-thrDb * this.slope * 0.75) / 20);
    }
    this.emix = t.echo;
    this.efb = Math.min(0.9, t.echoFb);
    const div = Math.max(0, Math.min(DIV_BEATS.length - 1, t.echoDiv | 0));
    const d = Math.floor(DIV_BEATS[div] * quarterFrames);
    this.dlen = Math.max(1, Math.min(this.dl.length - 1, d));
  }

  process(x) {
    if (this.hpOn) x = this.hp.process(x);
    if (this.loOn) x = this.lo.process(x);
    if (this.hiOn) x = this.hi.process(x);
    if (this.cAmt > 0) {
      const a = Math.abs(x);
      let env = this.env;
      env = a > env ? this.att * env + (1 - this.att) * a : this.rel * env + (1 - this.rel) * a;
      if (env < 1e-15) env = 0;
      this.env = env;
      if ((++this.cc & 7) === 0) {
        this.cg = env > this.thrLin ? this.makeup * Math.exp(-this.slope * Math.log(env / this.thrLin)) : this.makeup;
      }
      x *= this.cg;
    }
    if (this.emix > 0) {
      const dl = this.dl;
      let r = this.dw - this.dlen;
      if (r < 0) r += dl.length;
      const d = dl[r];
      let lp = this.lp + 0.35 * (d - this.lp);
      if (lp < 1e-15 && lp > -1e-15) lp = 0;
      this.lp = lp;
      dl[this.dw] = x + lp * this.efb;
      if (++this.dw >= dl.length) this.dw = 0;
      x += d * this.emix;
    }
    return x;
  }
}

class Comb {
  constructor(n) { this.b = new Float32Array(n); this.i = 0; this.filt = 0; }
  process(x) {
    const b = this.b, o = b[this.i];
    let f = o * 0.75 + this.filt * 0.25;
    if (f < 1e-15 && f > -1e-15) f = 0;
    this.filt = f;
    b[this.i] = x + f * 0.84;
    if (++this.i >= b.length) this.i = 0;
    return o;
  }
}

class Allpass {
  constructor(n) { this.b = new Float32Array(n); this.i = 0; }
  process(x) {
    const b = this.b, bo = b[this.i];
    b[this.i] = x + bo * 0.5;
    if (++this.i >= b.length) this.i = 0;
    return bo - x;
  }
}

/** Compact Freeverb-style stereo reverb (4 combs + 2 allpasses per side). */
export class Reverb {
  constructor(sr) {
    const cs = [1116, 1188, 1277, 1356], as = [556, 441], k = sr / 44100;
    this.cl = cs.map(c => new Comb(Math.floor(c * k)));
    this.cr = cs.map(c => new Comb(Math.floor((c + 23) * k)));
    this.al = as.map(a => new Allpass(Math.floor(a * k)));
    this.ar = as.map(a => new Allpass(Math.floor((a + 23) * k)));
  }

  /** Adds wet stereo signal for mono send `inp` into interleaved `out`. */
  process(inp, out, n) {
    const cl = this.cl, cr = this.cr, al = this.al, ar = this.ar;
    for (let i = 0; i < n; i++) {
      const x = inp[i] * 0.03;
      let l = 0, r = 0;
      for (let k = 0; k < 4; k++) { l += cl[k].process(x); r += cr[k].process(x); }
      for (let k = 0; k < 2; k++) { l = al[k].process(l); r = ar[k].process(r); }
      out[2 * i] += l;
      out[2 * i + 1] += r;
    }
  }
}
