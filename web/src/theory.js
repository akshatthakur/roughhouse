// Songwriting helpers derived from a key. Ported from Theory.java.
import { NOTES } from './pitch.js';

const MAJOR = [0, 2, 4, 5, 7, 9, 11];
const MINOR = [0, 2, 3, 5, 7, 8, 10];
const MAJ_Q = ['', 'm', 'm', '', '', 'm', 'dim'];
const MIN_Q = ['m', 'dim', '', 'm', 'm', '', ''];
const MAJ_N = ['I', 'ii', 'iii', 'IV', 'V', 'vi', 'vii°'];
const MIN_N = ['i', 'ii°', 'III', 'iv', 'v', 'VI', 'VII'];
const mod12 = v => ((v % 12) + 12) % 12;

export const scale = (root, minor) => (minor ? MINOR : MAJOR).map(s => (root + s) % 12);

export function inKey(midi, root, minor) {
  const pc = mod12(midi);
  if (scale(root, minor).includes(pc)) return true;
  // in minor, the raised 7th (harmonic minor) is normal too
  return minor && pc === (root + 11) % 12;
}

export const scaleNames = (root, minor) => scale(root, minor).map(p => NOTES[p]).join('  ');

/** "I   C", "ii   Dm", ... */
export function diatonic(root, minor) {
  const sc = scale(root, minor), out = [];
  for (let i = 0; i < 7; i++) out.push((minor ? MIN_N : MAJ_N)[i] + '   ' + NOTES[sc[i]] + (minor ? MIN_Q : MAJ_Q)[i]);
  if (minor) out.push('V (harmonic)   ' + NOTES[sc[4]]);
  return out;
}

/** Common progressions spelled in this key. */
export function progressions(root, minor) {
  const degs = minor
    ? [[0, 5, 2, 6], [0, 3, 4, 0], [0, 6, 5, 6], [0, 3, 6, 2], [0, 5, 3, -4]]
    : [[0, 4, 5, 3], [5, 3, 0, 4], [0, 3, 4, 0], [1, 4, 0, 0], [0, 5, 3, 4]];
  const labels = minor
    ? ['i  VI  III  VII', 'i  iv  v  i', 'i  VII  VI  VII', 'i  iv  VII  III', 'i  VI  iv  V']
    : ['I  V  vi  IV', 'vi  IV  I  V', 'I  IV  V  I', 'ii  V  I', 'I  vi  IV  V'];
  const sc = scale(root, minor), out = [];
  for (let i = 0; i < degs.length; i++) {
    const count = (!minor && i === 3) ? 3 : 4, names = [];
    for (let k = 0; k < count; k++) {
      const d = degs[i][k];
      names.push(d === -4 ? NOTES[sc[4]] : NOTES[sc[d]] + (minor ? MIN_Q : MAJ_Q)[d]);
    }
    out.push(labels[i] + '      ' + names.join(' - '));
  }
  return out;
}

/**
 * For each bar with melody, picks the chords from the key whose tones cover the most of
 * the melody (weighted by note length, with the downbeat counting extra).
 */
export function harmonize(notes, root, minor, beatFrames, beatsPerBar) {
  const out = [];
  if (root < 0 || notes.length === 0) return out;
  const bar = beatFrames * Math.max(1, beatsPerBar);
  const sc = scale(root, minor), q = minor ? MIN_Q : MAJ_Q;
  const n = minor ? 8 : 7, tones = [], names = [], roots = [];
  for (let i = 0; i < 7; i++) {
    tones.push([sc[i], sc[(i + 2) % 7], sc[(i + 4) % 7]]);
    names.push(NOTES[sc[i]] + q[i]);
    roots.push(sc[i]);
  }
  if (minor) {
    const r = sc[4];
    tones.push([r, (r + 4) % 12, (r + 7) % 12]);
    names.push(NOTES[r]);
    roots.push(r);
  }
  const last = notes[notes.length - 1];
  const bars = Math.ceil((last.start + last.len) / bar);
  for (let b = 0; b < bars; b++) {
    const bs = b * bar, be = bs + bar;
    const w = new Float64Array(12);
    let total = 0;
    for (const nt of notes) {
      const s0 = Math.max(bs, nt.start), s1 = Math.min(be, nt.start + nt.len);
      if (s1 <= s0) continue;
      let weight = s1 - s0;
      if (nt.start <= bs + beatFrames * 0.25 && nt.start + nt.len > bs) weight *= 1.5;
      w[mod12(nt.midi)] += weight;
      total += weight;
    }
    if (total <= 0) continue;
    let best = -1, second = -1, bestS = -1e9, secondS = -1e9;
    for (let c = 0; c < n; c++) {
      let cover = 0;
      for (const t of tones[c]) cover += w[t];
      let score = cover / total - (c === 6 && !minor ? 0.15 : 0) - (c === 1 && minor ? 0.15 : 0);
      if (c === 0) score += 0.05;
      if (score > bestS) { second = best; secondS = bestS; best = c; bestS = score; }
      else if (score > secondS) { second = c; secondS = score; }
    }
    out.push({
      bar: b, best: names[best], bestRoot: roots[best],
      bestMinor: names[best].endsWith('m'), bestDim: names[best].endsWith('dim'),
      alt: second >= 0 && bestS - secondS < 0.12 ? names[second] : null,
    });
  }
  return out;
}
