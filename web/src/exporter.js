// Renders the jam offline (same FX chain as playback) and encodes it. Ported from Exporter.java.
import { Mixer } from './mixer.js';
import { FlacWriter } from './flac.js';
import { Bytes } from './bytes.js';
import { Mp3Encoder } from '@breezystack/lamejs';
import { Muxer, ArrayBufferTarget } from 'mp4-muxer';
import { FORMATS } from './formats.js';


export const lengthFrames = s => s.tracks.reduce((m, t) => Math.max(m, t.data.length), 0);

/** Calls sink(pcmInterleavedInt16, frames) block by block. */
export function renderMix(s, sr, total, sink, progress) {
  const m = new Mixer(s, sr);
  const bs = 2048;
  const out = new Float32Array(bs * 2), p = new Float64Array(bs), pcm = new Int16Array(bs * 2);
  let lastReport = 0;
  for (let f = 0; f < total; f += bs) {
    const n = Math.min(bs, total - f);
    for (let i = 0; i < n; i++) p[i] = f + i;
    m.render(out, p, n, -1, false);
    for (let i = 0; i < n * 2; i++) {
      const x = Math.round(out[i] * 32767);
      pcm[i] = x > 32767 ? 32767 : x < -32768 ? -32768 : x;
    }
    sink(pcm, n);
    if (progress && f - lastReport > sr) { lastReport = f; progress(f / total); }
  }
}

function wavHeader(sr, frames) {
  const h = new Bytes(44), dataLen = frames * 4;
  h.ascii('RIFF'); h.le32(36 + dataLen); h.ascii('WAVEfmt ');
  h.le32(16); h.le16(1); h.le16(2); h.le32(sr); h.le32(sr * 4); h.le16(4); h.le16(16);
  h.ascii('data'); h.le32(dataLen);
  return h.result();
}


/** Returns a Blob of the rendered jam in the given format id. */
export async function exportJam(s, sr, fmtId, progress) {
  const len = lengthFrames(s);
  if (len === 0) throw new Error('nothing recorded yet');
  const total = len + sr * 2; // 2 s tail for echo / reverb
  const fmt = FORMATS.find(f => f.id === fmtId);
  const parts = [];

  if (fmtId === 'wav') {
    parts.push(wavHeader(sr, total));
    renderMix(s, sr, total, (pcm, n) => parts.push(new Uint8Array(pcm.buffer.slice(0, n * 4))), progress);
  } else if (fmtId === 'flac') {
    const w = new FlacWriter(sr, total, b => parts.push(b));
    renderMix(s, sr, total, (pcm, n) => w.write(pcm, n), progress);
    w.finish();
  } else if (fmtId === 'mp3') {
    const enc = new Mp3Encoder(2, sr, 192);
    const l = new Int16Array(2048), r = new Int16Array(2048);
    renderMix(s, sr, total, (pcm, n) => {
      for (let i = 0; i < n; i++) { l[i] = pcm[2 * i]; r[i] = pcm[2 * i + 1]; }
      const b = enc.encodeBuffer(l.subarray(0, n), r.subarray(0, n));
      if (b.length) parts.push(new Uint8Array(b.buffer, b.byteOffset, b.length).slice());
    }, progress);
    const b = enc.flush();
    if (b.length) parts.push(new Uint8Array(b.buffer, b.byteOffset, b.length).slice());
  } else if (fmtId === 'm4a') {
    const target = new ArrayBufferTarget();
    const muxer = new Muxer({ target, audio: { codec: 'aac', sampleRate: sr, numberOfChannels: 2 }, fastStart: 'in-memory' });
    let err = null;
    const enc = new AudioEncoder({ output: (chunk, meta) => muxer.addAudioChunk(chunk, meta), error: e => { err = e; } });
    enc.configure({ codec: 'mp4a.40.2', sampleRate: sr, numberOfChannels: 2, bitrate: 256000 });
    let framesIn = 0;
    renderMix(s, sr, total, (pcm, n) => {
      const f32 = new Float32Array(n * 2);
      for (let i = 0; i < n * 2; i++) f32[i] = pcm[i] / 32768;
      const d = new AudioData({ format: 'f32', sampleRate: sr, numberOfFrames: n, numberOfChannels: 2, timestamp: Math.round(framesIn * 1e6 / sr), data: f32 });
      enc.encode(d);
      d.close();
      framesIn += n;
    }, progress);
    await enc.flush();
    enc.close();
    if (err) throw err;
    muxer.finalize();
    parts.push(new Uint8Array(target.buffer));
  } else {
    throw new Error('unknown format');
  }
  return new Blob(parts, { type: fmt.mime });
}

/** Mixes the given tracks (with their effects, panned centre) to one mono Int16Array, trimmed after the last sound. */
export function bounce(s, sr) {
  const len = lengthFrames(s) + sr;
  const out = new Int16Array(len);
  let at = 0;
  const tracks = s.tracks.map(t => ({ ...t, pan: 0 }));
  renderMix({ ...s, tracks, master: 1 }, sr, len, (pcm, n) => {
    for (let i = 0; i < n; i++) {
      const v = Math.round((pcm[2 * i] + pcm[2 * i + 1]) * 0.5);
      out[at + i] = v;
    }
    at += n;
  }, null);
  let last = -1;
  for (let i = out.length - 1; i >= 0; i--) if (out[i] > 328 || out[i] < -328) { last = i; break; }
  return last >= 0 && last + sr / 10 < out.length ? out.slice(0, last + sr / 10) : out;
}
