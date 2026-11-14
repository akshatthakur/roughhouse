// Checks the ported audio code against reference tools (ffmpeg) and known inputs.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { writeFileSync, readFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { exportJam, bounce } from '../src/exporter.js';
import { analyzeTrack, analyze } from '../src/analyzer.js';
import { harmonize, diatonic, progressions, inKey } from '../src/theory.js';
import { writeMidi, voice, sharps } from '../src/midi.js';
import { writeZip, readZip } from '../src/zip.js';
import { Jam, Track, pinnedLyrics } from '../src/jam.js';
import { Engine } from '../src/engine.js';
import * as Editor from '../src/editor.js';
import { Mixer } from '../src/mixer.js';

const SR = 48000;
const dir = mkdtempSync(join(tmpdir(), 'rh-'));
const hz = m => 440 * Math.pow(2, (m - 69) / 12);

/** A sung-ish line: notes as [midi, beats] at the given bpm, with a little vibrato-free sine + harmonics. */
function melody(notes, bpm, amp = 0.4) {
  const beat = 60 * SR / bpm;
  const total = Math.round(notes.reduce((a, n) => a + n[1], 0) * beat);
  const d = new Int16Array(total);
  let t0 = 0;
  for (const [m, beats] of notes) {
    const len = Math.round(beats * beat), f = hz(m);
    for (let i = 0; i < len - 600; i++) {
      const env = Math.min(1, i / 300, (len - 600 - i) / 300);
      const ph = 2 * Math.PI * f * i / SR;
      d[t0 + i] = Math.round(32767 * amp * env * (Math.sin(ph) + 0.3 * Math.sin(2 * ph) + 0.1 * Math.sin(3 * ph)) / 1.4);
    }
    t0 += len;
  }
  return d;
}

/** Kick-like thumps on every beat plus chords, for tempo/key. */
function groove(bpm, seconds, chordsMidi) {
  const n = seconds * SR, d = new Float32Array(n), beat = 60 * SR / bpm;
  for (let b = 0; b * beat < n; b++) {
    const s = Math.round(b * beat);
    for (let i = 0; i < 4000 && s + i < n; i++) d[s + i] += 0.6 * Math.sin(2 * Math.PI * 60 * i / SR) * Math.exp(-i / 800);
  }
  const bar = beat * 4;
  for (let i = 0; i < n; i++) {
    const ch = chordsMidi[Math.floor(i / bar) % chordsMidi.length];
    for (const m of ch) d[i] += 0.08 * Math.sin(2 * Math.PI * hz(m) * i / SR);
  }
  const out = new Int16Array(n);
  for (let i = 0; i < n; i++) out[i] = Math.max(-32768, Math.min(32767, Math.round(d[i] * 32767)));
  return out;
}

function jamWith(tracks) {
  return { bpm: 120, beatsPerBar: 4, beatUnit: 4, metroVol: 0.6, master: 0.9, tracks };
}
const trk = (id, data, extra = {}) => ({ id, data, vol: 0.8, pan: 0, lowCut: 0, bass: 0, treble: 0, comp: 0, echo: 0, echoFb: 0.35, echoDiv: 3, rev: 0, mute: false, solo: false, meter: 0, ...extra });

test('FLAC export decodes bit-exact to the WAV export (ffmpeg)', async () => {
  const a = melody([[60, 2], [64, 2], [67, 2], [72, 2]], 120);
  const b = groove(120, 4, [[48, 52, 55]]);
  const s = jamWith([trk(1, a, { pan: -0.4, echo: 0.3, rev: 0.4 }), trk(2, b, { pan: 0.5, comp: 0.6, bass: 4, treble: -3, lowCut: 60 })]);
  const wav = new Uint8Array(await (await exportJam(s, SR, 'wav')).arrayBuffer());
  const flac = new Uint8Array(await (await exportJam(s, SR, 'flac')).arrayBuffer());
  writeFileSync(join(dir, 'a.wav'), wav);
  writeFileSync(join(dir, 'a.flac'), flac);
  assert.ok(flac.length < wav.length * 0.9, 'flac should be smaller');
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', join(dir, 'a.flac'), '-f', 's16le', join(dir, 'flac.raw')]);
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', join(dir, 'a.wav'), '-f', 's16le', join(dir, 'wav.raw')]);
  const fr = readFileSync(join(dir, 'flac.raw')), wr = readFileSync(join(dir, 'wav.raw'));
  assert.equal(fr.length, wr.length);
  assert.ok(fr.equals(wr), 'decoded FLAC must match the WAV samples');
  // ffmpeg's own integrity check (CRCs)
  execFileSync('ffmpeg', ['-v', 'error', '-xerror', '-i', join(dir, 'a.flac'), '-f', 'null', '-']);
});

test('MP3 export is a valid 192k stereo stream of the right length', async () => {
  const a = melody([[57, 4], [60, 4]], 120);
  const mp3 = new Uint8Array(await (await exportJam(jamWith([trk(1, a)]), SR, 'mp3')).arrayBuffer());
  writeFileSync(join(dir, 'a.mp3'), mp3);
  const info = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_streams', '-of', 'json', join(dir, 'a.mp3')]).toString());
  const st = info.streams[0];
  assert.equal(st.codec_name, 'mp3');
  assert.equal(+st.sample_rate, 48000);
  assert.equal(st.channels, 2);
  const dur = +JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_format', '-of', 'json', join(dir, 'a.mp3')]).toString()).format.duration;
  assert.ok(Math.abs(dur - (a.length / SR + 2)) < 0.2, 'duration ' + dur);
});

test('transcription finds the sung notes', () => {
  const line = [[60, 1], [62, 1], [64, 1], [65, 1], [67, 2], [64, 1], [60, 1]];
  const a = melody(line, 100);
  const f = analyzeTrack(a, SR, 60 * SR / 100, 4);
  assert.deepEqual(f.notes.map(n => n.midi), line.map(n => n[0]));
  assert.equal(f.low, 60);
  assert.equal(f.high, 67);
  assert.ok(Math.abs(f.avgCents) < 5);
  const beat = 60 * SR / 100;
  assert.ok(Math.abs(f.notes[1].start - beat) < SR * 0.05, 'second note starts on beat 2');
});

test('tempo and key of a groove', () => {
  const C = [48, 52, 55, 60], F = [53, 57, 60, 65], G = [55, 59, 62, 67], Am = [57, 60, 64, 69];
  const g = groove(96, 20, [C, F, G, C, Am, F, G, C]);
  const r = analyze(g, SR);
  assert.ok(Math.abs(r.bpm - 96) <= 1 || Math.abs(r.bpm - 192) <= 2 || Math.abs(r.bpm - 48) <= 1, 'bpm ' + r.bpm);
  assert.equal(r.keyRoot, 0);
  assert.equal(r.minor, false);
  const f = analyzeTrack(g, SR, 60 * SR / 96, 4);
  assert.deepEqual(f.chords.slice(0, 5).map(c => c.name), ['C', 'F', 'G', 'C', 'Am']);
});

test('theory helpers', () => {
  assert.deepEqual(diatonic(0, false).slice(0, 3), ['I   C', 'ii   Dm', 'iii   Em']);
  assert.equal(diatonic(9, true)[7], 'V (harmonic)   E');
  assert.equal(progressions(0, false)[0], 'I  V  vi  IV      C - G - Am - F');
  assert.ok(inKey(68, 9, true)); // G# in A minor (harmonic)
  const sg = harmonize([{ start: 0, len: 48000, midi: 64 }, { start: 48000, len: 48000, midi: 67 }], 0, false, 24000, 4);
  assert.equal(sg[0].best, 'C');
});

test('MIDI writer: header, tracks and chord voicing', () => {
  assert.deepEqual(voice('Am'), [57, 60, 64]);
  assert.deepEqual(voice('Db'), [49, 53, 56]);
  assert.deepEqual(voice('Bdim'), [59, 62, 65]);
  assert.equal(sharps(9, true), 0);
  assert.equal(sharps(2, false), 2);
  const s = { bpm: 120, beatsPerBar: 3, beatUnit: 4, keyRoot: 7, keyMinor: false };
  const b = writeMidi(s, SR, [{ name: 'vox', notes: [{ start: 0, len: 24000, midi: 67 }] }], [{ start: 0, len: 72000, midis: [55, 59, 62] }]);
  const txt = Buffer.from(b).toString('latin1');
  assert.equal(txt.slice(0, 4), 'MThd');
  assert.equal(b[11], 3); // conductor + melody + chords
  assert.equal((txt.match(/MTrk/g) || []).length, 3);
  assert.ok(txt.includes('\xFF\x51\x03\x07\xA1\x20')); // 500000 us per quarter
  assert.ok(txt.includes('\xFF\x58\x04\x03\x02')); // 3/4
  assert.ok(txt.includes('\xFF\x59\x02\x01\x00')); // G major: one sharp
});

test('zip backup round trip, readable by python zipfile', async () => {
  const s = new Jam('s1');
  s.name = 'test jam';
  s.lyrics = '[2] hello there';
  s.markers.push({ bar: 4, name: 'chorus' });
  const t = new Track(1, 'vox');
  t.setData(melody([[60, 2]], 120));
  t.analysis = analyzeTrack(t.data, SR, 24000, 4);
  t.showNotes = true;
  s.tracks.push(t);
  const blob = await writeZip(s.toFiles());
  const bytes = new Uint8Array(await blob.arrayBuffer());
  writeFileSync(join(dir, 'b.zip'), bytes);
  const names = execFileSync('python3', ['-c', 'import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None; print(",".join(z.namelist()))', join(dir, 'b.zip')]).toString().trim();
  assert.equal(names, 'session.json,t1.pcm,a1.json');
  const back = await readZip(bytes);
  const files = new Map(back.map(e => [e.name, e.data]));
  const o = JSON.parse(new TextDecoder().decode(files.get('session.json')));
  const j = Jam.fromFiles('s2', o, files);
  assert.equal(j.name, 'test jam');
  assert.equal(j.tracks[0].data.length, t.data.length);
  assert.deepEqual(Array.from(j.tracks[0].data.slice(1000, 1010)), Array.from(t.data.slice(1000, 1010)));
  assert.equal(j.tracks[0].analysis.notes[0].midi, 60);
  assert.equal(j.tracks[0].showNotes, true);
  assert.deepEqual(j.markers, [{ bar: 4, name: 'chorus' }]);
  // a zip written by Java's ZipOutputStream (data descriptors, deflate) opens too
  execFileSync('python3', ['-c', `
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('session.json', '{"name":"from android","bpm":90,"tracks":[{"id":3,"name":"Track 1"}]}')
    z.writestr('t3.pcm', bytes([1,0,2,0,255,255]))
    z.writestr('../evil.txt', 'x')
`, join(dir, 'android.zip')]);
  const ents = await readZip(readFileSync(join(dir, 'android.zip')));
  const fm = new Map(ents.filter(e => /^(session\.json|t\d+\.pcm|a\d+\.json)$/.test(e.name)).map(e => [e.name, e.data]));
  const aj = Jam.fromFiles('s3', JSON.parse(new TextDecoder().decode(fm.get('session.json'))), fm);
  assert.equal(aj.bpm, 90);
  assert.equal(aj.tracks[0].name, 'track 1');
  assert.deepEqual(Array.from(aj.tracks[0].data), [1, 2, -1]);
});

test('pinned lyrics', () => {
  assert.deepEqual(pinnedLyrics('intro words\n[3] first line\nsecond line\n[bar 9] chorus\n\n[9] again'),
    [[3, 'first line / second line'], [9, 'chorus / again']]);
});

test('take placement: latency, punch-in and crossfades', () => {
  const e = new Engine({});
  const t = new Track(1, 'x');
  t.setData(new Int16Array(SR).fill(1000));
  e.recTrack = t;
  e.recOrigin = 24000;
  e.latencyFrames = 4800;
  e.punchStart = 24000;
  e.inputGain = 1;
  const rec = new Float32Array(SR).fill(0.5);
  e.finalizeTake(rec);
  // first kept recorded sample lands at the punch point; earlier audio untouched
  assert.equal(t.data[23999], 1000);
  assert.equal(t.data[24000], 1000); // crossfade starts from the old audio
  assert.equal(t.data[24000 + 240], 16384);
  assert.equal(t.data.length, 24000 + SR - 4800);
  assert.equal(t.undo.length, SR);
});

test('editor: trim, shift, fades', () => {
  const d = new Int16Array(10000).fill(10000);
  assert.equal(Editor.trimAfter(d, 5000).length, 5000);
  const tb = Editor.trimBefore(d, 5000);
  assert.equal(tb[4999], 0);
  assert.equal(tb[5000 + 240], 10000);
  assert.equal(Editor.shift(d, 100).length, 10100);
  assert.equal(Editor.shift(d, -100).length, 9900);
  const fo = Editor.fadeOutFrom(d, 5000);
  assert.ok(fo[9999] < 10);
});

test('mixer: centre pan is equal power, limiter keeps peaks under 1', () => {
  const s = jamWith([trk(1, new Int16Array(512).fill(16384), { vol: 1 })]);
  s.master = 1;
  const m = new Mixer(s, SR);
  const out = new Float32Array(512), p = new Float64Array(256);
  for (let i = 0; i < 256; i++) p[i] = i;
  m.render(out, p, 256, -1, false);
  assert.ok(Math.abs(out[0] - 0.5) < 1e-6 && Math.abs(out[1] - 0.5) < 1e-6);
  const loud = jamWith([trk(1, new Int16Array(512).fill(32767), { vol: 1.5 })]);
  const m2 = new Mixer(loud, SR);
  m2.render(out, p, 256, -1, false);
  assert.ok(out[0] < 1 && out[0] > 0.8);
});

test('bounce mixes to mono and trims the tail', () => {
  const a = melody([[60, 1]], 120);
  const out = bounce(jamWith([trk(1, a), trk(2, a, { pan: 1 })]), SR);
  assert.ok(out.length < a.length + SR);
  assert.ok(out.length >= a.length - 1000);
});
