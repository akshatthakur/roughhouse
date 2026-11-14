// UI-thread side of the audio engine: owns the AudioContext, mirrors the jam to the worklet,
// and turns captured mic audio into takes. A recorded sample i maps to timeline frame
// (recOrigin + i - latencyFrames), as on Android.
import { SR, PB } from './timing.js';

const PARAMS = ['vol', 'pan', 'lowCut', 'bass', 'treble', 'comp', 'echo', 'echoFb', 'echoDiv', 'rev', 'mute', 'solo'];

export class Engine {
  /** l: { onEngineStopped(), onTake(track), onCalibrated(ms), onEngineError(msg) } */
  constructor(l) {
    this.l = l;
    this.ctx = null;
    this.node = null;
    this.readyP = null;
    this.s = null;
    this.sent = new Map(); // track id -> audio version sent to the worklet
    this.playing = false;
    this.recording = false;
    this.monitoring = false;
    this.calibrating = false;
    this.pos = 0;
    this.posAt = 0;
    this.startPos = 0;
    this.levelL = 0; this.levelR = 0;
    this.inputLevel = 0;
    this.pitchHz = -1;
    this.meters = {};
    this.latencyFrames = SR / 10;
    this.inputGain = 1;
    this.recTrack = null;
    this.punchStart = 0;
    this.recOrigin = 0;
    this.recChunks = [];
    this.recLen = 0;
    this.recPeaks = new Float32Array(1024);
    this.recPeakCount = 0;
    this.pk = 0; this.pc = 0;
    this.stream = null;
    this.src = null;
    this.stopWaiters = [];
  }

  isPlaying() { return this.playing; }
  isRecording() { return this.recording; }
  startPosition() { return this.startPos; }

  /** Smooth position between the audio thread's ~20 ms status messages. */
  position() {
    if (!this.playing) return this.pos;
    const s = this.s;
    const ahead = Math.min(0.05, (performance.now() - this.posAt) / 1000) * SR;
    if (s && s.loop && !this.recording) return this.pos;
    return Math.round(this.pos + ahead);
  }

  /** Creates the AudioContext. Call synchronously inside a user gesture (iOS needs that). */
  wake() {
    if (!this.ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      try {
        this.ctx = new AC({ sampleRate: SR, latencyHint: 'interactive' });
      } catch {
        this.ctx = new AC();
      }
      this.readyP = this.ctx.audioWorklet.addModule('worklet.js').then(() => {
        this.node = new AudioWorkletNode(this.ctx, 'roughhouse', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });
        this.node.connect(this.ctx.destination);
        this.node.port.onmessage = e => this.onMsg(e.data);
        if (this.ctx.sampleRate !== SR) this.l.onEngineError(`this browser runs audio at ${this.ctx.sampleRate} Hz; playback may be off-speed.`);
        this.sent.clear();
        if (this.s) this.sync(this.s);
      });
    }
    if (this.ctx.state !== 'running') this.ctx.resume().catch(() => {});
    return this.readyP;
  }

  /** Default latency guess from what the browser reports, used until calibrated. */
  guessLatencyMs() {
    if (!this.ctx) return 120;
    const out = (this.ctx.baseLatency || 0) + (this.ctx.outputLatency || 0);
    return Math.max(20, Math.min(300, Math.round(out * 1000 + 25)));
  }

  post(m, tr) { if (this.node) this.node.port.postMessage(m, tr || []); }

  /** Sends settings and track parameters; audio only for tracks that changed since last time. */
  sync(s) {
    this.s = s;
    if (!this.node) return;
    this.post({ type: 'jam', s: { bpm: s.bpm, beatsPerBar: s.beatsPerBar, beatUnit: s.beatUnit, metroVol: s.metroVol, master: s.master, loop: s.loop, loopIn: s.loopIn, loopOut: s.loopOut, metronome: s.metronome } });
    const tracks = [];
    for (const t of s.tracks) {
      const x = { id: t.id };
      for (const k of PARAMS) x[k] = t[k];
      if (this.sent.get(t.id) !== t.ver || this.sentJam !== s) {
        x.data = t.data.slice();
        this.sent.set(t.id, t.ver);
      }
      tracks.push(x);
    }
    this.sentJam = s;
    this.post({ type: 'tracks', tracks }, tracks.filter(x => x.data).map(x => x.data.buffer));
  }

  play(from, countIn, calib = false) {
    if (this.playing) return;
    this.startPos = from;
    const bar = Math.round(60 * SR / Math.max(30, this.s.bpm) * Math.max(1, this.s.beatsPerBar));
    this.pos = countIn ? from - bar : from;
    this.posAt = performance.now();
    this.playing = true;
    this.calibrating = calib;
    this.post({ type: 'play', from, countIn, calib });
  }

  seek(frame) {
    if (!this.playing) return;
    this.pos = Math.max(0, frame);
    this.posAt = performance.now();
    this.post({ type: 'seek', frame: this.pos });
  }

  /** Stops recording (if any) and playback. Resolves once the audio thread has stopped. */
  stop() {
    if (!this.playing) return Promise.resolve();
    this.playing = false;
    this.post({ type: 'stop' });
    return new Promise(res => this.stopWaiters.push(res));
  }

  // ------------------------------------------------------------------ mic

  async openMic() {
    await this.wake();
    if (this.stream) return true;
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
      });
    } catch (e) {
      this.stream = null;
      return false;
    }
    try {
      this.src = this.ctx.createMediaStreamSource(this.stream);
      this.src.connect(this.node);
    } catch (e) {
      this.closeMic();
      this.l.onEngineError("couldn't connect the microphone: " + e.message);
      return false;
    }
    return true;
  }

  closeMic() {
    if (this.src) { try { this.src.disconnect(); } catch { /* already gone */ } this.src = null; }
    if (this.stream) { for (const t of this.stream.getTracks()) t.stop(); this.stream = null; }
  }

  /** Starts capturing into track t (null while calibrating); audio before `punch` is discarded. */
  startRecording(t, punch, pitch) {
    if (this.recording) return true;
    if (!this.stream) return false;
    this.stopMonitor();
    this.recTrack = t;
    this.punchStart = punch;
    this.recChunks = [];
    this.recLen = 0;
    this.recPeakCount = 0;
    this.pk = 0; this.pc = 0;
    this.recOrigin = this.pos;
    this.recording = true;
    this.post({ type: 'rec', id: t ? t.id : -1, gain: this.inputGain, pitch: !!pitch });
    return true;
  }

  /** Ends the take but keeps playback running (punch out). */
  stopRecording() {
    if (!this.recording) return;
    this.post({ type: 'punchOut' });
  }

  startMonitor() {
    if (this.monitoring || this.recording) return true;
    if (!this.stream) return false;
    this.monitoring = true;
    this.post({ type: 'monitor', on: true, gain: this.inputGain });
    return true;
  }

  stopMonitor() {
    if (!this.monitoring) return;
    this.monitoring = false;
    this.pitchHz = -1;
    this.inputLevel = 0;
    this.post({ type: 'monitor', on: false, gain: this.inputGain });
  }

  calibrate() {
    if (!this.stream) return false;
    this.play(0, false, true);
    return this.startRecording(null, 0, false);
  }

  // ------------------------------------------------------------- messages

  onMsg(m) {
    switch (m.type) {
      case 'st':
        if (this.playing && m.running) { this.pos = m.pos; this.posAt = performance.now(); }
        this.levelL = m.levelL; this.levelR = m.levelR;
        this.inputLevel = m.inputLevel;
        this.pitchHz = m.pitch;
        this.meters = m.meters;
        if (this.s) for (const t of this.s.tracks) if (t.id in m.meters) t.meter = m.meters[t.id];
        break;
      case 'recStart':
        this.recOrigin = m.origin;
        break;
      case 'recData':
        this.addRec(m.data);
        break;
      case 'recEnd':
        this.finishRec();
        break;
      case 'ended':
        this.stop();
        break;
      case 'stopped': {
        this.levelL = 0; this.levelR = 0;
        const w = this.stopWaiters;
        this.stopWaiters = [];
        if (!this.monitoring) this.closeMic();
        this.l.onEngineStopped();
        for (const r of w) r();
        break;
      }
    }
  }

  addRec(d) {
    this.recChunks.push(d);
    this.recLen += d.length;
    const g = this.inputGain;
    for (let i = 0; i < d.length; i++) {
      const v = d[i] < 0 ? -d[i] : d[i];
      if (v > this.pk) this.pk = v;
      if (++this.pc === PB) {
        if (this.recPeakCount >= this.recPeaks.length) {
          const np = new Float32Array(this.recPeaks.length * 2);
          np.set(this.recPeaks);
          this.recPeaks = np;
        }
        this.recPeaks[this.recPeakCount++] = Math.min(1, this.pk * g);
        this.pk = 0; this.pc = 0;
      }
    }
  }

  recBuffer() {
    const all = new Float32Array(this.recLen);
    let o = 0;
    for (const c of this.recChunks) { all.set(c, o); o += c.length; }
    return all;
  }

  finishRec() {
    if (!this.recording) return;
    this.recording = false;
    this.inputLevel = 0;
    this.pitchHz = -1;
    const buf = this.recBuffer();
    this.recChunks = [];
    if (this.calibrating) {
      this.calibrating = false;
      this.l.onCalibrated(this.computeCalibration(buf));
    } else {
      const t = this.finalizeTake(buf);
      if (t) this.l.onTake(t);
    }
    this.recTrack = null;
    this.recPeakCount = 0;
  }

  finalizeTake(rec) {
    const t = this.recTrack, recLen = rec.length;
    if (!t || recLen === 0) return null;
    const first = this.recOrigin - this.latencyFrames;
    const skip = Math.max(0, this.punchStart - first);
    if (skip >= recLen) return null;
    const ts = first + skip, n = recLen - skip;
    if (n < SR / 20) return null; // ignore accidental taps (<50 ms)
    const old = t.data;
    const nd = new Int16Array(Math.max(old.length, ts + n));
    nd.set(old);
    const g = this.inputGain * 32768;
    const fade = Math.min(SR / 200, Math.floor(n / 2));
    for (let i = 0; i < n; i++) {
      let v = rec[skip + i] * g;
      const at = ts + i;
      if (i < fade || i >= n - fade) {
        const w = i < fade ? i / fade : (n - 1 - i) / fade;
        const o = at < old.length ? old[at] : 0;
        v = o * (1 - w) + v * w;
      }
      nd[at] = v > 32767 ? 32767 : v < -32768 ? -32768 : v;
    }
    t.undo = old;
    t.setData(nd);
    t.invalidateAnalysis();
    return t;
  }

  computeCalibration(rec) {
    const interval = SR / 2, win = Math.floor(SR * 4 / 10), lags = [];
    for (let c = interval; ; c += interval) {
      const base = c - this.recOrigin;
      if (base < 0) continue;
      if (base + win > rec.length) break;
      let mi = -1, mv = 0, sum = 0;
      for (let i = base; i < base + win; i++) {
        const v = Math.abs(rec[i]) * 32768;
        sum += v;
        if (v > mv) { mv = v; mi = i; }
      }
      const mean = sum / win;
      if (mv > 1200 && mv > mean * 6) lags.push(mi - base);
    }
    if (lags.length < 3) return -1;
    lags.sort((a, b) => a - b);
    return Math.round(lags[lags.length >> 1] * 1000 / SR);
  }
}
