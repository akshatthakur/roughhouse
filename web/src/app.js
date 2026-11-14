// Roughhouse for the browser: the deck, the channel strips and every menu. Ported from MainActivity.java.
import { SR, beatFrames as bfOf } from './timing.js';
import { C, CAPS, gestures } from './ui.js';
import { Cassette, Counter, Vu, Tuner, Key, Fifths } from './deck.js';
import { WaveView, Ruler, TrackView, MelodyView, MAX_ROWS } from './views.js';
import { Engine } from './engine.js';
import { Jam, Track, pinnedLyrics, saveJam, loadJam, listJams, deleteJam, importFiles } from './jam.js';
import { NOTES, keyName, refreshAfterEdit } from './analyzer.js';
import { noteName } from './pitch.js';
import * as Theory from './theory.js';
import * as Editor from './editor.js';
import { DIV_NAMES } from './dsp.js';
import { FORMATS, canM4a } from './formats.js';
import { writeMidi, voice } from './midi.js';
import { writeZip, readZip } from './zip.js';
import {
  el, dialog, message, busy, list, prompt, confirm, body, para, heading, slider, toggle, chipRow, chip, addChip,
  checks, anyOpen, closeTop,
} from './dialogs.js';

// analysis sections (bit flags so any mix can be shown together)
const A_TEMPO = 1, A_KEY = 2, A_MELODY = 4, A_CHORDS = 8, A_RANGE = 16, A_FITS = 32, A_SUGGEST = 64, A_ALL = 127;
const SIGS = [[2, 4], [3, 4], [4, 4], [5, 4], [6, 4], [7, 4], [3, 8], [5, 8], [6, 8], [7, 8], [9, 8], [12, 8]];
const SECTION_NAMES = ['intro', 'verse', 'pre-chorus', 'chorus', 'bridge', 'solo', 'outro'];
const BACKUP_EXT = '.roughhouse.zip';

const prefs = {
  get(k, d) { try { const v = localStorage.getItem('rh.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('rh.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
};

const fmtBpm = b => b === Math.round(b) ? String(Math.round(b)) : b.toFixed(1);
const fileName = (name, fallback) => (name.replace(/[^A-Za-z0-9 _-]/g, '').trim() || fallback);

// ---------------------------------------------------------------- background jobs

const worker = new Worker('worker.js');
let jobId = 0;
const jobs = new Map();
worker.onmessage = e => {
  const j = jobs.get(e.data.id);
  if (!j) return;
  if ('progress' in e.data) { if (j.progress) j.progress(e.data.progress); return; }
  jobs.delete(e.data.id);
  if (e.data.error) j.reject(new Error(e.data.error)); else j.resolve(e.data.result);
};
function job(spec, progress, transfer) {
  const id = ++jobId;
  return new Promise((resolve, reject) => {
    jobs.set(id, { resolve, reject, progress });
    worker.postMessage({ id, job: spec }, transfer || []);
  });
}
/** Plain copy of the jam for the worker (structured clone copies the audio). */
const jamSnapshot = (s, tracks = s.tracks) => ({
  bpm: s.bpm, beatsPerBar: s.beatsPerBar, beatUnit: s.beatUnit, metroVol: s.metroVol, master: s.master,
  tracks: tracks.map(t => ({ id: t.id, data: t.data, vol: t.vol, pan: t.pan, lowCut: t.lowCut, bass: t.bass, treble: t.treble, comp: t.comp, echo: t.echo, echoFb: t.echoFb, echoDiv: t.echoDiv, rev: t.rev, mute: t.mute, solo: t.solo, meter: 0 })),
});

// ======================================================================== app

class App {
  constructor() {
    this.session = prefs.get('session', false); // fresh installs start in scratch mode
    this.s = null;
    this.armed = null;
    this.undoAction = null;
    this.cursor = 0;
    this.counterBars = false;
    this.lyricPins = [];
    this.rows = [];
    this.taps = [];
    this.trackView = null;
    this.trackDlg = null;
    this.trackRefresh = null;
    this.ticking = false;
    this.saving = Promise.resolve();
    this.eng = new Engine({
      onEngineStopped: () => this.onEngineStopped(),
      onTake: t => this.onTake(t),
      onCalibrated: ms => this.onCalibrated(ms),
      onEngineError: m => this.note(m),
    });
    this.m4a = false;
    canM4a().then(v => { this.m4a = v; });
  }

  async start() {
    this.buildUi();
    let loaded = null;
    const last = prefs.get('last', null);
    if (last) loaded = await loadJam(last);
    this.openJam(loaded || Jam.create());
    this.applyMode();
    if (new URLSearchParams(location.search).has('record')) {
      history.replaceState(null, '', location.pathname);
      if (this.s.lengthFrames() > 0 || this.s.tracks.length) { await this.leaveCurrent(); this.openJam(Jam.create()); }
      this.note('fresh jam. press REC to start recording.');
    }
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        if (this.eng.monitoring) { this.eng.stopMonitor(); this.eng.closeMic(); this.refreshAll(); }
        this.save();
      }
    });
    window.addEventListener('pagehide', () => this.save());
    window.addEventListener('keydown', e => this.onKey(e));
    if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
  }

  setMode(on) {
    this.session = on;
    prefs.set('session', on);
    this.applyMode();
    this.note(on ? 'session mode: everything is on. tap SESSION MODE to go back to scratch.'
      : 'scratch mode: just the basics. tap SCRATCH MODE for the full set.');
  }

  applyMode() {
    this.modeLabel.textContent = this.session ? 'SESSION MODE  ·  TAP FOR SCRATCH' : 'SCRATCH MODE  ·  TAP FOR SESSION';
    for (const c of [this.sigChip, this.keyChip, this.tuneChip]) c.hidden = !this.session;
    if (!this.session && this.eng.monitoring) { this.eng.stopMonitor(); this.eng.closeMic(); }
    if (!this.session && this.trackDlg) this.trackDlg.close();
    this.refreshAll();
  }

  // ===================================================================== UI

  buildUi() {
    const root = document.getElementById('app');
    root.innerHTML = '';
    const colL = el('div', 'colL'), colR = el('div', 'colR');
    root.append(colL, colR);

    // brand strip
    const top = el('header', 'top');
    const brand = el('div', 'brand');
    brand.appendChild(el('div', 'word', 'ROUGHHOUSE'));
    this.modeLabel = el('button', 'mode');
    this.modeLabel.addEventListener('click', () => this.setMode(!this.session));
    brand.appendChild(this.modeLabel);
    top.appendChild(brand);
    top.append(chip('NEW', () => this.newJam()), chip('JAMS', () => this.showJams()), chip('•••', () => this.showMenu(), 'more'));
    top.lastChild.setAttribute('aria-label', 'menu');

    // deck panel
    const deck = el('section', 'deck panel');
    this.cassette = new Cassette();
    this.cassette.el.setAttribute('aria-label', 'cassette: tap to rename the jam');
    this.cassette.el.addEventListener('click', () => this.renameJam());
    deck.appendChild(this.cassette.el);
    const meters = el('div', 'meters');
    this.counter = new Counter();
    this.counter.el.addEventListener('click', () => { this.counterBars = !this.counterBars; this.updateDeck(); });
    meters.appendChild(this.counter.el);
    const slot = el('div', 'meterslot');
    const pair = el('div', 'vupair');
    this.vuL = new Vu('L'); this.vuR = new Vu('R');
    pair.append(this.vuL.el, this.vuR.el);
    this.vuPair = pair;
    this.tuner = new Tuner();
    this.tuner.el.hidden = true;
    slot.append(pair, this.tuner.el);
    meters.appendChild(slot);
    deck.appendChild(meters);
    const chips = el('div', 'chips');
    this.bpmChip = chip('100 BPM', () => this.showTempo(), 'w13');
    this.sigChip = chip('4/4', () => this.showTimeSig());
    this.keyChip = chip('KEY', () => this.showKeyPicker(), 'w13');
    gestures(this.keyChip, { long: () => { this.keyChip.dataset.longed = '1'; this.showCircle(); } });
    this.keyChip.addEventListener('click', e => { if (this.keyChip.dataset.longed) { e.stopImmediatePropagation(); delete this.keyChip.dataset.longed; } }, true);
    this.tuneChip = chip('TUNE', () => this.toggleTune());
    chips.append(this.bpmChip, this.sigChip, this.keyChip, this.tuneChip);
    deck.appendChild(chips);

    // timeline
    const rulerRow = el('div', 'rulerrow');
    rulerRow.appendChild(el('span', 'silk faint', 'BAR'));
    this.ruler = new Ruler(this);
    rulerRow.appendChild(this.ruler.el);
    rulerRow.appendChild(el('span', 'rspacer'));
    this.lyricStrip = el('button', 'lyricstrip');
    this.lyricStrip.hidden = true;
    this.lyricStrip.addEventListener('click', () => this.showLyrics());

    // channel strips
    this.trackList = el('div', 'tracks');

    // transport
    const keys = el('div', 'keys');
    this.rewKey = new Key(Key.REW, 'rewind to start');
    this.recKey = new Key(Key.REC, 'record');
    this.playKey = new Key(Key.PLAY, 'play');
    this.stopKey = new Key(Key.STOP, 'stop');
    const press = (k, fn) => {
      k.el.addEventListener('click', () => { this.eng.wake(); fn(); });
      k.el.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); this.eng.wake(); fn(); } });
    };
    press(this.rewKey, () => this.seekTo(0));
    press(this.recKey, () => this.onRec());
    press(this.playKey, () => this.onPlay());
    press(this.stopKey, () => this.onStopKey());
    keys.append(this.rewKey.el, this.recKey.el, this.playKey.el, this.stopKey.el);
    const small = el('div', 'small');
    this.loopChip = chip('LOOP', () => { if (this.loopChip.dataset.longed) { delete this.loopChip.dataset.longed; return; } this.s.loop = !this.s.loop; this.refreshAll(); });
    gestures(this.loopChip, { long: () => { this.loopChip.dataset.longed = '1'; this.showLoopMenu(); } });
    this.clickChip = chip('CLICK', () => { if (this.clickChip.dataset.longed) { delete this.clickChip.dataset.longed; return; } this.s.metronome = !this.s.metronome; this.refreshAll(); });
    gestures(this.clickChip, { long: () => { this.clickChip.dataset.longed = '1'; this.showSettings(); } });
    this.undoChip = chip('UNDO', () => this.onUndo());
    small.append(this.loopChip, this.clickChip, this.undoChip, chip('LYRICS', () => this.showLyrics(), 'w12'));

    colL.append(top, deck, small);
    colR.append(rulerRow, this.lyricStrip, this.trackList, keys);
    top.style.order = 1; deck.style.order = 2; rulerRow.style.order = 3; this.lyricStrip.style.order = 4;
    this.trackList.style.order = 5; keys.style.order = 6; small.style.order = 7;

    this.noteBar = el('div', 'notebar');
    this.noteBar.hidden = true;
    this.noteBar.setAttribute('role', 'status');
    this.noteBar.addEventListener('click', () => { this.noteBar.hidden = true; });
    document.body.appendChild(this.noteBar);
  }

  // one channel strip: coloured cap (tap = arm) · tape lane · M / S / FX
  makeRow(t, index) {
    const v = el('div', 'row panel');
    const cap = el('button', 'cap', String(index + 1));
    cap.setAttribute('aria-label', `arm channel ${index + 1} for recording`);
    cap.addEventListener('click', () => { this.armed = t; this.refreshAll(); });
    const wave = new WaveView(this, t);
    const btns = el('div', 'btns');
    const m = chip('M', () => { t.mute = !t.mute; this.refreshAll(); }, 'tiny');
    const so = chip('S', () => { t.solo = !t.solo; this.refreshAll(); }, 'tiny');
    const fx = chip('FX', () => this.showFx(t), 'tiny');
    m.setAttribute('aria-label', 'mute'); so.setAttribute('aria-label', 'solo'); fx.setAttribute('aria-label', 'effects');
    btns.append(m, so, fx);
    v.append(cap, wave.el, btns);
    const r = {
      t, v, wave,
      refresh: i => {
        const isArmed = this.nextRecTrack() === t;
        cap.style.background = CAPS[i % CAPS.length];
        cap.style.boxShadow = isArmed ? `inset 0 0 0 3px ${this.eng.isRecording() ? C.RED : C.CREAM}` : 'none';
        setChip(m, t.mute, 'blue');
        setChip(so, t.solo, 'yellow');
        setChip(fx, t.fxActive(), 'amber');
        wave.invalidate();
      },
    };
    return r;
  }

  rebuildRows() {
    for (const r of this.rows) r.wave.destroy();
    this.trackList.innerHTML = '';
    this.rows = [];
    this.s.tracks.forEach((t, i) => {
      const r = this.makeRow(t, i);
      this.rows.push(r);
      this.trackList.appendChild(r.v);
    });
    if (this.s.tracks.length === 0) {
      this.trackList.appendChild(el('div', 'empty', 'press REC to lay down a jam.\nevery take lands on a new track.'));
    }
    const foot = el('div', 'foot');
    foot.append(chip('+ TRACK', () => { this.armed = this.addTrack(); this.refreshAll(); }), chip('IMPORT AUDIO', () => this.pickImport()));
    this.trackList.appendChild(foot);
  }

  refreshAll() {
    const s = this.s, eng = this.eng;
    if (!s) return;
    this.bpmChip.textContent = fmtBpm(s.bpm) + ' BPM';
    this.sigChip.textContent = s.timeSig();
    this.keyChip.textContent = s.keyRoot < 0 ? 'KEY –' : NOTES[s.keyRoot] + (s.keyMinor ? ' MIN' : ' MAJ');
    setChip(this.tuneChip, eng.monitoring, 'green');
    setChip(this.loopChip, s.loop, 'amber');
    setChip(this.clickChip, s.metronome, 'yellow');
    this.undoChip.style.opacity = this.undoAction && !eng.isRecording() ? 1 : 0.4;
    const playing = eng.isPlaying();
    this.playKey.set(playing, playing);
    this.recKey.set(eng.isRecording(), eng.isRecording());
    this.rows.forEach((r, i) => r.refresh(i));
    const showTuner = this.session && (eng.monitoring || (eng.isRecording() && eng.recTrack && eng.recTrack.vocal));
    this.tuner.el.hidden = !showTuner;
    this.vuPair.style.visibility = showTuner ? 'hidden' : 'visible';
    this.updateDeck();
    this.ruler.invalidate();
    if (this.trackRefresh) this.trackRefresh();
    if (this.trackView) this.trackView.invalidate();
    eng.sync(s);
  }

  updateLyricStrip() {
    const pins = this.lyricPins;
    if (!pins.length) { this.lyricStrip.hidden = true; return; }
    const bar = Math.floor(Math.max(0, this.playhead()) / this.s.barFrames()) + 1;
    let e = null;
    for (const p of pins) { if (p[0] <= bar) e = p; else break; }
    const line = e ? e[1] : pins[0][1];
    this.lyricStrip.textContent = line;
    this.lyricStrip.classList.toggle('ahead', !e);
    this.lyricStrip.hidden = false;
  }

  /** Counter, cassette label and reels. */
  updateDeck() {
    const s = this.s, eng = this.eng, p = this.playhead(), rec = eng.isRecording();
    const cas = this.cassette;
    cas.title = s.name;
    cas.sub = (fmtBpm(s.bpm) + ' BPM  ·  ' + s.timeSig() + '  ·  ' + (s.keyRoot < 0 ? 'NO KEY' : s.keyLabel()) + '  ·  '
      + s.tracks.length + (s.tracks.length === 1 ? ' TRACK' : ' TRACKS')).toUpperCase();
    cas.recording = rec;
    const view = this.viewFrames();
    cas.frac = view > 0 ? Math.max(0, Math.min(1, p / view)) : 0;
    cas.angle = Math.max(0, p) / SR * Math.PI * 1.2;
    cas.invalidate();

    const ctr = this.counter;
    ctr.red = rec;
    if (eng.isPlaying() && eng.position() < eng.startPosition() && rec) {
      const left = Math.ceil((eng.startPosition() - eng.position()) / s.beatFrames());
      ctr.text = 'IN ' + Math.max(1, left);
      ctr.caption = 'COUNT-IN';
    } else if (this.counterBars) {
      const beat = Math.floor(Math.max(0, p) / s.beatFrames()), bpb = Math.max(1, s.beatsPerBar);
      ctr.text = String(Math.floor(beat / bpb) + 1).padStart(3, '0') + '.' + (beat % bpb + 1);
      ctr.caption = 'BAR . BEAT   (TAP FOR TIME)';
    } else {
      const tenths = Math.floor(Math.max(0, p) * 10 / SR);
      ctr.text = String(Math.floor(tenths / 600)).padStart(2, '0') + ':' + String(Math.floor(tenths / 10) % 60).padStart(2, '0') + '.' + (tenths % 10);
      ctr.caption = 'TAPE TIME   (TAP FOR BARS)';
    }
    ctr.invalidate();
    this.updateLyricStrip();
  }

  tick() {
    const eng = this.eng;
    const active = eng.isPlaying() || eng.monitoring;
    let moving = this.vuL.step(eng.isPlaying() ? eng.levelL : eng.monitoring ? eng.inputLevel : 0);
    moving = this.vuR.step(eng.isPlaying() ? eng.levelR : eng.monitoring ? eng.inputLevel : 0) || moving;
    if (active) {
      this.updateDeck();
      for (const r of this.rows) r.wave.invalidate();
      this.ruler.invalidate();
      if (this.trackView) this.trackView.invalidate();
      this.recKey.level = eng.inputLevel;
      this.recKey.invalidate();
      if (!this.tuner.el.hidden) this.tuner.set(eng.pitchHz);
    }
    if (!active && !moving) { this.ticking = false; return; }
    requestAnimationFrame(() => this.tick());
  }

  startTicking() {
    this.keepAwake(true);
    if (!this.ticking) { this.ticking = true; requestAnimationFrame(() => this.tick()); }
  }

  async keepAwake(on) {
    try {
      if (on && !this.wakeLock && navigator.wakeLock) this.wakeLock = await navigator.wakeLock.request('screen');
      else if (!on && this.wakeLock) { await this.wakeLock.release(); this.wakeLock = null; }
    } catch { this.wakeLock = null; }
  }

  note(msg) {
    this.noteBar.textContent = msg;
    this.noteBar.hidden = false;
    clearTimeout(this.noteTimer);
    this.noteTimer = setTimeout(() => { this.noteBar.hidden = true; }, Math.min(6000, 2200 + msg.length * 40));
  }

  onKey(e) {
    if (anyOpen()) {
      if (e.key === 'Escape') closeTop();
      return;
    }
    const tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA' || e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key === ' ') { e.preventDefault(); this.eng.wake(); if (this.eng.isPlaying()) this.onStopKey(); else this.onPlay(); }
    else if (e.key === 'r' || e.key === 'R') { this.eng.wake(); this.onRec(); }
    else if (e.key === 'Home' || e.key === 'Enter') { e.preventDefault(); this.seekTo(0); }
    else if (e.key === 'l' || e.key === 'L') { this.s.loop = !this.s.loop; this.refreshAll(); }
    else if (e.key === 'c' || e.key === 'C') { this.s.metronome = !this.s.metronome; this.refreshAll(); }
    else if ((e.key === 'z' || e.key === 'Z') || e.key === 'u') this.onUndo();
  }

  // ============================================================ shared state

  viewFrames() {
    let m = Math.max(this.s.lengthFrames(), 15 * SR);
    if (this.eng.isPlaying()) m = Math.max(m, this.eng.position());
    return Math.floor(m * 1.1);
  }

  playhead() { return this.eng.isPlaying() ? this.eng.position() : this.cursor; }

  isAudible(t) {
    if (t.mute) return false;
    for (const o of this.s.tracks) if (o.solo) return t.solo;
    return true;
  }

  /** The track the next REC will record into (null = a new track). */
  nextRecTrack() {
    const eng = this.eng;
    if (eng.isRecording()) return eng.recTrack;
    if (this.armed && this.s.tracks.includes(this.armed)) return this.armed;
    for (const t of this.s.tracks) if (t.isEmpty()) return t;
    return null;
  }

  seekTo(frame) {
    frame = Math.max(0, Math.floor(frame));
    this.cursor = frame;
    if (this.eng.isPlaying() && !this.eng.isRecording()) this.eng.seek(frame);
    this.updateDeck();
    for (const r of this.rows) r.wave.invalidate();
    this.ruler.invalidate();
    if (this.trackView) this.trackView.invalidate();
  }

  addTrack() {
    const t = new Track(this.s.nextId(), 'track ' + (this.s.tracks.length + 1));
    this.s.tracks.push(t);
    this.rebuildRows();
    return t;
  }

  applyEngineSettings() {
    const eng = this.eng;
    eng.s = this.s;
    eng.latencyFrames = Math.round(prefs.get('lat', eng.guessLatencyMs()) * SR / 1000);
    eng.inputGain = Math.pow(10, prefs.get('boost', 0) / 20);
    eng.sync(this.s);
  }

  // ============================================================== transport

  async onPlay() {
    const eng = this.eng, s = this.s;
    if (eng.isPlaying()) return;
    if (s.lengthFrames() === 0 && !s.metronome) { this.note('nothing on tape yet. press REC to record.'); return; }
    await eng.wake();
    this.applyEngineSettings();
    eng.play(this.cursor, false);
    this.refreshAll();
    this.startTicking();
  }

  onStopKey() {
    if (this.eng.isPlaying()) this.eng.stop();
    else { this.cursor = 0; this.updateDeck(); for (const r of this.rows) r.wave.invalidate(); this.ruler.invalidate(); }
  }

  async onRec() {
    const eng = this.eng;
    if (eng.isRecording()) { eng.stopRecording(); this.refreshAll(); return; } // punch out, keep rolling
    if (this.recBusy) return;
    this.recBusy = true;
    try {
      if (!(await eng.openMic())) {
        this.note("couldn't open the microphone. allow mic access for this site and try again.");
        return;
      }
      let t = this.nextRecTrack();
      if (!t) t = this.addTrack();
      this.applyEngineSettings();
      const wasPlaying = eng.isPlaying();
      if (!wasPlaying) eng.play(this.cursor, this.s.countIn);
      const punch = wasPlaying ? Math.max(0, eng.position()) : this.cursor;
      if (!eng.startRecording(t, punch, this.session && t.vocal)) {
        this.note("couldn't open the microphone.");
        if (!wasPlaying) eng.stop();
        return;
      }
      this.armed = t;
      this.refreshAll();
      this.startTicking();
      if (!prefs.get('calHint', false) && this.s.tracks.length > 1) {
        prefs.set('calHint', true);
        this.note('tip: run ••• → calibrate latency once so overdubs line up.');
      }
    } finally {
      this.recBusy = false;
    }
  }

  onUndo() {
    if (!this.undoAction || this.eng.isRecording()) return;
    if (this.eng.isPlaying()) this.eng.stop();
    const u = this.undoAction;
    this.undoAction = null;
    u();
    this.armed = null;
    this.save();
    this.rebuildRows();
    this.refreshAll();
    this.note('undone');
  }

  /** Remembers how to put a track's audio back to what it was before a change. */
  undoAudio(t, before) {
    this.undoAction = () => {
      t.setData(before);
      t.invalidateAnalysis();
      if (!this.s.tracks.includes(t)) this.s.tracks.push(t);
    };
  }

  undoAdded(t) {
    this.undoAction = () => { this.s.tracks = this.s.tracks.filter(x => x !== t); };
  }

  async toggleTune() {
    const eng = this.eng;
    if (eng.monitoring) {
      eng.stopMonitor();
      if (!eng.isPlaying()) eng.closeMic();
    } else {
      if (eng.isRecording()) { this.note('the tuner already shows while recording a VOX track.'); return; }
      if (!(await eng.openMic())) { this.note("couldn't open the microphone"); return; }
      this.applyEngineSettings();
      if (!eng.startMonitor()) { this.note("couldn't open the microphone"); return; }
      this.startTicking();
    }
    this.refreshAll();
  }

  // ======================================================== engine callbacks

  onEngineStopped() {
    if (!this.eng.monitoring) this.keepAwake(false);
    this.recKey.level = 0;
    this.refreshAll();
    this.startTicking(); // let the VU needles fall back
    this.save();
  }

  onTake(t) {
    if (!t.undo || t.undo.length === 0) this.undoAdded(t);
    else this.undoAudio(t, t.undo);
    t.undo = null;
    this.armed = null;
    this.save();
    this.refreshAll();
  }

  onCalibrated(ms) {
    if (this.calDlg) { this.calDlg.close(); this.calDlg = null; }
    if (ms < 0) this.note("couldn't hear the clicks. turn the volume up, unplug headphones and try again.");
    else { prefs.set('lat', ms); this.note('latency set to ' + ms + ' ms'); }
  }

  // ================================================================ dialogs

  showFx(t) {
    const l = body();
    const fmtDb = v => Math.abs(v) < 0.1 ? '0 dB' : (v > 0 ? '+' : '') + v.toFixed(1) + ' dB';
    const pct = p => p === 0 ? 'off' : p + '%';
    const live = () => { this.refreshAll(); };
    slider(l, 'volume', 150, Math.round(t.vol * 100), p => p + '%', p => { t.vol = p / 100; live(); });
    slider(l, 'pan', 100, Math.round(t.pan * 50 + 50), p => p === 50 ? 'center' : (p < 50 ? 'L ' + (50 - p) * 2 : 'R ' + (p - 50) * 2), p => { t.pan = (p - 50) / 50; live(); });
    slider(l, 'low cut', 100, t.lowCut <= 0 ? 0 : Math.round(Math.log(t.lowCut / 20) / Math.log(20) * 100),
      p => p === 0 ? 'off' : Math.round(20 * Math.pow(20, p / 100)) + ' Hz', p => { t.lowCut = p === 0 ? 0 : 20 * Math.pow(20, p / 100); live(); });
    slider(l, 'bass', 48, Math.round(t.bass * 2 + 24), p => fmtDb((p - 24) / 2), p => { t.bass = (p - 24) / 2; live(); });
    slider(l, 'treble', 48, Math.round(t.treble * 2 + 24), p => fmtDb((p - 24) / 2), p => { t.treble = (p - 24) / 2; live(); });
    slider(l, 'compression', 100, Math.round(t.comp * 100), pct, p => { t.comp = p / 100; live(); });
    slider(l, 'echo', 100, Math.round(t.echo / 0.8 * 100), pct, p => { t.echo = p / 100 * 0.8; live(); });
    slider(l, 'echo time (synced)', DIV_NAMES.length - 1, t.echoDiv, p => DIV_NAMES[p], p => { t.echoDiv = p; live(); });
    slider(l, 'echo repeats', 90, Math.round(t.echoFb * 100), p => p + '%', p => { t.echoFb = p / 100; live(); });
    slider(l, 'reverb', 100, Math.round(t.rev * 100), pct, p => { t.rev = p / 100; live(); });
    let reset = false;
    dialog({
      title: t.name, body: l,
      buttons: [{ label: 'RESET', left: true, fn: () => { reset = true; t.resetFx(); this.refreshAll(); this.showFx(t); } }, { label: 'DONE' }],
      onDismiss: () => { if (!reset) { this.refreshAll(); this.save(); } },
    });
  }

  showTempo() {
    const s = this.s, l = body();
    slider(l, 'tempo', 200, Math.round(s.bpm) - 40, p => (p + 40) + ' BPM', p => { s.bpm = p + 40; this.refreshAll(); });
    const row = chipRow(l);
    const tap = addChip(row, 'TAP TEMPO', () => {
      const now = performance.now(), taps = this.taps;
      if (taps.length && now - taps[taps.length - 1] > 2000) taps.length = 0;
      taps.push(now);
      while (taps.length > 6) taps.shift();
      if (taps.length >= 2) {
        const avg = (taps[taps.length - 1] - taps[0]) / (taps.length - 1);
        s.bpm = Math.max(40, Math.min(240, Math.round(60000 / avg)));
        this.refreshAll();
        tap.textContent = 'TAP · ' + fmtBpm(s.bpm);
      }
    });
    addChip(row, '×2', () => { s.bpm = Math.min(240, s.bpm * 2); this.refreshAll(); this.note(fmtBpm(s.bpm) + ' BPM'); });
    addChip(row, '÷2', () => { s.bpm = Math.max(40, s.bpm / 2); this.refreshAll(); this.note(fmtBpm(s.bpm) + ' BPM'); });
    para(l, 'the metronome clicks on every ' + (s.beatUnit === 8 ? 'eighth' : 'quarter') + ' note at this tempo.', 12, 'dim');
    dialog({ title: 'tempo', body: l, buttons: [{ label: 'DONE' }], onDismiss: () => { this.refreshAll(); this.save(); } });
  }

  showTimeSig() {
    const s = this.s;
    const items = SIGS.map(([a, b]) => a + '/' + b + (a === s.beatsPerBar && b === s.beatUnit ? '   ●' : ''));
    list('time signature', items, w => {
      s.beatsPerBar = SIGS[w][0];
      s.beatUnit = SIGS[w][1];
      this.refreshAll();
      this.save();
      this.note('metronome accents every ' + s.beatsPerBar + ' beats; count-in is one bar.');
    });
  }

  showKeyPicker() {
    const s = this.s, items = ['circle of fifths…', 'no key'];
    for (let i = 0; i < 12; i++) { items.push(NOTES[i] + ' major'); items.push(NOTES[i] + ' minor'); }
    list('key', items, w => {
      if (w === 0) { this.showCircle(); return; }
      if (w === 1) s.keyRoot = -1;
      else { s.keyRoot = (w - 2) >> 1; s.keyMinor = (w - 2) % 2 === 1; }
      this.refreshAll();
      this.save();
      if (s.keyRoot >= 0) this.showKeyHelper(s.keyRoot, s.keyMinor);
    });
  }

  showCircle() {
    const s = this.s, l = body();
    const circle = new Fifths();
    const root0 = s.keyRoot >= 0 ? s.keyRoot : 0, minor0 = s.keyRoot >= 0 && s.keyMinor;
    circle.pos = Fifths.posOf(root0, minor0);
    circle.minor = minor0;
    l.appendChild(circle.el);
    const info = el('div');
    l.appendChild(info);
    const sel = [root0, minor0];
    const fill = () => {
      info.innerHTML = '';
      const [r, m] = sel;
      heading(info, keyName(r, m));
      const major = m ? (r + 3) % 12 : r, relMinor = (major + 9) % 12;
      para(info, 'next door on the circle:  IV ' + NOTES[(major + 5) % 12] + '   ·   V ' + NOTES[(major + 7) % 12]
        + '\nrelative ' + (m ? 'major: ' + NOTES[major] : 'minor: ' + NOTES[relMinor] + 'm')
        + '\nkeys next to each other share six of seven notes, so moving one step is the smoothest key change.', 13, 'dim');
      addChip(chipRow(info), 'USE FOR JAM', () => {
        s.keyRoot = r; s.keyMinor = m;
        this.refreshAll(); this.save();
        this.note('jam key set to ' + s.keyLabel());
      });
      this.fitsKey(info, r, m);
    };
    circle.onPick = (r, m) => { sel[0] = r; sel[1] = m; fill(); };
    fill();
    dialog({ title: 'circle of fifths', body: l, buttons: [{ label: 'DONE' }], onDismiss: () => circle.destroy() });
  }

  showKeyHelper(root, minor) {
    const l = body();
    this.fitsKey(l, root, minor);
    dialog({ title: keyName(root, minor), body: l, buttons: [{ label: 'CIRCLE OF FIFTHS', left: true, fn: () => this.showCircle() }, { label: 'DONE' }] });
  }

  fitsKey(l, root, minor) {
    heading(l, 'scale');
    para(l, Theory.scaleNames(root, minor), 15, 'amber');
    heading(l, 'chords in the key');
    para(l, Theory.diatonic(root, minor).join('\n'), 14);
    heading(l, 'progressions to try');
    para(l, Theory.progressions(root, minor).join('\n'), 14);
  }

  showSettings() {
    const s = this.s, l = body();
    toggle(l, 'one-bar count-in before recording', s.countIn, c => { s.countIn = c; });
    slider(l, 'click volume', 100, Math.round(s.metroVol * 100), p => p + '%', p => { s.metroVol = p / 100; this.eng.sync(s); });
    slider(l, 'input boost', 24, prefs.get('boost', 0), p => '+' + p + ' dB', p => prefs.set('boost', p));
    slider(l, 'latency compensation', 400, prefs.get('lat', this.eng.guessLatencyMs()), p => p + ' ms', p => prefs.set('lat', p));
    para(l, 'shifts new takes earlier so they line up with what you heard. calibrate measures it for you.', 12, 'dim');
    dialog({
      title: 'settings', body: l,
      buttons: [{ label: 'CALIBRATE', left: true, fn: () => this.calibrate() }, { label: 'DONE' }],
      onDismiss: () => { this.refreshAll(); this.save(); },
    });
  }

  calibrate() {
    message('unplug headphones, turn the volume up and stay quiet. Roughhouse will play a few clicks and listen for them (about 3 seconds).', [
      { label: 'CANCEL' },
      {
        label: 'START', fn: async () => {
          const eng = this.eng;
          eng.wake();
          if (eng.isPlaying()) await eng.stop();
          if (eng.monitoring) eng.stopMonitor();
          if (!(await eng.openMic())) { this.note("couldn't open the microphone"); return; }
          this.applyEngineSettings();
          if (!eng.calibrate()) { this.note("couldn't open the microphone"); return; }
          this.calDlg = busy('listening…');
          this.refreshAll();
          this.startTicking();
          setTimeout(() => eng.stop(), 3400);
        },
      },
    ], { title: 'calibrate latency' });
  }

  showLoopMenu() {
    const s = this.s;
    list('loop region', ['loop starts here', 'loop ends here', 'loop the whole jam'], w => {
      const p = Math.max(0, this.playhead());
      if (w === 0) { s.loopIn = p; if (s.loopOut > 0 && s.loopOut <= p) s.loopOut = -1; }
      else if (w === 1) { if (p > s.loopIn) s.loopOut = p; else this.note('loop end must be after the start'); }
      else { s.loopIn = 0; s.loopOut = -1; }
      s.loop = true;
      this.refreshAll();
    });
  }

  showTrackMenu(t) {
    if (this.eng.isRecording()) return;
    const items = [], acts = [];
    const add = (label, fn) => { items.push(label); acts.push(fn); };
    if (this.session) add('open track view', () => this.openTrack(t));
    if (this.session) {
      if (t.analysis) {
        add('analysis…', () => this.showAnalysisMenu(t));
        add(t.showNotes ? 'hide notes on the lane' : 'show notes on the lane', () => { t.showNotes = !t.showNotes; this.save(); this.refreshAll(); });
      } else if (!t.isEmpty()) {
        add('analyze notes, key, tempo and chords', () => this.analyzeTrack(t, A_ALL));
      }
    }
    if (!t.isEmpty()) add('edit…', () => this.showEditMenu(t));
    add('effects', () => this.showFx(t));
    if (this.session) {
      add(t.vocal ? 'pitch monitor: on (vocal track)' : 'pitch monitor: off', () => {
        t.vocal = !t.vocal; this.save(); this.refreshAll();
        this.note(t.vocal ? 'the tuner will show while recording this track.' : 'pitch monitor off for this track.');
      });
    }
    add('rename', () => prompt('rename track', t.name, v => { t.name = v; this.save(); this.rebuildRows(); this.refreshAll(); }));
    add('duplicate', () => {
      const c = new Track(this.s.nextId(), t.name + ' copy');
      c.copySettingsFrom(t);
      c.vocal = t.vocal;
      c.setData(t.data.slice());
      c.analysis = t.analysis ? structuredClone(t.analysis) : null;
      c.analysisDirty = !!t.analysis;
      c.showNotes = t.showNotes;
      this.s.tracks.splice(this.s.tracks.indexOf(t) + 1, 0, c);
      this.save(); this.rebuildRows(); this.refreshAll();
    });
    add('clear audio', () => {
      if (this.eng.isPlaying()) this.eng.stop();
      this.undoAudio(t, t.data);
      t.setData(new Int16Array(0));
      t.invalidateAnalysis();
      this.save(); this.refreshAll();
    });
    add('delete track', () => confirm('delete ' + t.name + '?', 'DELETE', () => {
      const at = this.s.tracks.indexOf(t);
      this.s.tracks.splice(at, 1);
      this.undoAction = () => this.s.tracks.splice(Math.min(at, this.s.tracks.length), 0, t);
      if (this.armed === t) this.armed = null;
      this.save(); this.rebuildRows(); this.refreshAll();
    }));
    list(t.name, items, w => acts[w]());
  }

  showAnalysisMenu(t) {
    const items = ['tempo', 'key', 'melody', 'chords by bar', 'chords for the melody', 'range and tuning', 'chords that fit the key', 'see all analysis', 'analyze again'];
    const map = [A_TEMPO, A_KEY, A_MELODY, A_CHORDS, A_SUGGEST, A_RANGE, A_FITS, A_ALL, -1];
    list('analysis', items, w => { if (map[w] < 0) this.reanalyze(t); else this.showAnalysis(t, map[w]); });
  }

  reanalyze(t) {
    if (!t.analysis || !t.analysis.edited) { this.analyzeTrack(t, A_ALL); return; }
    confirm("analyzing again will replace the notes and chords you've corrected on " + t.name + '.', 'ANALYZE AGAIN', () => this.analyzeTrack(t, A_ALL));
  }

  /** Called after any hand edit to a track's notes or chords. */
  analysisEdited(t) {
    t.analysisDirty = true;
    t.showNotes = t.analysis.notes.length > 0 || t.showNotes;
    this.save();
    this.refreshAll();
  }

  keyFor(f) {
    const s = this.s;
    return { root: s.keyRoot >= 0 ? s.keyRoot : f.base.keyRoot, minor: s.keyRoot >= 0 ? s.keyMinor : f.base.minor };
  }

  /** Pick the chord for one bar. Suggested chords become real ones the first time you change one. */
  editChord(t, bar, fromSuggested) {
    const f = t.analysis;
    if (!f) return;
    const { root, minor } = this.keyFor(f), s = this.s;
    let current = null;
    for (const c of f.chords) if (c.bar === bar) current = c.name;
    if (current == null && fromSuggested) {
      for (const g of Theory.harmonize(f.notes, root, minor, s.beatFrames(), Math.max(1, s.beatsPerBar))) if (g.bar === bar) current = g.best;
    }
    const names = [];
    if (root >= 0) for (const d of Theory.diatonic(root, minor)) { const n = d.substring(d.lastIndexOf(' ') + 1); if (!names.includes(n)) names.push(n); }
    const inKeyN = names.length;
    const items = names.map(n => n === current ? n + '   ●' : n);
    items.push('other chord…', 'no chord');
    list('bar ' + (bar + 1) + (current ? ' · now ' + current : ''), items, w => {
      if (w < inKeyN) this.setChord(t, bar, names[w], fromSuggested);
      else if (w === inKeyN) this.pickAnyChord(t, bar, fromSuggested);
      else this.setChord(t, bar, null, fromSuggested);
    });
  }

  pickAnyChord(t, bar, fromSuggested) {
    const all = [];
    for (let i = 0; i < 12; i++) for (const q of ['', 'm', 'dim']) all.push(NOTES[i] + q);
    list('bar ' + (bar + 1), all, w => this.setChord(t, bar, all[w], fromSuggested));
  }

  setChord(t, bar, name, fromSuggested) {
    const f = t.analysis, s = this.s;
    if (f.chords.length === 0 && fromSuggested) {
      const { root, minor } = this.keyFor(f);
      for (const g of Theory.harmonize(f.notes, root, minor, s.beatFrames(), Math.max(1, s.beatsPerBar))) f.chords.push({ bar: g.bar, name: g.best });
    }
    f.chords = f.chords.filter(c => c.bar !== bar);
    if (name != null) f.chords.push({ bar, name });
    refreshAfterEdit(f);
    this.analysisEdited(t);
    this.note(name == null ? 'removed the chord in bar ' + (bar + 1) : 'bar ' + (bar + 1) + ' is now ' + name);
  }

  showMenu() {
    const items = [], acts = [];
    const add = (label, fn) => { items.push(label); acts.push(fn); };
    add('export jam…', () => this.exportJam());
    add('import audio…', () => this.pickImport());
    add('lyrics', () => this.showLyrics());
    if (this.session) {
      add('sections…', () => this.showSections());
      add('bounce tracks…', () => this.showBounce());
      add('back up or share this jam', () => this.backupJam());
      add('open a jam backup…', () => this.pickBackup());
      add('circle of fifths', () => this.showCircle());
    }
    add(this.session ? 'switch to scratch mode (just the basics)' : 'switch to session mode (everything)', () => this.setMode(!this.session));
    add('settings', () => this.showSettings());
    add('calibrate latency', () => this.calibrate());
    add('rename jam', () => this.renameJam());
    add('delete jam', () => this.confirmDeleteJam(this.s.id, this.s.name));
    add('about', () => this.showAbout());
    list(null, items, w => acts[w]());
  }

  showAbout() {
    const l = body();
    para(l, 'created by Akshat Thakur. currently free to use since I have a day job.', 15);
    const a = el('a', 'link', 'akshatthakur.com');
    a.href = 'https://www.akshatthakur.com';
    a.target = '_blank';
    a.rel = 'noopener';
    l.appendChild(a);
    dialog({ title: 'about Roughhouse', body: l, buttons: [{ label: 'DONE' }] });
  }

  renameJam() { prompt('rename jam', this.s.name, v => { this.s.name = v; this.save(); this.refreshAll(); }); }

  // =============================================================== analysis

  async analyzeTrack(t, which) {
    if (this.eng.isPlaying()) this.eng.stop();
    const wait = busy('listening to ' + t.name + '…');
    try {
      const res = await job({ type: 'analyzeTrack', data: t.data, beatFrames: this.s.beatFrames(), beatsPerBar: Math.max(1, this.s.beatsPerBar) });
      wait.close();
      t.analysis = res;
      t.analysisDirty = true;
      t.showNotes = res.notes.length > 0;
      this.save();
      this.refreshAll();
      this.showAnalysis(t, which);
    } catch (e) {
      wait.close();
      this.note('analysis failed: ' + e.message);
    }
  }

  showAnalysis(t, which) {
    const f = t.analysis;
    if (!f) return;
    const s = this.s, l = body(), { root, minor } = this.keyFor(f), all = which === A_ALL;
    let title = t.name, mv = null;
    if (which & A_TEMPO) {
      if (all) heading(l, 'tempo'); else title = 'tempo · ' + t.name;
      if (f.base.bpm > 0) {
        para(l, 'about ' + fmtBpm(f.base.bpm) + ' BPM' + (f.base.bpmConfidence < 0.3 ? ' (low confidence)' : '') + '. this jam is at ' + fmtBpm(s.bpm) + ' BPM.', 14);
        this.tempoChoices(l, f.base.bpm);
      } else para(l, 'no steady pulse found. it needs a few seconds of rhythmic playing.', 14, 'dim');
    }
    if (which & A_KEY) {
      if (all) heading(l, 'key'); else title = 'key · ' + t.name;
      if (f.base.keyRoot >= 0) {
        para(l, keyName(f.base.keyRoot, f.base.minor) + (f.base.keyConfidence < 0.25 ? ' (it could also be the relative ' + (f.base.minor ? 'major' : 'minor') + ')' : ''), 14);
        addChip(chipRow(l), 'USE FOR JAM', () => {
          s.keyRoot = f.base.keyRoot; s.keyMinor = f.base.minor;
          this.refreshAll(); this.save();
          this.note('jam key set to ' + s.keyLabel());
        });
      } else para(l, 'not enough pitched material to tell.', 14, 'dim');
    }
    if (which & A_RANGE) {
      if (all) heading(l, 'range and tuning'); else title = 'range · ' + t.name;
      if (f.notes.length === 0) para(l, "no single-note melody found, so there's no range to show.", 14, 'dim');
      else {
        para(l, noteName(f.low) + ' to ' + noteName(f.high) + ' (' + rangeText(f.high - f.low) + ')', 14);
        const off = Math.abs(f.avgCents) < 6;
        para(l, off ? 'in tune on average.' : Math.round(Math.abs(f.avgCents)) + ' cents ' + (f.avgCents > 0 ? 'sharp' : 'flat') + ' on average.', 13, off ? 'dim' : 'amber');
      }
    }
    if (which & A_MELODY) {
      if (all) heading(l, 'melody'); else title = 'melody · ' + t.name;
      if (f.notes.length === 0) para(l, 'no single-note melody found. this works best on one voice or instrument at a time.', 14, 'dim');
      else {
        mv = new MelodyView(f, root, minor, s.beatFrames(), Math.max(1, s.beatsPerBar));
        let outCount = 0;
        if (root >= 0) for (const n of f.notes) if (!Theory.inKey(n.midi, root, minor)) outCount++;
        let summary = f.notes.length + ' notes in ' + mv.totalPhrases + (mv.totalPhrases === 1 ? ' phrase' : ' phrases');
        const repeated = mv.totalPhrases - mv.phrases.length;
        summary += repeated > 0 ? ', ' + repeated + (repeated === 1 ? ' of them a repeat.' : ' of them repeats.') : '.';
        if (root >= 0) summary += outCount === 0 ? ' every note is in ' + keyName(root, minor) + '.'
          : ' ' + outCount + (outCount === 1 ? ' note falls' : ' notes fall') + ' outside ' + keyName(root, minor) + ' (in red).';
        para(l, summary, 14);
        l.appendChild(mv.el);
        if (mv.phrases.length > MAX_ROWS) para(l, 'showing the first ' + MAX_ROWS + ' of ' + mv.phrases.length + ' different phrases. the track view has all of them.', 12, 'dim');
        const c = addChip(chipRow(l), t.showNotes ? 'HIDE NOTES ON LANE' : 'SHOW NOTES ON LANE', () => {
          t.showNotes = !t.showNotes;
          c.textContent = t.showNotes ? 'HIDE NOTES ON LANE' : 'SHOW NOTES ON LANE';
          this.save(); this.refreshAll();
        });
      }
    }
    if (which & A_CHORDS) {
      if (all) heading(l, 'chords by bar'); else title = 'chords · ' + t.name;
      if (f.chords.length === 0) para(l, "no clear chords. that's normal for a single melody line.", 14, 'dim');
      else para(l, chordText(f), 14);
    }
    if (which & A_SUGGEST) {
      if (all) heading(l, 'chords for the melody'); else title = 'chords for the melody · ' + t.name;
      if (root < 0) para(l, 'set a key first (KEY button, or USE FOR JAM above) so chords can be picked from it.', 14, 'dim');
      else if (f.notes.length === 0) para(l, 'no single-note melody to harmonize.', 14, 'dim');
      else {
        const sg = Theory.harmonize(f.notes, root, minor, s.beatFrames(), Math.max(1, s.beatsPerBar));
        para(l, 'chords from ' + keyName(root, minor) + ' that share the most notes with what you sang or played in each bar.', 13, 'dim');
        para(l, suggestionText(sg), 14);
      }
    }
    if (which & A_FITS) {
      if (root >= 0) {
        if (all) heading(l, 'writing in ' + keyName(root, minor)); else title = keyName(root, minor);
        this.fitsKey(l, root, minor);
      } else para(l, 'no key yet. set one with the KEY button or analyze a track with more pitched material.', 14, 'dim');
    }
    if (which & (A_MELODY | A_CHORDS)) para(l, 'bars and beats follow the jam\'s tempo and time signature.', 12, 'dim');
    const sheet = dialog({
      title: all ? t.name : title, body: l,
      buttons: [...(this.session ? [{ label: 'TRACK VIEW', left: true, fn: () => this.openTrack(t) }] : []), { label: 'DONE' }],
      onDismiss: () => { if (mv) mv.destroy(); },
    });
    if (mv) {
      mv.onPhrase = frame => {
        sheet.close();
        this.seekTo(Math.max(0, frame - Math.floor(s.beatFrames() / 2)));
        this.openTrack(t);
        if (this.trackView) this.trackView.focusOn(frame);
      };
    }
  }

  tempoChoices(l, bpm) {
    const row = chipRow(l);
    for (const b of [bpm, bpm * 2, bpm / 2]) {
      if (b < 40 || b > 240) continue;
      const use = Math.round(b * 2) / 2;
      addChip(row, 'USE ' + fmtBpm(use), () => { this.s.bpm = use; this.refreshAll(); this.save(); this.note('tempo set to ' + fmtBpm(use) + ' BPM'); });
    }
  }

  // ================================================================ editing

  posText() {
    const s = this.s, beat = Math.floor(Math.max(0, this.playhead()) / s.beatFrames()), bpb = Math.max(1, s.beatsPerBar);
    return 'bar ' + (Math.floor(beat / bpb) + 1) + '.' + (beat % bpb + 1);
  }

  showEditMenu(t) {
    if (this.eng.isRecording() || t.isEmpty()) return;
    const p = Math.max(0, this.playhead()), at = this.posText(), items = [], acts = [];
    const add = (label, fn) => { items.push(label); acts.push(fn); };
    add('trim everything before the playhead', () => this.applyEdit(t, Editor.trimBefore(t.data, p), 'trimmed before ' + at));
    add('trim everything after the playhead', () => this.applyEdit(t, Editor.trimAfter(t.data, p), 'trimmed after ' + at));
    if (this.session) add('split at the playhead (the rest goes to a new track)', () => this.split(t, p, at));
    add('move so it starts at the playhead', () => {
      const first = Editor.firstSound(t.data);
      if (first < 0) { this.note('this track is silent.'); return; }
      this.applyEdit(t, Editor.shift(t.data, p - first), 'moved to start at ' + at);
    });
    add('nudge earlier or later…', () => this.showNudge(t));
    if (this.session) {
      add('fade in up to the playhead', () => this.applyEdit(t, Editor.fadeInTo(t.data, p), 'faded in up to ' + at));
      add('fade out from the playhead', () => this.applyEdit(t, Editor.fadeOutFrom(t.data, p), 'fading out from ' + at));
    }
    list('edit ' + t.name + ' · ' + at, items, w => acts[w]());
  }

  applyEdit(t, nd, msg) {
    if (this.eng.isPlaying()) this.eng.stop();
    this.undoAudio(t, t.data);
    t.setData(nd);
    t.invalidateAnalysis();
    this.save();
    this.refreshAll();
    this.note(msg + '. UNDO puts it back.');
  }

  split(t, p, at) {
    if (p <= 0 || p >= t.data.length) { this.note('move the playhead inside the track to split it.'); return; }
    if (this.eng.isPlaying()) this.eng.stop();
    const before = t.data, s = this.s;
    const b = new Track(s.nextId(), t.name + ' b');
    b.copySettingsFrom(t);
    b.vocal = t.vocal;
    b.setData(Editor.tail(before, p));
    t.setData(Editor.trimAfter(before, p));
    t.invalidateAnalysis();
    s.tracks.splice(s.tracks.indexOf(t) + 1, 0, b);
    this.undoAction = () => {
      this.s.tracks = this.s.tracks.filter(x => x !== b);
      t.setData(before);
      t.invalidateAnalysis();
    };
    this.save(); this.rebuildRows(); this.refreshAll();
    this.note('split at ' + at + '. the second half is on ' + b.name + '.');
  }

  showNudge(t) {
    const original = t.data, l = body();
    let moved = 0;
    const amount = para(l, 'moved 0 ms', 15, 'amber');
    para(l, "use this to line up a take that's a little early or late.", 12, 'dim');
    const row = chipRow(l), beat = Math.round(this.s.beatFrames());
    const steps = [-beat, -SR / 100, SR / 100, beat], names = ['−1 BEAT', '−10 MS', '+10 MS', '+1 BEAT'];
    steps.forEach((st, i) => addChip(row, names[i], () => {
      if (this.eng.isPlaying()) this.eng.stop();
      moved += st;
      t.setData(Editor.shift(original, moved));
      const ms = Math.round(moved * 1000 / SR);
      amount.textContent = 'moved ' + (ms > 0 ? '+' : '') + ms + ' ms';
      this.refreshAll();
    }));
    dialog({
      title: 'nudge ' + t.name, body: l, buttons: [{ label: 'DONE' }],
      onDismiss: () => { if (moved !== 0) { t.invalidateAnalysis(); this.undoAudio(t, original); this.save(); this.refreshAll(); } },
    });
  }

  // ================================================================= lyrics

  showLyrics() {
    const s = this.s, col = el('div', 'lyrics');
    const help = el('div', 'para dim', 'start a line with [9] to pin it to bar 9. pinned lines show under the timeline while the jam plays. PIN TO PLAYHEAD adds the tag for the current bar.');
    help.style.fontSize = '12px';
    const ta = el('textarea', 'lyrics-text');
    ta.value = s.lyrics;
    ta.autocapitalize = 'sentences';
    ta.spellcheck = true;
    ta.placeholder = 'words go here';
    const d = fullScreen('lyrics · ' + s.name, [chip('PIN TO PLAYHEAD', () => {
      const bar = Math.floor(Math.max(0, this.playhead()) / s.barFrames()) + 1;
      const c = Math.max(0, ta.selectionStart || 0), txt = ta.value;
      const lineStart = c === 0 ? 0 : txt.lastIndexOf('\n', c - 1) + 1;
      const tag = '[' + bar + '] ';
      ta.value = txt.slice(0, lineStart) + tag + txt.slice(lineStart);
      ta.selectionStart = ta.selectionEnd = c + tag.length;
      ta.focus();
    })], col, () => {
      s.lyrics = ta.value;
      this.lyricPins = pinnedLyrics(s.lyrics);
      this.save();
      this.refreshAll();
    });
    col.append(help, ta);
    setTimeout(() => ta.focus(), 50);
    return d;
  }

  // =============================================================== sections

  /** Long-press on the timeline: add a section at that bar, or edit the one that's there. */
  markerMenu(frame) {
    const s = this.s, bar = Math.floor(Math.max(0, frame) / s.barFrames());
    const existing = s.markers.find(m => m.bar === bar);
    if (existing) {
      const m = existing;
      list(m.name + ' · bar ' + (bar + 1), ['loop this section', 'rename', 'delete'], w => {
        if (w === 0) this.loopSection(m);
        else if (w === 1) prompt('rename section', m.name, v => { m.name = v.toLowerCase(); this.save(); this.refreshAll(); });
        else { s.markers = s.markers.filter(x => x !== m); this.save(); this.refreshAll(); }
      });
      return;
    }
    list('mark bar ' + (bar + 1) + ' as', [...SECTION_NAMES, 'other name…'], w => {
      if (w < SECTION_NAMES.length) this.addMarker(bar, SECTION_NAMES[w]);
      else prompt('section name', '', v => this.addMarker(bar, v.toLowerCase()));
    });
  }

  addMarker(bar, name) {
    this.s.markers.push({ bar, name });
    this.save(); this.refreshAll();
    this.note(name + ' starts at bar ' + (bar + 1) + '. tap its label on the timeline to loop it.');
  }

  loopSection(m) {
    const s = this.s, bar = s.barFrames();
    let endBar = -1;
    for (const o of s.sortedMarkers()) if (o.bar > m.bar) { endBar = o.bar; break; }
    const inn = Math.round(m.bar * bar), out = endBar > 0 ? Math.round(endBar * bar) : -1;
    if (out < 0 && s.lengthFrames() <= inn) { this.note("there's nothing recorded in " + m.name + ' yet.'); return; }
    s.loopIn = inn; s.loopOut = out; s.loop = true;
    this.seekTo(inn);
    this.refreshAll();
    this.note('looping ' + m.name + (endBar > 0 ? ' (bars ' + (m.bar + 1) + ' to ' + endBar + ')' : ' to the end'));
  }

  showSections() {
    const ms = this.s.sortedMarkers();
    if (!ms.length) { this.note('long-press the timeline above the tracks to mark a section.'); return; }
    list('sections (tap to loop)', ms.map(m => m.name + '\nbar ' + (m.bar + 1)), w => this.loopSection(ms[w]));
  }

  // =============================================================== bouncing

  showBounce() {
    const ts = this.s.tracks.filter(t => !t.isEmpty());
    if (ts.length < 2) { this.note('you need at least two tracks with audio to bounce.'); return; }
    const l = body();
    const get = checks(l, ts.map(t => t.name));
    dialog({
      title: 'bounce which tracks?', body: l,
      buttons: [{ label: 'CANCEL' }, {
        label: 'NEXT', fn: () => {
          const pick = ts.filter((t, i) => get()[i]);
          if (pick.length < 2) { this.note('pick at least two tracks.'); return; }
          list('bounce ' + pick.length + ' tracks, then', ['keep the originals, muted', 'delete the originals'], k => this.runBounce(pick, k === 1));
        },
      }],
    });
  }

  async runBounce(pick, deleteOriginals) {
    if (this.eng.isPlaying()) this.eng.stop();
    const wait = busy('bouncing ' + pick.length + ' tracks…'), s = this.s;
    const name = 'bounce of ' + pick.map(t => s.tracks.indexOf(t) + 1).join('+');
    const snap = jamSnapshot(s, pick.map(t => ({ ...t, data: t.data, mute: false, solo: false })));
    snap.tracks.forEach(x => { x.mute = false; x.solo = false; });
    let mixed;
    try { mixed = await job({ type: 'bounce', jam: snap }); } catch (e) { wait.close(); this.note('bounce failed: ' + e.message); return; }
    wait.close();
    const b = new Track(s.nextId(), name);
    b.setData(mixed);
    const at = s.tracks.indexOf(pick[pick.length - 1]) + 1;
    s.tracks.splice(Math.min(at, s.tracks.length), 0, b);
    const removed = [], wasMuted = pick.map(t => t.mute);
    for (const t of pick) {
      if (deleteOriginals) { removed.push([s.tracks.indexOf(t), t]); s.tracks.splice(s.tracks.indexOf(t), 1); }
      else t.mute = true;
    }
    this.undoAction = () => {
      this.s.tracks = this.s.tracks.filter(x => x !== b);
      for (let i = removed.length - 1; i >= 0; i--) this.s.tracks.splice(Math.min(removed[i][0], this.s.tracks.length), 0, removed[i][1]);
      pick.forEach((t, i) => { t.mute = wasMuted[i]; });
    };
    this.save(); this.rebuildRows(); this.refreshAll();
    this.note('bounced to ' + b.name + (deleteOriginals ? '.' : '. the originals are muted.'));
  }

  // =================================================================== MIDI

  exportMidi() {
    const s = this.s, parts = [], chords = [], bar = s.barFrames();
    let firstMelody = null;
    for (const t of s.tracks) {
      if (!t.analysis) continue;
      if (t.analysis.notes.length) { parts.push({ name: t.name, notes: t.analysis.notes }); if (!firstMelody) firstMelody = t.analysis; }
      if (!chords.length && t.analysis.chords.length) {
        for (const c of t.analysis.chords) chords.push({ start: Math.round(c.bar * bar), len: Math.round(bar), midis: voice(c.name) });
      }
    }
    const root = s.keyRoot >= 0 ? s.keyRoot : (firstMelody ? firstMelody.base.keyRoot : -1);
    const minor = s.keyRoot >= 0 ? s.keyMinor : !!(firstMelody && firstMelody.base.minor);
    if (!chords.length && firstMelody && root >= 0) {
      for (const g of Theory.harmonize(firstMelody.notes, root, minor, s.beatFrames(), Math.max(1, s.beatsPerBar))) {
        chords.push({ start: Math.round(g.bar * bar), len: Math.round(bar), midis: voice(g.best) });
      }
    }
    if (!parts.length && !chords.length) { this.note('analyze a track first (long-press it) so there are notes or chords to export.'); return; }
    const bytes = writeMidi(s, SR, parts, chords);
    this.deliver(new Blob([bytes], { type: 'audio/midi' }), fileName(s.name, 'Roughhouse jam') + '.mid', 'MIDI');
  }

  /** Hands a finished file to the person: share sheet where the device has one, a download otherwise. */
  deliver(blob, name, what) {
    const file = new File([blob], name, { type: blob.type });
    const canShare = navigator.canShare && navigator.canShare({ files: [file] });
    const size = blob.size > 1e6 ? (blob.size / 1e6).toFixed(1) + ' MB' : Math.max(1, Math.round(blob.size / 1e3)) + ' KB';
    const download = () => {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = name;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 60000);
    };
    const buttons = [{ label: 'CLOSE', left: true }];
    if (canShare) buttons.push({ label: 'SHARE', fn: () => navigator.share({ files: [file], title: name }).catch(() => {}) });
    buttons.push({ label: 'SAVE', fn: download });
    message(name + '  ·  ' + size, buttons, { title: what + ' ready' });
  }

  // ================================================================ backups

  async backupJam() {
    const s = this.s;
    if (s.lengthFrames() === 0) { this.note("there's nothing in this jam to back up yet."); return; }
    if (this.eng.isPlaying()) this.eng.stop();
    await this.save();
    const wait = busy('packing up the jam…');
    try {
      const blob = await writeZip(s.toFiles());
      wait.close();
      this.deliver(blob, fileName(s.name, 'jam') + BACKUP_EXT, 'backup');
    } catch (e) {
      wait.close();
      this.note('backup failed: ' + e.message);
    }
  }

  pickBackup() {
    pickFile('.zip,application/zip,application/x-zip-compressed,application/octet-stream', f => this.restoreBackup(f));
  }

  async restoreBackup(file) {
    if (this.eng.isPlaying()) this.eng.stop();
    const wait = busy('opening the backup…');
    try {
      const entries = await readZip(await file.arrayBuffer());
      const nj = await importFiles(entries);
      wait.close();
      await this.leaveCurrent();
      this.openJam(nj);
      this.note('opened ' + nj.name + '.');
    } catch (e) {
      wait.close();
      this.note(e.message || "that backup couldn't be read.");
    }
  }

  // ============================================================= track view

  openTrack(t) {
    if (this.eng.isRecording()) return;
    const col = el('div', 'tv');
    const info = el('div', 'tv-info');
    const an = chip(t.analysis ? 'ANALYSIS' : 'ANALYZE', () => { if (t.analysis) this.showAnalysisMenu(t); else if (!t.isEmpty()) this.analyzeTrack(t, A_ALL); });
    const tv = new TrackView(this, t);
    const actions = el('div', 'tv-actions');
    actions.append(an, chip('EDIT', () => { if (!t.isEmpty()) this.showEditMenu(t); }), chip('−', () => tv.zoom(1 / 1.6), 'zoom'), chip('+', () => tv.zoom(1.6), 'zoom'), chip('FIT', () => tv.fit()));
    const noteEdit = el('div', 'tv-noteedit');
    noteEdit.hidden = true;
    const noteNameEl = el('span', 'tv-note');
    const up = chip('UP'), dn = chip('DOWN'), sh = chip('SHORTER'), lo = chip('LONGER'), del = chip('DELETE');
    noteEdit.append(noteNameEl, up, dn, sh, lo, del);
    tv.onSelect = n => {
      noteEdit.hidden = !n;
      if (n) {
        const beat = Math.floor(n.start / this.s.beatFrames()), bpb = Math.max(1, this.s.beatsPerBar);
        noteNameEl.textContent = noteName(n.midi) + '  ·  bar ' + (Math.floor(beat / bpb) + 1) + '.' + (beat % bpb + 1);
      }
    };
    const edited = () => { refreshAfterEdit(t.analysis); this.analysisEdited(t); tv.select(tv.selected); };
    const half = Math.round(this.s.beatFrames() / 2);
    up.addEventListener('click', () => { if (tv.selected) { tv.selected.midi++; edited(); } });
    dn.addEventListener('click', () => { if (tv.selected) { tv.selected.midi--; edited(); } });
    sh.addEventListener('click', () => { if (tv.selected) { tv.selected.len = Math.max(half / 4, tv.selected.len - half); edited(); } });
    lo.addEventListener('click', () => { if (tv.selected) { tv.selected.len += half; edited(); } });
    del.addEventListener('click', () => {
      if (!tv.selected || !t.analysis) return;
      t.analysis.notes = t.analysis.notes.filter(n => n !== tv.selected);
      tv.select(null);
      refreshAfterEdit(t.analysis);
      this.analysisEdited(t);
    });
    const hint = el('div', 'tv-hint', 'tap a note or chord to fix it. long-press the roll to add a note. pinch or ctrl+scroll to zoom, drag to scroll.');
    const keys = el('div', 'keys tv-keys');
    const rw = new Key(Key.REW, 'rewind'), pl = new Key(Key.PLAY, 'play'), st = new Key(Key.STOP, 'stop');
    rw.el.addEventListener('click', () => { this.seekTo(0); tv.invalidate(); });
    pl.el.addEventListener('click', () => { this.eng.wake(); this.onPlay(); });
    st.el.addEventListener('click', () => { this.onStopKey(); tv.invalidate(); });
    keys.append(rw.el, pl.el, st.el);
    col.append(info, actions, tv.el, noteEdit, hint, keys);

    const m = chip('M', () => { t.mute = !t.mute; this.refreshAll(); }), so = chip('S', () => { t.solo = !t.solo; this.refreshAll(); }), fx = chip('FX', () => this.showFx(t));
    const chipsRefresh = () => { setChip(m, t.mute, 'blue'); setChip(so, t.solo, 'yellow'); setChip(fx, t.fxActive(), 'amber'); };
    const refreshInfo = () => {
      const f = t.analysis;
      an.textContent = f ? 'ANALYSIS' : 'ANALYZE';
      if (!f) { info.textContent = t.isEmpty() ? 'this track is empty.' : 'not analyzed yet. tap ANALYZE to find its notes and chords.'; return; }
      const b = [];
      if (f.base.keyRoot >= 0) b.push(keyName(f.base.keyRoot, f.base.minor));
      if (f.base.bpm > 0) b.push('about ' + fmtBpm(f.base.bpm) + ' BPM');
      if (f.low > 0) b.push('range ' + noteName(f.low) + ' to ' + noteName(f.high));
      b.push(f.notes.length + ' notes');
      info.textContent = b.join('  ·  ');
    };
    refreshInfo();
    chipsRefresh();
    this.trackRefresh = () => { refreshInfo(); chipsRefresh(); pl.set(this.eng.isPlaying(), this.eng.isPlaying()); };
    this.trackView = tv;
    this.trackDlg = fullScreen(t.name, [m, so, fx], col, () => {
      tv.destroy();
      this.trackView = null; this.trackDlg = null; this.trackRefresh = null;
      this.refreshAll();
    });
  }

  // ================================================================= import

  pickImport() { pickFile('audio/*,video/*,.mp3,.wav,.m4a,.aac,.flac,.ogg,.opus,.aif,.aiff', f => this.importAudio(f)); }

  async importAudio(file) {
    if (this.eng.isPlaying()) this.eng.stop();
    const wait = busy('loading audio…');
    const jamWasEmpty = this.s.lengthFrames() === 0;
    let data, r = null;
    try {
      data = await decodeToTrack(await file.arrayBuffer());
    } catch (e) {
      wait.close();
      this.note("couldn't read that file. it may be a format this browser can't decode.");
      return;
    }
    try { r = await job({ type: 'analyze', data }); } catch { r = null; }
    wait.close();
    let name = file.name.replace(/\.[^.]+$/, '') || 'imported';
    if (name.length > 28) name = name.substring(0, 28);
    const t = new Track(this.s.nextId(), name);
    t.setData(data);
    this.s.tracks.push(t);
    this.undoAdded(t);
    this.armed = null;
    this.save(); this.rebuildRows(); this.refreshAll();
    this.offerDetected(t, r, jamWasEmpty);
  }

  offerDetected(t, r, auto) {
    const s = this.s, hasTempo = r && r.bpm > 0, hasKey = this.session && r && r.keyRoot >= 0;
    if (!hasTempo && !hasKey) { this.note('imported ' + t.name + '.'); return; }
    if (auto) {
      let b = 'imported ' + t.name + '.';
      if (hasTempo) { s.bpm = Math.max(40, Math.min(240, r.bpm)); b += ' tempo set to ' + fmtBpm(s.bpm) + ' BPM'; }
      if (hasKey) { s.keyRoot = r.keyRoot; s.keyMinor = r.minor; b += (hasTempo ? ', key ' : ' key set to ') + s.keyLabel(); }
      b += '. tap BPM or KEY to adjust.';
      this.refreshAll(); this.save(); this.note(b);
      return;
    }
    const l = body();
    if (hasTempo) {
      heading(l, 'tempo');
      para(l, 'about ' + fmtBpm(r.bpm) + ' BPM (jam is ' + fmtBpm(s.bpm) + ')', 14);
      this.tempoChoices(l, r.bpm);
    }
    if (hasKey) {
      heading(l, 'key');
      para(l, keyName(r.keyRoot, r.minor) + ' (jam is ' + s.keyLabel() + ')', 14);
      addChip(chipRow(l), 'USE FOR JAM', () => { s.keyRoot = r.keyRoot; s.keyMinor = r.minor; this.refreshAll(); this.save(); this.note('jam key set to ' + s.keyLabel()); });
    }
    dialog({ title: 'found in ' + t.name, body: l, buttons: [{ label: 'KEEP CURRENT' }] });
  }

  // =================================================================== jams

  openJam(nj) {
    if (this.eng.isPlaying()) this.eng.stop();
    this.s = nj;
    this.eng.s = nj;
    this.armed = null;
    this.undoAction = null;
    this.cursor = 0;
    prefs.set('last', nj.id);
    this.lyricPins = pinnedLyrics(nj.lyrics);
    this.rebuildRows();
    this.refreshAll();
  }

  /** Saves the current jam, or throws it away if nothing was recorded. */
  async leaveCurrent() {
    if (this.eng.isPlaying()) await this.eng.stop();
    if (this.s.lengthFrames() === 0) await deleteJam(this.s.id).catch(() => {});
    else await this.save();
  }

  async newJam() {
    if (this.s.lengthFrames() === 0 && this.s.tracks.length === 0) { this.note("you're already on a fresh jam."); return; }
    await this.leaveCurrent();
    this.openJam(Jam.create());
    this.note('new jam');
  }

  async showJams() {
    if (this.eng.isPlaying()) this.eng.stop();
    await this.save();
    const infos = (await listJams()).filter(i => i.len > 0 || i.id === this.s.id);
    if (!infos.length) { this.note('no saved jams yet.'); return; }
    const labels = infos.map(i => {
      const sec = Math.floor(i.len / SR);
      return (i.id === this.s.id ? '● ' : '') + i.name + '\n' + Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0') + '  ·  ' + i.tracks + (i.tracks === 1 ? ' track' : ' tracks');
    });
    list('jams', labels, w => this.openJamId(infos[w].id), w => this.confirmDeleteJam(infos[w].id, infos[w].name));
    this.note('long-press (or right-click) a jam to delete it.');
  }

  async openJamId(id) {
    if (id === this.s.id) return;
    const nj = await loadJam(id);
    if (!nj) { this.note("couldn't open that jam."); return; }
    await this.leaveCurrent();
    this.openJam(nj);
  }

  confirmDeleteJam(id, name) {
    confirm('delete "' + name + '" and all its tracks?', 'DELETE', async () => {
      const current = id === this.s.id;
      if (current && this.eng.isPlaying()) await this.eng.stop();
      await deleteJam(id);
      if (current) this.openJam(Jam.create());
      this.note('deleted');
    });
  }

  // ================================================================= export

  exportJam() {
    if (this.s.lengthFrames() === 0) { this.note('nothing to export yet.'); return; }
    const fs = FORMATS.filter(f => f.id !== 'm4a' || this.m4a);
    const items = fs.map(f => f.label + '\n' + f.hint);
    if (this.session) items.push('MIDI\nnotes and chords from analyzed tracks');
    list('export jam as', items, w => { if (w === fs.length) this.exportMidi(); else this.runExport(fs[w]); });
  }

  async runExport(fmt) {
    if (this.eng.isPlaying()) this.eng.stop();
    this.save();
    const label = 'rendering ' + fmt.ext.toUpperCase() + '…';
    const wait = busy(label);
    try {
      const blob = await job({ type: 'export', jam: jamSnapshot(this.s), format: fmt.id }, f => wait.setMessage(label + '  ' + Math.round(f * 100) + '%'));
      wait.close();
      this.deliver(blob, fileName(this.s.name, 'Roughhouse jam') + '.' + fmt.ext, fmt.ext.toUpperCase());
    } catch (e) {
      wait.close();
      this.note('export failed: ' + e.message);
    }
  }

  // ================================================================ helpers

  save() {
    const s = this.s;
    if (!s || (s.lengthFrames() === 0 && s.tracks.length === 0)) return this.saving; // don't litter empty jams
    this.saving = this.saving.then(() => saveJam(s)).catch(e => this.note('save failed: ' + (e && e.message || e)));
    return this.saving;
  }
}

// ---------------------------------------------------------------- helpers

function setChip(c, on, color) {
  c.classList.toggle('on', on);
  c.dataset.color = color;
}

function rangeText(semis) {
  const oct = Math.floor(semis / 12), rest = semis % 12;
  if (oct === 0) return semis + ' semitones';
  return oct + (oct === 1 ? ' octave' : ' octaves') + (rest > 0 ? ' + ' + rest : '');
}

function suggestionText(sg) {
  return sg.map((g, i) => (i > 0 ? (i % 2 === 0 ? '\n' : '      ') : '') + 'bar ' + (g.bar + 1) + ':  ' + g.best + (g.alt ? '  (or ' + g.alt + ')' : '')).join('');
}

function chordText(f) {
  let b = '', i = 0;
  const ch = f.chords;
  while (i < ch.length) {
    const c = ch[i];
    let j = i;
    while (j + 1 < ch.length && ch[j + 1].name === c.name && ch[j + 1].bar === ch[j].bar + 1) j++;
    const a = c.bar + 1, z = ch[j].bar + 1;
    if (b) b += i % 4 === 0 ? '\n' : '    ';
    b += (a === z ? 'bar ' + a : 'bars ' + a + '–' + z) + ':  ' + c.name;
    i = j + 1;
  }
  return b;
}

/** Full-screen sheet with BACK, a serif title and extra chips in the top bar. */
function fullScreen(title, extra, content, onDismiss) {
  const wrap = el('div', 'fs');
  const top = el('div', 'fs-top');
  let d;
  top.appendChild(chip('BACK', () => d.close()));
  const t = el('div', 'fs-title', title);
  top.appendChild(t);
  for (const e of extra) top.appendChild(e);
  wrap.append(top, content);
  d = dialog({ body: wrap, full: true, onDismiss, noFocus: true });
  return d;
}

function pickFile(accept, fn) {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = accept;
  input.style.display = 'none';
  input.addEventListener('change', () => { if (input.files && input.files[0]) fn(input.files[0]); input.remove(); });
  document.body.appendChild(input);
  input.click();
}

/** Decodes any audio the browser can read into a mono 16-bit track at 48 kHz. */
async function decodeToTrack(buf) {
  const OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
  const ctx = new OAC(1, 1, SR);
  const ab = await new Promise((res, rej) => {
    const p = ctx.decodeAudioData(buf, res, rej);
    if (p && p.then) p.then(res, rej);
  });
  const n = ab.length, ch = ab.numberOfChannels;
  const chans = [];
  for (let c = 0; c < ch; c++) chans.push(ab.getChannelData(c));
  let data;
  if (ab.sampleRate === SR) {
    data = new Int16Array(n);
    for (let i = 0; i < n; i++) {
      let sum = 0;
      for (let c = 0; c < ch; c++) sum += chans[c][i];
      const v = sum / ch * 32767;
      data[i] = v > 32767 ? 32767 : v < -32768 ? -32768 : v;
    }
  } else {
    // some browsers hand back the file's own rate: resample with a cubic interpolator
    const mono = new Float32Array(n);
    for (let i = 0; i < n; i++) { let sum = 0; for (let c = 0; c < ch; c++) sum += chans[c][i]; mono[i] = sum / ch; }
    const m = Math.floor(n * SR / ab.sampleRate), step = ab.sampleRate / SR;
    data = new Int16Array(m);
    for (let i = 0; i < m; i++) {
      const pos = i * step, k = Math.floor(pos), t = pos - k;
      const p0 = mono[Math.max(0, k - 1)], p1 = mono[Math.min(n - 1, k)], p2 = mono[Math.min(n - 1, k + 1)], p3 = mono[Math.min(n - 1, k + 2)];
      const y = p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)));
      const v = y * 32767;
      data[i] = v > 32767 ? 32767 : v < -32768 ? -32768 : v;
    }
  }
  return data;
}

// ------------------------------------------------------------------ boot

const app = new App();
window.roughhouse = app;
// canvases draw with these faces, so ask for them up front and redraw once they're in
Promise.all(['12px Heros', 'bold 12px Heros', '12px Termes', 'italic 12px Termes', 'bold 12px Termes'].map(f => document.fonts.load(f)))
  .then(() => { app.refreshAll(); for (const r of app.rows) r.wave.invalidate(); }).catch(() => {});
app.start().catch(e => { console.error(e); app.note('something went wrong starting up: ' + e.message); });

if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
