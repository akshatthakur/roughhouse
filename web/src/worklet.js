// Audio thread: playback through the mixer, mic capture for takes, live pitch.
// The UI thread owns the jam; it mirrors track audio and settings here by message.
import { Mixer } from './mixer.js';
import { PitchTap } from './pitch.js';
import { SR } from './timing.js';

const PARAMS = ['vol', 'pan', 'lowCut', 'bass', 'treble', 'comp', 'echo', 'echoFb', 'echoDiv', 'rev', 'mute', 'solo'];
const JAMKEYS = ['bpm', 'beatsPerBar', 'beatUnit', 'metroVol', 'master', 'loop', 'loopIn', 'loopOut', 'metronome'];

class RoughhouseProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.s = { tracks: [], bpm: 100, beatsPerBar: 4, beatUnit: 4, metroVol: 0.6, master: 0.9, loop: false, loopIn: 0, loopOut: -1, metronome: false };
    this.byId = new Map();
    this.running = false;
    this.pos = 0;
    this.startPos = 0;
    this.mixer = null;
    this.out = new Float32Array(256);
    this.p = new Float64Array(128);
    this.rec = false;
    this.recId = -1;
    this.calibrating = false;
    this.monitoring = false;
    this.pitchOn = false;
    this.tap = null;
    this.gain = 1;
    this.chunk = new Float32Array(8192);
    this.chunkLen = 0;
    this.inputLevel = 0;
    this.blocks = 0;
    this.port.onmessage = e => this.onMsg(e.data);
  }

  onMsg(m) {
    switch (m.type) {
      case 'jam':
        for (const k of JAMKEYS) if (k in m.s) this.s[k] = m.s[k];
        break;
      case 'tracks': {
        // full order + params; data only for tracks whose audio changed
        const list = [];
        for (const t of m.tracks) {
          let x = this.byId.get(t.id);
          if (!x) { x = { id: t.id, data: new Int16Array(0), meter: 0 }; this.byId.set(t.id, x); }
          for (const k of PARAMS) x[k] = t[k];
          if (t.data) x.data = t.data;
          list.push(x);
        }
        for (const id of [...this.byId.keys()]) if (!list.some(x => x.id === id)) this.byId.delete(id);
        this.s.tracks = list;
        break;
      }
      case 'play': {
        if (this.running) break;
        this.startPos = m.from;
        const bar = Math.round(60 * SR / Math.max(30, this.s.bpm) * Math.max(1, this.s.beatsPerBar));
        this.pos = m.countIn ? m.from - bar : m.from;
        this.mixer = new Mixer(this.s, SR);
        this.mixer.calib = !!m.calib;
        this.calibrating = !!m.calib;
        this.running = true;
        break;
      }
      case 'seek':
        if (this.running) this.pos = Math.max(0, m.frame);
        break;
      case 'stop':
        this.endRec();
        this.running = false;
        this.calibrating = false;
        this.status(true);
        this.port.postMessage({ type: 'stopped' });
        break;
      case 'rec':
        // recording starts at the current block: recorded sample i maps to timeline frame recOrigin + i - latency
        this.rec = true;
        this.recId = m.id;
        this.gain = m.gain;
        this.chunkLen = 0;
        this.pitchOn = !!m.pitch;
        if (this.pitchOn) this.tap = new PitchTap(SR);
        this.port.postMessage({ type: 'recStart', origin: this.pos });
        break;
      case 'punchOut':
        this.endRec();
        break;
      case 'monitor':
        this.monitoring = m.on;
        this.gain = m.gain;
        this.tap = m.on ? new PitchTap(SR) : null;
        if (!m.on) { this.inputLevel = 0; }
        break;
      case 'gain':
        this.gain = m.gain;
        break;
    }
  }

  endRec() {
    if (!this.rec) return;
    this.flush();
    this.rec = false;
    this.pitchOn = false;
    if (!this.monitoring) this.tap = null;
    this.inputLevel = 0;
    this.port.postMessage({ type: 'recEnd' });
  }

  flush() {
    if (this.chunkLen === 0) return;
    const c = this.chunk.slice(0, this.chunkLen);
    this.port.postMessage({ type: 'recData', data: c }, [c.buffer]);
    this.chunkLen = 0;
  }

  status(force) {
    if (!force && (++this.blocks & 7) !== 0) return;
    const meters = {};
    for (const t of this.s.tracks) meters[t.id] = t.meter || 0;
    this.port.postMessage({
      type: 'st', running: this.running, pos: this.pos, rec: this.rec,
      levelL: this.mixer && this.running ? this.mixer.peakL : 0, levelR: this.mixer && this.running ? this.mixer.peakR : 0,
      inputLevel: this.inputLevel, pitch: this.tap ? this.tap.hz : -1, meters,
    });
  }

  process(inputs, outputs) {
    const output = outputs[0];
    const n = output[0].length;
    const input = inputs[0];
    const mic = input && input.length > 0 ? input[0] : null;

    // mic: capture for a take, level, pitch
    if (mic && (this.rec || this.monitoring)) {
      const g = this.gain;
      let bm = 0;
      for (let i = 0; i < mic.length; i++) { const v = Math.abs(mic[i]); if (v > bm) bm = v; }
      this.inputLevel = Math.min(1, bm * g);
      if (this.tap && (this.monitoring || this.pitchOn)) this.tap.feed(mic, mic.length, g);
      if (this.rec) {
        if (this.chunkLen + mic.length > this.chunk.length) this.flush();
        this.chunk.set(mic, this.chunkLen);
        this.chunkLen += mic.length;
      }
    } else if (this.rec) {
      // no mic data this block (stream not flowing yet): keep the timeline honest with silence
      if (this.chunkLen + n > this.chunk.length) this.flush();
      this.chunk.fill(0, this.chunkLen, this.chunkLen + n);
      this.chunkLen += n;
    }

    if (!this.running) {
      for (const ch of output) ch.fill(0);
      this.status(false);
      return true;
    }

    const s = this.s;
    let len = 0;
    for (const t of s.tracks) if (t.data.length > len) len = t.data.length;
    const rec = this.rec || this.calibrating;
    const lin = Math.max(0, s.loopIn);
    const lout = s.loopOut > 0 ? s.loopOut : len;
    const loop = s.loop && !rec && lout - lin > SR / 10;
    let cur = this.pos;
    const p = this.p;
    for (let i = 0; i < n; i++) {
      if (loop && cur >= lout) cur = lin;
      p[i] = cur++;
    }
    const click = s.metronome || (this.rec && p[0] < this.startPos);
    if (this.out.length < n * 2) this.out = new Float32Array(n * 2);
    const out = this.out;
    this.mixer.render(out, p, n, this.rec ? this.recId : -1, click);
    this.pos = cur;
    const L = output[0], R = output[1] || output[0];
    for (let i = 0; i < n; i++) { L[i] = out[2 * i]; R[i] = out[2 * i + 1]; }
    if (!rec && !loop && len > 0 && cur > len + SR / 2 && cur > this.startPos) {
      this.running = false;
      this.port.postMessage({ type: 'ended' });
      this.status(true);
      return true;
    }
    this.status(false);
    return true;
  }
}

registerProcessor('roughhouse', RoughhouseProcessor);
