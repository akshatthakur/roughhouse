// Tempo, key, chords and melody transcription. Ported from Analyzer.java.
import { Pitch, Decimator, N as PN, RATE as PRATE, midi as hzToMidi, NOTES } from './pitch.js';

export { NOTES };

export const keyName = (root, minor) => root < 0 ? 'no key' : NOTES[root] + (minor ? ' minor' : ' major');

const PRIOR_BPM = 110, PRIOR_OCT = 0.8, LOW_WEIGHT = 4;

/** Basic tempo + key result for mono int16 audio. */
export function analyze(x, sr) {
  const r = { bpm: 0, bpmConfidence: 0, keyRoot: -1, minor: false, keyConfidence: 0 };
  const maxLen = Math.min(x.length, sr * 90);
  if (maxLen < sr * 4) return r;
  tempo(x, maxLen, sr, r);
  key(x, maxLen, sr, r);
  return r;
}

const hannCache = new Map();
function hann(n) {
  let w = hannCache.get(n);
  if (!w) {
    w = new Float64Array(n);
    for (let i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
    hannCache.set(n, w);
  }
  return w;
}

/** In-place iterative radix-2 complex FFT; n must be a power of two. */
export function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; (j & bit) !== 0; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      let t = re[i]; re[i] = re[j]; re[j] = t;
      t = im[i]; im[i] = im[j]; im[j] = t;
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang), half = len >> 1;
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < half; k++) {
        const a = i + k, b = a + half;
        const tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
        re[b] = re[a] - tr; im[b] = im[a] - ti;
        re[a] += tr; im[a] += ti;
        const ncr = cr * wr - ci * wi;
        ci = cr * wi + ci * wr;
        cr = ncr;
      }
    }
  }
}

function tempo(x, len, sr, r) {
  const n = 1024, hop = 512;
  const frames = Math.floor((len - n) / hop);
  if (frames < 64) return;
  const win = hann(n);
  const re = new Float64Array(n), im = new Float64Array(n);
  const bins = n / 2;
  let prev = new Float64Array(bins), cur = new Float64Array(bins);
  const onset = new Float64Array(frames);
  const lowBins = Math.floor(250 * n / sr);
  for (let f = 0; f < frames; f++) {
    const o = f * hop;
    for (let i = 0; i < n; i++) { re[i] = x[o + i] / 32768 * win[i]; im[i] = 0; }
    fft(re, im);
    let flux = 0;
    for (let k = 1; k < bins; k++) {
      const mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
      cur[k] = Math.log1p(100 * mag);
      const d = cur[k] - prev[k];
      if (d > 0) flux += k < lowBins ? d * LOW_WEIGHT : d;
    }
    onset[f] = f === 0 ? 0 : flux;
    const t = prev; prev = cur; cur = t;
  }
  const w = 16;
  const env = new Float64Array(frames);
  let acc = 0;
  for (let i = 0; i < frames; i++) {
    acc += onset[i];
    if (i >= w) acc -= onset[i - w];
    const mean = acc / Math.min(i + 1, w);
    env[i] = Math.max(0, onset[i] - mean);
  }
  const fps = sr / hop;
  const minLag = Math.floor(60 * fps / 200), maxLag = Math.ceil(60 * fps / 55);
  if (maxLag * 2 >= frames) return;
  const ac = new Float64Array(maxLag * 4 + 2);
  let e0 = 0;
  for (let i = 0; i < frames; i++) e0 += env[i] * env[i];
  if (e0 <= 0) return;
  for (let lag = 1; lag < ac.length && lag < frames; lag++) {
    let s = 0;
    for (let i = lag; i < frames; i++) s += env[i] * env[i - lag];
    ac[lag] = s / e0 * frames / (frames - lag);
  }
  let best = -1, bestS = 0, sum = 0, count = 0;
  for (let lag = minLag; lag <= maxLag; lag++) {
    const bpm = 60 * fps / lag;
    const oct = Math.log2(bpm / PRIOR_BPM);
    const prior = Math.exp(-0.5 * oct * oct / (PRIOR_OCT * PRIOR_OCT));
    const s = ac[lag] * prior;
    sum += s; count++;
    if (s > bestS) { bestS = s; best = lag; }
  }
  if (best < 0) return;
  let lagF = best;
  if (best > 1 && best + 1 < ac.length) {
    const a = ac[best - 1], b = ac[best], c = ac[best + 1];
    const den = a - 2 * b + c;
    if (den < 0) lagF = best + 0.5 * (a - c) / den;
  }
  const bpm = 60 * fps / lagF;
  r.bpm = Math.round(bpm * 2) / 2;
  const mean = sum / Math.max(1, count);
  r.bpmConfidence = Math.max(0, Math.min(1, (bestS / Math.max(1e-9, mean) - 1) / 3));
}

const MAJOR = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];
const CN = 8192, CHOP = 4096;
let pcCache = null;

/** Pitch-class profile of x[start, end): each non-silent frame votes equally. Returns frames used. */
function chroma(x, start, end, sr, out) {
  out.fill(0);
  const win = hann(CN);
  const re = new Float64Array(CN), im = new Float64Array(CN);
  if (!pcCache || pcCache.sr !== sr) {
    const pcOf = new Int8Array(CN / 2);
    for (let k = 0; k < CN / 2; k++) {
      const f = k * sr / CN;
      if (f < 55 || f > 2000) { pcOf[k] = -1; continue; }
      const m = 69 + 12 * Math.log2(f / 440);
      pcOf[k] = ((Math.round(m) % 12) + 12) % 12;
    }
    pcCache = { sr, pcOf };
  }
  const pcOf = pcCache.pcOf;
  const fc = new Float64Array(12);
  let used = 0;
  for (let o = start; o + CN <= end; o += CHOP) {
    let energy = 0;
    for (let i = 0; i < CN; i++) {
      const v = x[o + i] / 32768;
      energy += v * v;
      re[i] = v * win[i];
      im[i] = 0;
    }
    if (energy / CN < 1e-6) continue;
    fft(re, im);
    fc.fill(0);
    for (let k = 1; k < CN / 2; k++) {
      const pc = pcOf[k];
      if (pc < 0) continue;
      fc[pc] += Math.sqrt(re[k] * re[k] + im[k] * im[k]);
    }
    let s = 0;
    for (let i = 0; i < 12; i++) s += fc[i];
    if (s <= 0) continue;
    for (let i = 0; i < 12; i++) out[i] += fc[i] / s;
    used++;
  }
  return used;
}

function corr(c, profile, root) {
  let ma = 0, mb = 0;
  for (let i = 0; i < 12; i++) { ma += c[i]; mb += profile[i]; }
  ma /= 12; mb /= 12;
  let num = 0, da = 0, db = 0;
  for (let i = 0; i < 12; i++) {
    const a = c[(i + root) % 12] - ma, b = profile[i] - mb;
    num += a * b; da += a * a; db += b * b;
  }
  return num / Math.sqrt(da * db + 1e-12);
}

function key(x, len, sr, r) {
  const c = new Float64Array(12);
  if (chroma(x, 0, len, sr, c) < 4) return;
  let best = -2, second = -2, bestRoot = -1, bestMinor = false;
  for (let root = 0; root < 12; root++) {
    for (let m = 0; m < 2; m++) {
      const v = corr(c, m === 0 ? MAJOR : MINOR, root);
      if (v > best) { second = best; best = v; bestRoot = root; bestMinor = m === 1; }
      else if (v > second) second = v;
    }
  }
  if (bestRoot < 0 || best < 0.3) return;
  r.keyRoot = bestRoot;
  r.minor = bestMinor;
  r.keyConfidence = Math.max(0, Math.min(1, (best - second) * 5));
}

/** Best major/minor triad for a chroma vector, or null if nothing triad-like. */
export function chordName(c) {
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += c[i];
  if (sum <= 0) return null;
  let best = 0, name = null;
  for (let root = 0; root < 12; root++) {
    for (let q = 0; q < 2; q++) {
      const third = q === 0 ? 4 : 3;
      const inn = c[root] + c[(root + third) % 12] + c[(root + 7) % 12];
      const score = inn / sum - 0.25 * c[(root + (q === 0 ? 3 : 4)) % 12] / sum;
      if (score > best) { best = score; name = NOTES[root] + (q === 0 ? '' : 'm'); }
    }
  }
  return best > 0.42 ? name : null;
}

/** Everything the analysis sheet shows. beatFrames/beatsPerBar come from the jam grid. */
export function analyzeTrack(x, sr, beatFrames, beatsPerBar) {
  const f = { base: analyze(x, sr), notes: [], chords: [], low: -1, high: -1, avgCents: 0, edited: false };
  transcribe(x, sr, f);
  const bar = Math.round(beatFrames * beatsPerBar);
  if (bar > CN) {
    const c = new Float64Array(12);
    const bars = Math.min(256, Math.ceil(x.length / bar));
    for (let b = 0; b < bars; b++) {
      const s = b * bar, e = Math.min(x.length, s + bar);
      const used = chroma(x, Math.max(0, Math.min(s, e - CN)), e, sr, c);
      if (used === 0) continue;
      const name = chordName(c);
      if (name == null) continue;
      f.chords.push({ bar: b, name });
    }
  }
  return f;
}

/** Keeps notes in time order and the range current after a hand edit. */
export function refreshAfterEdit(f) {
  f.edited = true;
  f.notes.sort((a, b) => a.start - b.start);
  f.chords.sort((a, b) => a.bar - b.bar);
  f.low = -1; f.high = -1;
  for (const n of f.notes) {
    if (f.low < 0 || n.midi < f.low) f.low = n.midi;
    if (n.midi > f.high) f.high = n.midi;
  }
}

/** Monophonic note transcription from a YIN pitch track (best on voice or single-note lines). */
function transcribe(x, sr, f) {
  if (sr !== 48000) return;
  const n12 = Math.floor(x.length / 4);
  const y = new Float32Array(n12 + PN);
  const xf = new Float32Array(x.length);
  for (let i = 0; i < x.length; i++) xf[i] = x[i] / 32768;
  new Decimator(sr).process(xf, xf.length, 1, y, 0);
  const p = new Pitch();
  const hop = 128;
  const frames = Math.max(0, Math.floor((n12 - PN) / hop));
  const m = new Float32Array(frames);
  for (let i = 0; i < frames; i++) {
    const hz = p.detect(y, i * hop);
    m[i] = (hz > 0 && p.conf > 0.75) ? hzToMidi(hz) : NaN;
  }
  const sm = new Float32Array(frames);
  const w = [];
  for (let i = 0; i < frames; i++) {
    if (Number.isNaN(m[i])) { sm[i] = NaN; continue; }
    w.length = 0;
    for (let j = i - 2; j <= i + 2; j++) if (j >= 0 && j < frames && !Number.isNaN(m[j])) w.push(m[j]);
    w.sort((a, b) => a - b);
    sm[i] = w[w.length >> 1];
  }
  const fps = PRATE / hop;
  const minLen = Math.ceil(0.07 * fps);
  let i = 0, centsSum = 0, centsN = 0;
  while (i < frames) {
    if (Number.isNaN(sm[i])) { i++; continue; }
    const start = i, ref = Math.round(sm[i]);
    let gap = 0, j = i + 1, last = i;
    while (j < frames) {
      if (Number.isNaN(sm[j])) { if (++gap > 3) break; j++; continue; }
      if (Math.abs(sm[j] - ref) > 0.7) break;
      gap = 0; last = j; j++;
    }
    const len = last - start + 1;
    if (len >= minLen) {
      const vals = [];
      for (let t = start; t <= last; t++) if (!Number.isNaN(sm[t])) vals.push(sm[t]);
      vals.sort((a, b) => a - b);
      const med = vals[vals.length >> 1];
      const midi = Math.round(med);
      const nt = { midi, cents: (med - midi) * 100, start: Math.round(start * hop * 4 + PN * 2), len: Math.round(len * hop * 4) };
      f.notes.push(nt);
      centsSum += nt.cents; centsN++;
      if (f.low < 0 || midi < f.low) f.low = midi;
      if (midi > f.high) f.high = midi;
    }
    i = last + 1;
  }
  f.avgCents = centsN > 0 ? centsSum / centsN : 0;
}

// ---------------------------------------------------------------- storage (same JSON as Android)

export function toJson(f) {
  return {
    bpm: f.base.bpm, bpmConf: f.base.bpmConfidence, keyRoot: f.base.keyRoot, minor: f.base.minor,
    keyConf: f.base.keyConfidence, low: f.low, high: f.high, avgCents: f.avgCents, edited: f.edited,
    noteStart: f.notes.map(n => n.start), noteLen: f.notes.map(n => n.len),
    noteMidi: f.notes.map(n => n.midi), noteCents: f.notes.map(n => Math.round(n.cents)),
    chordBar: f.chords.map(c => c.bar), chordName: f.chords.map(c => c.name),
  };
}

export function fromJson(o) {
  const f = {
    base: { bpm: +o.bpm || 0, bpmConfidence: +o.bpmConf || 0, keyRoot: o.keyRoot ?? -1, minor: !!o.minor, keyConfidence: +o.keyConf || 0 },
    notes: [], chords: [], low: o.low ?? -1, high: o.high ?? -1, avgCents: +o.avgCents || 0, edited: !!o.edited,
  };
  for (let i = 0; i < o.noteStart.length; i++) {
    f.notes.push({ start: o.noteStart[i], len: o.noteLen[i], midi: o.noteMidi[i], cents: o.noteCents[i] });
  }
  for (let i = 0; i < o.chordBar.length; i++) f.chords.push({ bar: o.chordBar[i], name: o.chordName[i] });
  return f;
}
