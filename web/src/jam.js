// Jams and tracks, stored in IndexedDB with the same files the Android app keeps in a jam folder
// (session.json, t<id>.pcm, a<id>.json), so backups move freely between the two.
import { PB, SR, beatFrames, barFrames } from './timing.js';
import { toJson as analysisToJson, fromJson as analysisFromJson, keyName } from './analyzer.js';

const FX_DEFAULTS = { vol: 0.8, pan: 0, lowCut: 0, bass: 0, treble: 0, comp: 0, echo: 0, echoFb: 0.35, echoDiv: 3, rev: 0 };
export const FX_KEYS = Object.keys(FX_DEFAULTS);

export class Track {
  constructor(id, name) {
    this.id = id;
    this.name = name;
    this.data = new Int16Array(0);
    this.peaks = new Float32Array(0);
    this.ver = 1;          // bumped whenever the audio changes, so the audio thread gets a fresh copy
    this.dirty = false;
    Object.assign(this, FX_DEFAULTS);
    this.mute = false;
    this.solo = false;
    this.vocal = false;
    this.meter = 0;
    this.analysis = null;
    this.analysisDirty = false;
    this.showNotes = false;
    this.undo = null;
  }
  isEmpty() { return this.data.length === 0; }
  fxActive() {
    return this.lowCut > 0 || Math.abs(this.bass) > 0.1 || Math.abs(this.treble) > 0.1 || this.comp > 0 || this.echo > 0 || this.rev > 0;
  }
  resetFx() { Object.assign(this, FX_DEFAULTS); }
  copySettingsFrom(o) { for (const k of FX_KEYS) this[k] = o[k]; }
  invalidateAnalysis() { this.analysis = null; this.showNotes = false; }
  setData(d) { this.data = d; this.ver++; this.dirty = true; this.computePeaks(); }
  computePeaks() {
    const d = this.data, n = Math.ceil(d.length / PB), p = new Float32Array(n);
    for (let b = 0; b < n; b++) {
      const s = b * PB, e = Math.min(d.length, s + PB);
      let m = 0;
      for (let i = s; i < e; i++) { const v = d[i] < 0 ? -d[i] : d[i]; if (v > m) m = v; }
      p[b] = m / 32768;
    }
    this.peaks = p;
  }
}

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
const pad2 = n => String(n).padStart(2, '0');

export class Jam {
  constructor(id) {
    this.id = id;
    this.name = '';
    this.bpm = 100;
    this.loop = false;
    this.metronome = false;
    this.countIn = true;
    this.loopIn = 0;
    this.loopOut = -1;
    this.metroVol = 0.6;
    this.master = 0.9;
    this.beatsPerBar = 4;
    this.beatUnit = 4;
    this.keyRoot = -1;
    this.keyMinor = false;
    this.lyrics = '';
    this.markers = [];
    this.tracks = [];
  }

  static create() {
    const now = Date.now(), d = new Date(now);
    const s = new Jam('s' + now);
    s.name = `jam ${MONTHS[d.getMonth()]} ${d.getDate()}, ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
    return s;
  }

  lengthFrames() { return this.tracks.reduce((m, t) => Math.max(m, t.data.length), 0); }
  nextId() { return this.tracks.reduce((m, t) => Math.max(m, t.id), 0) + 1; }
  beatFrames() { return beatFrames(this, SR); }
  barFrames() { return barFrames(this, SR); }
  timeSig() { return this.beatsPerBar + '/' + this.beatUnit; }
  keyLabel() { return keyName(this.keyRoot, this.keyMinor); }
  sortedMarkers() { return [...this.markers].sort((a, b) => a.bar - b.bar); }

  sessionJson() {
    return {
      name: this.name, bpm: this.bpm, loop: this.loop, metronome: this.metronome, countIn: this.countIn,
      loopIn: this.loopIn, loopOut: this.loopOut, metroVol: this.metroVol, beatsPerBar: this.beatsPerBar,
      beatUnit: this.beatUnit, keyRoot: this.keyRoot, keyMinor: this.keyMinor, lyrics: this.lyrics,
      markers: this.markers.map(m => ({ bar: m.bar, name: m.name })),
      len: this.lengthFrames(),
      tracks: this.tracks.map(t => {
        const j = { id: t.id, name: t.name };
        for (const k of FX_KEYS) j[k] = t[k];
        j.mute = t.mute; j.solo = t.solo; j.vocal = t.vocal; j.showNotes = t.showNotes;
        return j;
      }),
    };
  }

  /** files: Map name -> Uint8Array | string (pcm and analysis), as stored or unzipped. */
  static fromFiles(id, o, files) {
    const s = new Jam(id);
    s.name = lowerDefault(o.name ?? id);
    s.bpm = num(o.bpm, 100);
    s.loop = !!o.loop;
    s.metronome = !!o.metronome;
    s.countIn = o.countIn ?? true;
    s.loopIn = num(o.loopIn, 0);
    s.loopOut = num(o.loopOut, -1);
    s.metroVol = num(o.metroVol, 0.6);
    s.beatsPerBar = num(o.beatsPerBar, 4);
    s.beatUnit = num(o.beatUnit, 4);
    s.keyRoot = num(o.keyRoot, -1);
    s.keyMinor = !!o.keyMinor;
    s.lyrics = o.lyrics ?? '';
    for (const m of o.markers || []) s.markers.push({ bar: num(m.bar, 0), name: m.name ?? 'section' });
    (o.tracks || []).forEach((j, i) => {
      const t = new Track(j.id, lowerDefault(j.name ?? 'track ' + (i + 1)));
      t.vocal = !!j.vocal;
      for (const k of FX_KEYS) t[k] = num(j[k], t[k]);
      t.mute = !!j.mute;
      t.solo = !!j.solo;
      const pcm = files.get('t' + t.id + '.pcm');
      if (pcm) {
        const b = pcm instanceof Uint8Array ? pcm : new Uint8Array(pcm);
        const copy = new Uint8Array(b.length & ~1);
        copy.set(b.subarray(0, copy.length));
        t.data = new Int16Array(copy.buffer);
      }
      t.computePeaks();
      const an = files.get('a' + t.id + '.json');
      if (an) {
        try {
          t.analysis = analysisFromJson(JSON.parse(typeof an === 'string' ? an : new TextDecoder().decode(an)));
          t.showNotes = !!j.showNotes;
        } catch { /* a damaged analysis just means analyzing again */ }
      }
      s.tracks.push(t);
    });
    return s;
  }

  /** Every file of the jam as bytes, for backups. */
  toFiles() {
    const enc = new TextEncoder(), out = [{ name: 'session.json', data: enc.encode(JSON.stringify(this.sessionJson())) }];
    for (const t of this.tracks) {
      out.push({ name: 't' + t.id + '.pcm', data: new Uint8Array(t.data.buffer, t.data.byteOffset, t.data.byteLength) });
      if (t.analysis) out.push({ name: 'a' + t.id + '.json', data: enc.encode(JSON.stringify(analysisToJson(t.analysis))) });
    }
    return out;
  }
}

const num = (v, d) => (typeof v === 'number' && Number.isFinite(v)) ? v : d;

/** Names generated by older versions ("Jam Nov 13, 15:40", "Track 2") become lowercase; typed names stay. */
export function lowerDefault(n) {
  if (/^Jam [A-Z][a-z]{2,3}\.? \d{1,2}, \d{1,2}:\d{2}$/.test(n) || /^Track \d+( copy)*$/.test(n)) return n.toLowerCase();
  return n;
}

/**
 * Lyric lines pinned to bars. "[9] words" or "[bar 9] words" pins it (and the untagged lines
 * after it) to bar 9. Returns a sorted array of [bar, text] (1-based bars).
 */
export function pinnedLyrics(text) {
  const out = new Map();
  let bar = -1;
  for (const line of text.split('\n')) {
    const m = /^\s*\[(?:bar\s*)?(\d+)\]\s*(.*)$/i.exec(line);
    let words;
    if (m) { bar = parseInt(m[1], 10); words = m[2].trim(); } else words = line.trim();
    if (bar < 1 || !words) continue;
    out.set(bar, out.has(bar) ? out.get(bar) + ' / ' + words : words);
  }
  return [...out.entries()].sort((a, b) => a[0] - b[0]);
}

export const SAFE_NAME = /^(session\.json|t\d+\.pcm|a\d+\.json)$/;

// ------------------------------------------------------------------ IndexedDB

let dbp = null;
function db() {
  if (!dbp) {
    dbp = new Promise((res, rej) => {
      const r = indexedDB.open('roughhouse', 1);
      r.onupgradeneeded = () => {
        const d = r.result;
        d.createObjectStore('jams');
        d.createObjectStore('files');
      };
      r.onsuccess = () => res(r.result);
      r.onerror = () => rej(r.error);
    });
  }
  return dbp;
}

const done = tx => new Promise((res, rej) => { tx.oncomplete = res; tx.onerror = () => rej(tx.error); tx.onabort = () => rej(tx.error || new Error('storage aborted')); });
const req = r => new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
const range = id => IDBKeyRange.bound(id + '/', id + '/￿');

export async function saveJam(s) {
  const d = await db();
  const keep = new Set();
  const tx = d.transaction(['jams', 'files'], 'readwrite');
  const files = tx.objectStore('files');
  const existing = new Set(await req(files.getAllKeys(range(s.id))));
  for (const t of s.tracks) {
    const fn = s.id + '/t' + t.id + '.pcm';
    keep.add(fn);
    if (t.dirty || !existing.has(fn)) {
      files.put(t.data.slice().buffer, fn);
      t.dirty = false;
    }
    if (t.analysis) {
      const an = s.id + '/a' + t.id + '.json';
      keep.add(an);
      if (t.analysisDirty || !existing.has(an)) {
        files.put(JSON.stringify(analysisToJson(t.analysis)), an);
        t.analysisDirty = false;
      }
    }
  }
  for (const k of existing) if (!keep.has(k)) files.delete(k);
  tx.objectStore('jams').put(s.sessionJson(), s.id);
  await done(tx);
}

export async function loadJam(id) {
  const d = await db();
  const tx = d.transaction(['jams', 'files'], 'readonly');
  const o = await req(tx.objectStore('jams').get(id));
  if (!o) return null;
  const fs = tx.objectStore('files');
  const keys = await req(fs.getAllKeys(range(id)));
  const vals = await req(fs.getAll(range(id)));
  const files = new Map();
  keys.forEach((k, i) => files.set(k.substring(id.length + 1), vals[i]));
  try { return Jam.fromFiles(id, o, files); } catch { return null; }
}

/** [{id, name, len, tracks}] newest first, without loading audio. */
export async function listJams() {
  const d = await db();
  const tx = d.transaction('jams', 'readonly');
  const st = tx.objectStore('jams');
  const keys = await req(st.getAllKeys());
  const vals = await req(st.getAll());
  return keys.map((id, i) => ({ id, name: lowerDefault(vals[i].name ?? id), len: vals[i].len || 0, tracks: (vals[i].tracks || []).length }))
    .sort((a, b) => b.id.localeCompare(a.id, 'en', { numeric: true }));
}

export async function deleteJam(id) {
  const d = await db();
  const tx = d.transaction(['jams', 'files'], 'readwrite');
  tx.objectStore('jams').delete(id);
  tx.objectStore('files').delete(range(id));
  await done(tx);
}

/** Stores an unzipped backup as a new jam and returns its id. */
export async function importFiles(entries) {
  const files = new Map();
  for (const e of entries) if (SAFE_NAME.test(e.name)) files.set(e.name, e.data);
  if (!files.has('session.json')) throw new Error("that file isn't a Roughhouse jam");
  const o = JSON.parse(new TextDecoder().decode(files.get('session.json')));
  const s = Jam.fromFiles('s' + Date.now(), o, files);
  for (const t of s.tracks) { t.dirty = true; if (t.analysis) t.analysisDirty = true; }
  await saveJam(s);
  return s;
}
