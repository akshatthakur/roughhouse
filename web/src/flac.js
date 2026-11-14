// Minimal lossless FLAC encoder: 16-bit stereo, FIXED predictors (order 0-4), partitioned Rice
// coding, per-frame stereo decorrelation. Ported from FlacWriter.java.

const BLOCK = 4096;

class Bits {
  constructor() { this.buf = new Uint8Array(1 << 16); this.pos = 0; this.acc = 0; this.nacc = 0; }
  reset() { this.pos = 0; this.acc = 0; this.nacc = 0; }
  /** Writes the low n bits of v (v < 2^32, n <= 32). */
  put(v, n) {
    while (n > 0) {
      const take = Math.min(n, 24);
      const part = (v >>> (n - take)) & ((1 << take) - 1);
      // n - take can be 32 only when n > 32, which never happens; >>> 0 for n === take is a no-op
      this.acc = (this.acc << take) | part;
      this.nacc += take;
      n -= take;
      while (this.nacc >= 8) {
        this.nacc -= 8;
        if (this.pos >= this.buf.length) { const nb = new Uint8Array(this.buf.length * 2); nb.set(this.buf); this.buf = nb; }
        this.buf[this.pos++] = (this.acc >>> this.nacc) & 0xFF;
      }
      this.acc &= (1 << this.nacc) - 1;
    }
  }
  putSigned(v, n) { this.put(v & ((1 << n) - 1), n); }
  unary(zeros) {
    while (zeros >= 24) { this.put(0, 24); zeros -= 24; }
    this.put(1, zeros + 1);
  }
  align() { if (this.nacc > 0) this.put(0, 8 - this.nacc); }
}

function crc8(d, len) {
  let c = 0;
  for (let i = 0; i < len; i++) {
    c ^= d[i];
    for (let k = 0; k < 8; k++) c = (c & 0x80) ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
  }
  return c;
}

function crc16(d, len) {
  let c = 0;
  for (let i = 0; i < len; i++) {
    c ^= d[i] << 8;
    for (let k = 0; k < 8; k++) c = (c & 0x8000) ? ((c << 1) ^ 0x8005) & 0xFFFF : (c << 1) & 0xFFFF;
  }
  return c;
}

const fold = v => v >= 0 ? v * 2 : -v * 2 - 1;

export class FlacWriter {
  /** onBytes(Uint8Array) receives encoded output in order. */
  constructor(sampleRate, totalFrames, onBytes) {
    this.sr = sampleRate;
    this.out = onBytes;
    this.pend = new Int16Array(BLOCK * 2);
    this.pendN = 0;
    this.frameNo = 0;
    this.bw = new Bits();
    this.l = new Int32Array(BLOCK); this.r = new Int32Array(BLOCK);
    this.m = new Int32Array(BLOCK); this.s = new Int32Array(BLOCK);
    this.res = new Int32Array(BLOCK);
    this.bestOrder = 0; this.bestPartOrder = 0;
    this.bestParams = new Int32Array(256); this.tmpParams = new Int32Array(256);
    const h = new Bits();
    h.put(0x664C6143, 32);
    h.put(1, 1); h.put(0, 7);
    h.put(34, 24);
    h.put(BLOCK, 16); h.put(BLOCK, 16);
    h.put(0, 24); h.put(0, 24);
    h.put(sampleRate, 20);
    h.put(1, 3);
    h.put(15, 5);
    h.put(Math.floor(totalFrames / 4294967296), 4);
    h.put(totalFrames >>> 0, 32);
    for (let i = 0; i < 4; i++) h.put(0, 32);
    this.out(h.buf.slice(0, h.pos));
  }

  /** Appends n stereo frames from interleaved 16-bit samples. */
  write(inp, n) {
    let i = 0;
    while (i < n) {
      const take = Math.min(n - i, BLOCK - this.pendN);
      this.pend.set(inp.subarray(i * 2, (i + take) * 2), this.pendN * 2);
      this.pendN += take;
      i += take;
      if (this.pendN === BLOCK) { this.frame(BLOCK); this.pendN = 0; }
    }
  }

  finish() {
    if (this.pendN > 0) this.frame(this.pendN);
    this.pendN = 0;
  }

  srCode() {
    switch (this.sr) {
      case 44100: return 9;
      case 48000: return 10;
      case 96000: return 11;
      case 32000: return 8;
      case 22050: return 6;
      default: return 12;
    }
  }

  frame(n) {
    const { l, r, m, s, pend, bw } = this;
    for (let i = 0; i < n; i++) {
      const a = pend[2 * i], b = pend[2 * i + 1];
      l[i] = a; r[i] = b; s[i] = a - b; m[i] = (a + b) >> 1;
    }
    const cl = this.cost(l, n, 16), cr = this.cost(r, n, 16), cs = this.cost(s, n, 17), cm = this.cost(m, n, 16);
    let mode = 1, best = cl + cr;
    if (cl + cs < best) { best = cl + cs; mode = 8; }
    if (cs + cr < best) { best = cs + cr; mode = 9; }
    if (cm + cs < best) { mode = 10; }

    bw.reset();
    bw.put(0x3FFE, 14); bw.put(0, 1); bw.put(0, 1);
    const full = n === BLOCK;
    bw.put(full ? 12 : 7, 4);
    bw.put(this.srCode(), 4);
    bw.put(mode, 4);
    bw.put(4, 3);
    bw.put(0, 1);
    this.utf8(this.frameNo++);
    if (!full) bw.put(n - 1, 16);
    if (this.srCode() === 12) bw.put(Math.floor(this.sr / 1000), 8);
    bw.put(crc8(bw.buf, bw.pos), 8);

    switch (mode) {
      case 1: this.subframe(l, n, 16); this.subframe(r, n, 16); break;
      case 8: this.subframe(l, n, 16); this.subframe(s, n, 17); break;
      case 9: this.subframe(s, n, 17); this.subframe(r, n, 16); break;
      default: this.subframe(m, n, 16); this.subframe(s, n, 17); break;
    }
    bw.align();
    bw.put(crc16(bw.buf, bw.pos), 16);
    this.out(bw.buf.slice(0, bw.pos));
  }

  utf8(v) {
    const b = this.bw;
    if (v < 0x80) { b.put(v, 8); return; }
    const bytes = v < 0x800 ? 2 : v < 0x10000 ? 3 : v < 0x200000 ? 4 : v < 0x4000000 ? 5 : 6;
    const lead = (0xFF00 >> bytes) & 0xFF;
    b.put(lead | Math.floor(v / Math.pow(2, 6 * (bytes - 1))), 8);
    for (let i = bytes - 2; i >= 0; i--) b.put(0x80 | (Math.floor(v / Math.pow(2, 6 * i)) & 0x3F), 8);
  }

  cost(x, n, bps) {
    let best = Infinity;
    const maxOrder = Math.min(4, n - 1);
    for (let ord = 0; ord <= maxOrder; ord++) {
      this.residual(x, n, ord);
      const c = ord * bps + this.riceCost(this.res, ord, n);
      if (c < best) { best = c; this.bestOrder = ord; }
    }
    return best;
  }

  subframe(x, n, bps) {
    const bw = this.bw;
    let constant = true;
    for (let i = 1; i < n && constant; i++) if (x[i] !== x[0]) constant = false;
    if (constant) {
      bw.put(0, 1); bw.put(0, 6); bw.put(0, 1);
      bw.putSigned(x[0], bps);
      return;
    }
    this.cost(x, n, bps);
    const ord = this.bestOrder;
    const verbatim = n * bps;
    this.residual(x, n, ord);
    const fixed = ord * bps + this.riceCost(this.res, ord, n);
    if (verbatim <= fixed) {
      bw.put(0, 1); bw.put(1, 6); bw.put(0, 1);
      for (let i = 0; i < n; i++) bw.putSigned(x[i], bps);
      return;
    }
    bw.put(0, 1); bw.put(8 | ord, 6); bw.put(0, 1);
    for (let i = 0; i < ord; i++) bw.putSigned(x[i], bps);
    this.writeResidual(this.res, ord, n);
  }

  residual(x, n, ord) {
    const res = this.res;
    for (let i = ord; i < n; i++) {
      switch (ord) {
        case 0: res[i] = x[i]; break;
        case 1: res[i] = x[i] - x[i - 1]; break;
        case 2: res[i] = x[i] - 2 * x[i - 1] + x[i - 2]; break;
        case 3: res[i] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]; break;
        default: res[i] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]; break;
      }
    }
  }

  riceCost(e, ord, n) {
    let best = Infinity, maxPo = 0;
    while (maxPo < 8 && (n % (1 << (maxPo + 1))) === 0 && (n >> (maxPo + 1)) > ord) maxPo++;
    for (let po = 0; po <= maxPo; po++) {
      const parts = 1 << po, psize = n >> po;
      let bits = 6;
      for (let p = 0; p < parts; p++) {
        const start = p === 0 ? ord : p * psize, end = (p + 1) * psize;
        let sum = 0;
        for (let i = start; i < end; i++) sum += fold(e[i]);
        const cnt = end - start;
        let k = 0;
        if (cnt > 0) {
          const mean = Math.floor(sum / cnt);
          while (k < 14 && Math.pow(2, k + 1) <= mean) k++;
        }
        bits += 4 + cnt * (k + 1) + Math.floor(sum / Math.pow(2, k));
        this.tmpParams[p] = k;
      }
      if (bits < best) {
        best = bits;
        this.bestPartOrder = po;
        this.bestParams.set(this.tmpParams.subarray(0, parts));
      }
    }
    return best;
  }

  writeResidual(e, ord, n) {
    this.riceCost(e, ord, n);
    const bw = this.bw, po = this.bestPartOrder, parts = 1 << po, psize = n >> po;
    bw.put(0, 2);
    bw.put(po, 4);
    for (let p = 0; p < parts; p++) {
      const k = this.bestParams[p];
      bw.put(k, 4);
      const start = p === 0 ? ord : p * psize, end = (p + 1) * psize, mask = (1 << k) - 1;
      for (let i = start; i < end; i++) {
        const u = fold(e[i]);
        bw.unary(u >>> k);
        if (k > 0) bw.put(u & mask, k);
      }
    }
  }
}
