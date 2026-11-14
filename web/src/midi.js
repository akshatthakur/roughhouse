// Standard MIDI File (type 1) writer. Ported from Midi.java.
import { Bytes, utf8 } from './bytes.js';
import { NOTES } from './pitch.js';
import { quarterFrames } from './timing.js';

export const PPQ = 480;

/** MIDI notes for a chord name like "Am", "F#", "Bdim", voiced around middle C. */
export function voice(name) {
  let root = -1, i = 0;
  const flat = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B'];
  const other = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
  for (const names of [NOTES, flat, other]) {
    for (let k = 0; k < 12; k++) {
      if (name.startsWith(names[k]) && names[k].length > i) { root = k; i = names[k].length; }
    }
  }
  if (root < 0) return [];
  const q = name.substring(i);
  const third = q.startsWith('m') || q.startsWith('dim') ? 3 : 4;
  const fifth = q.startsWith('dim') ? 6 : 7;
  const base = 48 + root;
  return [base, base + third, base + fifth];
}

/** Key signature as sharps (+) or flats (-) for the MIDI key meta event. */
export function sharps(root, minor) {
  const major = minor ? (root + 3) % 12 : root;
  return [0, -5, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5][major];
}

function varLen(t, v) {
  let buf = v & 0x7F;
  while ((v = Math.floor(v / 128)) > 0) buf = (buf * 256) + (0x80 | (v & 0x7F));
  for (;;) {
    t.u8(buf & 0xFF);
    if (buf & 0x80) buf = Math.floor(buf / 256);
    else break;
  }
}

function meta(t, delta, type, data) {
  varLen(t, delta);
  t.u8(0xFF);
  t.u8(type);
  varLen(t, data.length);
  t.bytes(data);
}

function chunk(os, body) {
  os.ascii('MTrk');
  os.be32(body.length);
  os.bytes(body);
}

function events(name, ev) {
  ev.sort((a, b) => a[0] !== b[0] ? a[0] - b[0] : a[1] - b[1]);
  const t = new Bytes();
  meta(t, 0, 0x03, utf8(name));
  let last = 0;
  for (const e of ev) {
    varLen(t, e[0] - last);
    last = e[0];
    t.u8(e[2]); t.u8(e[3] & 0x7F); t.u8(e[4] & 0x7F);
  }
  meta(t, 0, 0x2F, []);
  return t.result();
}

const ticks = (frame, qf) => Math.max(0, Math.round(frame / qf * PPQ));

/** parts: [{name, notes}], chords: [{start, len, midis}]. Returns Uint8Array. */
export function writeMidi(s, sr, parts, chords) {
  const qf = quarterFrames(s, sr);
  const os = new Bytes();
  os.ascii('MThd');
  os.be32(6);
  os.be16(1);
  os.be16(1 + parts.length + (chords.length ? 1 : 0));
  os.be16(PPQ);

  const t = new Bytes();
  meta(t, 0, 0x03, utf8('Roughhouse'));
  const us = Math.round(qf / sr * 1e6);
  meta(t, 0, 0x51, [(us >> 16) & 0xFF, (us >> 8) & 0xFF, us & 0xFF]);
  const dd = Math.round(Math.log2(Math.max(1, s.beatUnit)));
  meta(t, 0, 0x58, [s.beatsPerBar, dd, 24, 8]);
  if (s.keyRoot >= 0) meta(t, 0, 0x59, [sharps(s.keyRoot, s.keyMinor) & 0xFF, s.keyMinor ? 1 : 0]);
  meta(t, 0, 0x2F, []);
  chunk(os, t.result());

  let ch = 0;
  for (const p of parts) {
    const ev = [];
    for (const n of p.notes) {
      const on = ticks(n.start, qf), off = Math.max(on + 1, ticks(n.start + n.len, qf));
      ev.push([on, 1, 0x90 | ch, n.midi, 96]);
      ev.push([off, 0, 0x80 | ch, n.midi, 0]);
    }
    chunk(os, events(p.name, ev));
    ch = Math.min(ch + 1, 8);
  }
  if (chords.length) {
    const ev = [];
    const cc = ch === 9 ? 10 : ch;
    for (const c of chords) {
      const on = ticks(c.start, qf), off = Math.max(on + 1, ticks(c.start + c.len, qf) - 1);
      for (const m of c.midis) {
        ev.push([on, 1, 0x90 | cc, m, 72]);
        ev.push([off, 0, 0x80 | cc, m, 0]);
      }
    }
    chunk(os, events('chords', ev));
  }
  return os.result();
}
