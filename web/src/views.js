// Tape lanes, the timeline ruler, the full-screen track view and the melody summary.
// Ported from WaveView.java, Ruler.java, TrackView.java and MelodyView.java.
import { C, F, CanvasView, gestures, rect, fillRoundRect, spaced, argb } from './ui.js';
import { PB, SR, floorMod } from './timing.js';
import { inKey, harmonize } from './theory.js';
import { noteName, NOTES } from './pitch.js';
import { refreshAfterEdit } from './analyzer.js';

const METER_W = 5, METER_GAP = 3;

function keyOf(a, an) {
  const s = a.s;
  const root = s.keyRoot >= 0 ? s.keyRoot : (an ? an.base.keyRoot : -1);
  const minor = s.keyRoot >= 0 ? s.keyMinor : !!(an && an.base.minor);
  return { root, minor };
}

/** One tape lane: waveform on the shared timeline, live take, detected notes, LED meter. */
export class WaveView extends CanvasView {
  constructor(a, t) {
    super('wave');
    this.a = a; this.t = t;
    this.el.setAttribute('aria-label', `${t.name}: tap for the track, long-press for its menu`);
    gestures(this.el, {
      tap: () => { if (a.session) a.openTrack(t); else a.showTrackMenu(t); },
      long: () => a.showTrackMenu(t),
    });
  }
  draw(c, fullW, h) {
    const a = this.a, t = this.t, s = a.s;
    const w = fullW - METER_W - METER_GAP;
    rect(c, 0, 0, fullW, h, C.LANE);
    const vf = a.viewFrames(), fpp = vf / w, mid = h / 2, amp = h / 2 - 3;
    if (s.loop) {
      const lout = s.loopOut > 0 ? s.loopOut : s.lengthFrames();
      if (lout > s.loopIn) rect(c, s.loopIn / fpp, 0, lout / fpp, h, argb(0x1AE9A23B));
    }
    const bar = s.barFrames();
    if (bar / fpp > 6) {
      c.fillStyle = argb(0xFF1F1C19);
      for (let f = bar; f < vf; f += bar) c.fillRect(Math.floor(f / fpp), 0, 1, h);
    }
    const audible = a.isAudible(t);
    const notes = a.session && t.showNotes && t.analysis && t.analysis.notes.length > 0;
    const pk = t.peaks;
    c.fillStyle = !audible ? argb(0xFF3A3630) : notes ? argb(0x66E9A23B) : C.AMBER;
    for (let xi = 0; xi < w; xi++) {
      const i0 = Math.floor(xi * fpp / PB);
      if (i0 >= pk.length) break;
      const i1 = Math.min(pk.length, Math.max(i0 + 1, Math.floor((xi + 1) * fpp / PB)));
      let m = 0;
      for (let i = i0; i < i1; i++) if (pk[i] > m) m = pk[i];
      const hh = Math.max(0.5, m * amp);
      c.fillRect(xi, mid - hh, 1, hh * 2);
    }
    if (notes) this.drawNotes(c, w, h, fpp);

    const e = a.eng;
    if (e.isRecording() && e.recTrack === t) {
      const rp = e.recPeaks, rc = e.recPeakCount;
      const first = e.recOrigin - e.latencyFrames, punch = e.punchStart;
      c.fillStyle = C.RED;
      for (let k = 0; k < rc; k++) {
        const f = first + k * PB;
        if (f < punch) continue;
        const xx = f / fpp;
        if (xx > w) break;
        const hh = Math.max(0.5, rp[k] * amp);
        c.fillRect(xx, mid - hh, 1, hh * 2);
      }
    }
    const ph = a.playhead();
    if (ph >= 0) rect(c, ph / fpp - 0.6, 0, ph / fpp + 0.6, h, C.CREAM);

    const armed = t === a.nextRecTrack();
    c.font = F.sansBold(9.5);
    c.fillStyle = armed ? C.RED : C.DIM;
    spaced(c, t.name.toUpperCase() + (t.vocal && a.session ? '  ·  VOX' : ''), 5, 12, 0.1);

    const m = e.isPlaying() ? t.meter : 0;
    const lit = Math.ceil(Math.max(0, Math.min(1, m > 1e-4 ? (20 * Math.log10(m) + 30) / 30 : 0)) * 10);
    const segH = (h - 4) / 10, mx = fullW - METER_W;
    for (let i = 0; i < 10; i++) {
      const y1 = h - 2 - (i + 1) * segH + 1;
      const col = i >= 9 ? C.RED : i >= 7 ? C.AMBER : C.GREEN;
      rect(c, mx, y1, fullW, y1 + segH - 1.5, i < lit ? col : argb(0xFF24211D));
    }
  }
  drawNotes(c, w, h, fpp) {
    const an = this.t.analysis, lo = an.low, hi = Math.max(an.high, an.low + 1);
    const top = 16, bottom = h - 4;
    const rowH = Math.max(2, Math.min(6, (bottom - top) / (hi - lo + 1)));
    const { root, minor } = keyOf(this.a, an);
    c.font = F.sansBold(8);
    for (const n of an.notes) {
      const x1 = n.start / fpp, x2 = (n.start + n.len) / fpp;
      if (x2 < 0 || x1 > w) continue;
      const y = bottom - (n.midi - lo + 1) * (bottom - top) / (hi - lo + 1);
      const out = root >= 0 && !inKey(n.midi, root, minor);
      rect(c, x1, y, Math.max(x1 + 2, x2 - 1), y + rowH, out ? C.RED : C.CREAM);
      if (x2 - x1 > 22) { c.fillStyle = C.DIM; c.textAlign = 'left'; c.fillText(noteName(n.midi), x1 + 2, y - 2); }
    }
  }
}

/** Timeline for the whole jam: bar numbers, loop region, overview of every track, playhead, sections. */
export class Ruler extends CanvasView {
  constructor(a) {
    super('ruler');
    this.a = a;
    this.el.setAttribute('aria-label', 'timeline: tap or drag to move the playhead, long-press to mark a section');
    const labelH = 13;
    const inLabels = p => p.y < labelH + 4;
    gestures(this.el, {
      down: p => { if (!a.session || !inLabels(p)) this.seekAt(p.x); },
      drag: p => this.seekAt(p.x),
      tap: p => {
        const m = a.session && inLabels(p) ? this.markerAt(p.x) : null;
        if (m) a.loopSection(m); else this.seekAt(p.x);
      },
      long: p => { if (a.session) a.markerMenu(this.frameAt(p.x)); },
    });
  }
  contentW() { return this.w - METER_W - METER_GAP; }
  frameAt(x) { const cw = this.contentW(); return Math.floor(Math.max(0, Math.min(cw, x)) / cw * this.a.viewFrames()); }
  seekAt(x) { this.a.seekTo(this.frameAt(x)); this.invalidate(); }
  markerAt(x) {
    const a = this.a, fpp = a.viewFrames() / this.contentW(), bar = a.s.barFrames();
    const c = this.el.getContext('2d');
    c.font = F.sansBold(9);
    let hit = null;
    for (const m of a.s.sortedMarkers()) {
      const mx = m.bar * bar / fpp, wl = c.measureText(m.name).width + 8;
      if (x >= mx - 4 && x <= mx + Math.max(wl, 24)) hit = m;
    }
    return hit;
  }
  draw(c, W, H) {
    const a = this.a, s = a.s, w = this.contentW();
    const vf = a.viewFrames(), fpp = vf / w;
    rect(c, 0, 0, W, H, argb(0xFF0F0D0C));
    if (s.loop) {
      const lout = s.loopOut > 0 ? s.loopOut : s.lengthFrames();
      if (lout > s.loopIn) rect(c, s.loopIn / fpp, 0, lout / fpp, H, argb(0x33E9A23B));
    }
    c.fillStyle = argb(0xFF3A342C);
    const mid = H * 0.7, amp = H * 0.24;
    for (let x = 0; x < w; x++) {
      const i0 = Math.floor(x * fpp / PB), i1 = Math.max(i0 + 1, Math.floor((x + 1) * fpp / PB));
      let m = 0;
      for (const t of s.tracks) {
        const pk = t.peaks, e = Math.min(i1, pk.length);
        for (let i = i0; i < e; i++) if (pk[i] > m) m = pk[i];
      }
      if (m > 0) c.fillRect(x, mid - m * amp, 1, m * amp * 2);
    }
    const lh = 13, bar = s.barFrames();
    c.font = F.sansBold(9);
    c.textAlign = 'left';
    const ms = a.session ? s.sortedMarkers() : [];
    ms.forEach((m, i) => {
      const x = m.bar * bar / fpp;
      const next = i + 1 < ms.length ? ms[i + 1].bar * bar / fpp : w;
      if (x > w) return;
      rect(c, x, 0, Math.max(x + 2, next - 1), lh, argb(0x55E9A23B));
      rect(c, x, 0, x + 1.5, H, C.AMBER);
      c.fillStyle = C.CREAM;
      let label = m.name;
      const room = next - x - 6;
      if (room > 12) {
        while (label.length > 1 && c.measureText(label).width > room) label = label.slice(0, -1);
        c.fillText(label, x + 4, lh - 3);
      }
    });
    let every = 1;
    while (bar * every / fpp < 28) every *= 2;
    c.font = F.sansBold(8.5);
    for (let b = 0; b * bar < vf; b++) {
      const x = Math.floor(b * bar / fpp), label = b % every === 0;
      rect(c, x, lh, x + 1, lh + (label ? 9 : 5), label ? argb(0xFF5E5A51) : argb(0xFF2B2824));
      if (label) { c.fillStyle = C.DIM; c.fillText(String(b + 1), x + 3, lh + 10); }
    }
    const ph = Math.max(0, a.playhead()), px = Math.min(w, ph / fpp);
    const col = a.eng.isRecording() ? C.RED : C.CREAM;
    rect(c, px - 0.75, 0, px + 0.75, H, col);
    c.fillStyle = col;
    c.beginPath();
    c.moveTo(px - 5, H); c.lineTo(px + 5, H); c.lineTo(px, H - 6); c.closePath(); c.fill();
  }
}

/**
 * Full-screen view of one track: bar ruler, chord row, piano roll of detected notes with names,
 * and the waveform underneath. Pinch or ctrl+wheel to zoom, drag to scroll, tap to seek.
 */
export class TrackView extends CanvasView {
  constructor(a, t) {
    super('trackview');
    this.a = a; this.t = t;
    this.left = 0; this.fpp = -1; this.userMoved = false;
    this.selected = null; this.onSelect = null;
    this.suggFor = null; this.suggKey = null; this.suggCache = [];
    this.L = {};
    const pointers = new Map();
    let pinch = null, dragFrom = null;
    gestures(this.el, {
      down: p => { dragFrom = { x: p.x, left: this.left }; },
      drag: p => {
        if (pinch || pointers.size > 1) return;
        this.left = this.clampLeft(dragFrom.left - (p.x - dragFrom.x) * this.fpp);
        this.userMoved = true;
        this.invalidate();
      },
      tap: p => this.onTap(p),
      long: p => this.onLong(p),
    });
    this.el.addEventListener('pointerdown', e => {
      pointers.set(e.pointerId, this.at(e));
      if (pointers.size === 2) {
        const [p1, p2] = [...pointers.values()];
        pinch = { d: Math.abs(p1.x - p2.x) || 1, fpp: this.fpp, fx: (p1.x + p2.x) / 2 - this.gutter() };
        pinch.at = this.left + pinch.fx * this.fpp;
      }
    });
    this.el.addEventListener('pointermove', e => {
      if (!pointers.has(e.pointerId)) return;
      pointers.set(e.pointerId, this.at(e));
      if (pinch && pointers.size === 2) {
        const [p1, p2] = [...pointers.values()];
        const d = Math.abs(p1.x - p2.x) || 1;
        this.fpp = this.clampFpp(pinch.fpp * pinch.d / d);
        this.left = this.clampLeft(pinch.at - pinch.fx * this.fpp);
        this.userMoved = true;
        this.invalidate();
      }
    });
    const end = e => { pointers.delete(e.pointerId); if (pointers.size < 2) pinch = null; };
    this.el.addEventListener('pointerup', end);
    this.el.addEventListener('pointercancel', end);
    this.el.addEventListener('wheel', e => {
      e.preventDefault();
      const p = this.at(e);
      if (e.ctrlKey || e.metaKey) {
        const fx = p.x - this.gutter(), at = this.left + fx * this.fpp;
        this.fpp = this.clampFpp(this.fpp * Math.exp(e.deltaY * 0.01));
        this.left = this.clampLeft(at - fx * this.fpp);
      } else {
        const d = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
        this.left = this.clampLeft(this.left + d * this.fpp);
      }
      this.userMoved = true;
      this.invalidate();
    }, { passive: false });
  }

  gutter() { return 38; }
  total() { return Math.max(this.t.data.length, this.a.s.lengthFrames(), 4 * SR); }
  clampFpp(v) {
    const w = Math.max(1, this.w - this.gutter());
    return Math.max(32, Math.min(this.total() * 1.05 / w, v));
  }
  clampLeft(v) {
    const w = Math.max(1, this.w - this.gutter());
    return Math.max(0, Math.min(v, this.total() - w * this.fpp * 0.5));
  }
  zoom(f) {
    const cw = this.w - this.gutter(), at = this.left + cw / 2 * this.fpp;
    this.fpp = this.clampFpp(this.fpp / f);
    this.left = this.clampLeft(at - cw / 2 * this.fpp);
    this.userMoved = true;
    this.invalidate();
  }
  select(n) { this.selected = n; if (this.onSelect) this.onSelect(n); this.invalidate(); }
  fit() { this.fpp = -1; this.left = 0; this.userMoved = false; this.invalidate(); }
  focusOn(frame) {
    requestAnimationFrame(() => {
      const w = Math.max(1, this.el.getBoundingClientRect().width - this.gutter());
      this.w = this.el.getBoundingClientRect().width;
      const bars = this.a.s.barFrames() * 4;
      this.fpp = this.clampFpp(bars / w);
      this.left = this.clampLeft(frame - w * this.fpp * 0.1);
      this.userMoved = true;
      this.invalidate();
    });
  }

  noteAt(x, y) {
    const an = this.t.analysis, L = this.L;
    if (!an || y < L.rollTop || y > L.rollBot) return null;
    const slack = 6, g = this.gutter();
    let best = null;
    for (const n of an.notes) {
      const x1 = g + (n.start - this.left) / this.fpp, x2 = g + (n.start + n.len - this.left) / this.fpp;
      const ny = L.rollBot - (n.midi - L.lo + 1) * L.rowH;
      if (x >= x1 - slack && x <= Math.max(x2, x1 + 3) + slack && y >= ny - slack && y <= ny + L.rowH + slack) best = n;
    }
    return best;
  }

  onTap(p) {
    const x = p.x - this.gutter(), a = this.a, t = this.t, L = this.L;
    if (x < 0) return;
    const an = t.analysis;
    if (an && L.chordH > 0 && p.y >= L.rulerH && p.y < L.rulerH + L.chordH) {
      const bar = Math.floor((this.left + x * this.fpp) / L.bar);
      if (bar >= 0) a.editChord(t, bar, L.showSugg);
      return;
    }
    const hit = this.noteAt(p.x, p.y);
    if (hit) { this.select(hit); return; }
    if (this.selected) { this.select(null); return; }
    a.seekTo(Math.max(0, Math.floor(this.left + x * this.fpp)));
    this.userMoved = false;
    this.invalidate();
  }

  onLong(p) {
    const an = this.t.analysis, L = this.L;
    if (!an || p.x < this.gutter() || p.y < L.rollTop || p.y > L.rollBot) return;
    const hit = this.noteAt(p.x, p.y);
    if (hit) { this.select(hit); return; }
    const midi = L.lo + Math.floor((L.rollBot - p.y) / L.rowH);
    const half = this.a.s.beatFrames() / 2;
    const at = Math.round(Math.round((this.left + (p.x - this.gutter()) * this.fpp) / half) * half);
    const n = { start: Math.max(0, at), len: Math.round(half), midi, cents: 0 };
    an.notes.push(n);
    refreshAfterEdit(an);
    this.a.analysisEdited(this.t);
    this.select(n);
  }

  suggestions(an) {
    if (!an || an.notes.length === 0) return [];
    const s = this.a.s, { root, minor } = keyOf(this.a, an);
    const k = `${root}|${minor}|${s.beatsPerBar}|${s.bpm}|${an.notes.length}`;
    if (an !== this.suggFor || k !== this.suggKey) {
      this.suggFor = an; this.suggKey = k;
      this.suggCache = harmonize(an.notes, root, minor, s.beatFrames(), Math.max(1, s.beatsPerBar));
    }
    return this.suggCache;
  }

  draw(c, W, H) {
    const a = this.a, s = a.s, t = this.t, an = t.analysis, gx = this.gutter(), cw = W - gx;
    if (this.fpp <= 0) this.fpp = this.clampFpp(Number.MAX_VALUE);
    const ph = a.playhead();
    if (a.eng.isPlaying() && !this.userMoved) {
      const x = (ph - this.left) / this.fpp;
      if (x > cw * 0.85 || x < 0) this.left = this.clampLeft(ph - cw * 0.15 * this.fpp);
    }
    const fpp = this.fpp, left = this.left;
    rect(c, 0, 0, W, H, C.LANE);
    const sugg = this.suggestions(an);
    const showSugg = !!an && an.chords.length === 0 && sugg.length > 0;
    const rulerH = 22, chordH = an ? 30 : 0, waveH = 64;
    const rollTop = rulerH + chordH, rollBot = H - waveH;
    let lo = 48, hi = 72;
    if (an && an.low > 0) { lo = an.low - 2; hi = an.high + 2; }
    if (hi - lo < 12) { const m = Math.floor((lo + hi) / 2); lo = m - 6; hi = m + 6; }
    const rows = hi - lo + 1, rowH = (rollBot - rollTop) / rows;
    const bar = s.barFrames(), beat = s.beatFrames();
    this.L = { rulerH, chordH, rollTop, rollBot, rowH, lo, bar, showSugg };
    const { root, minor } = keyOf(a, an);

    for (let m = lo; m <= hi; m++) {
      const y = rollBot - (m - lo + 1) * rowH;
      const ik = root >= 0 && inKey(m, root, minor);
      rect(c, gx, y, W, y + rowH, ik ? argb(0xFF1E1B18) : argb(0xFF15120F));
      rect(c, gx, y + rowH - 1, W, y + rowH, floorMod(m, 12) === 0 ? argb(0xFF3A352E) : argb(0xFF1A1714));
      if (rowH >= 11 || floorMod(m, 12) === 0) {
        const size = Math.min(11, rowH * 0.8 + 3);
        c.font = floorMod(m, 12) === 0 ? F.sansBold(size) : F.sans(size);
        c.textAlign = 'right';
        c.fillStyle = ik ? C.CREAM : C.FAINT;
        c.fillText(noteName(m), gx - 5, y + rowH * 0.5 + size * 0.35);
      }
    }

    let every = 1;
    while (bar * every / fpp < 34) every *= 2;
    const firstBar = Math.floor(left / bar);
    c.textAlign = 'left';
    c.font = F.sansBold(9.5);
    if (beat / fpp > 14) {
      c.fillStyle = argb(0xFF221E1A);
      for (let f = Math.floor(left / beat) * beat; f < left + cw * fpp; f += beat) {
        const x = gx + (f - left) / fpp;
        if (x >= gx) c.fillRect(x, rulerH, 1, rollBot - rulerH);
      }
    }
    for (let b = firstBar; b * bar < left + cw * fpp; b++) {
      const x = gx + (b * bar - left) / fpp;
      if (x < gx) continue;
      rect(c, x, rulerH, x + 1, H, argb(0xFF3A352E));
      if (b % every === 0) { c.fillStyle = C.DIM; c.fillText(String(b + 1), x + 3, rulerH - 7); }
    }
    c.font = F.sansBold(9);
    for (const mk of s.sortedMarkers()) {
      const x = gx + (mk.bar * bar - left) / fpp;
      if (x < gx || x > W) continue;
      rect(c, x, 0, x + 1.5, rulerH, C.AMBER);
      c.fillStyle = C.AMBER;
      c.fillText(mk.name, x + 4, 9);
    }

    if (showSugg) {
      rect(c, gx, rulerH, W, rulerH + chordH, argb(0xFF1F1C19));
      c.font = F.sansBold(11);
      for (const g of sugg) {
        const x1 = gx + (g.bar * bar - left) / fpp, x2 = gx + ((g.bar + 1) * bar - left) / fpp;
        if (x2 < gx || x1 > W) continue;
        const rl = Math.max(gx, x1) + 2, rr = x2 - 2, rt = rulerH + 4, rb = rulerH + chordH - 4;
        if (rr - rl < 6) continue;
        fillRoundRect(c, rl, rt, rr, rb, 3, argb(0xFF2A2621));
        c.fillStyle = argb(0xFFB7A57F);
        const both = g.best + ' / ' + g.alt;
        const label = g.alt && c.measureText(both).width < rr - rl - 6 ? both : g.best;
        if (c.measureText(label).width < rr - rl - 6) c.fillText(label, rl + 5, (rt + rb) / 2 + 4);
      }
      c.font = F.sansBold(7.5);
      c.fillStyle = C.FAINT;
      c.fillText('SUGGESTED', 4, rulerH + chordH / 2 + 3);
    }

    if (chordH > 0 && !showSugg) {
      rect(c, gx, rulerH, W, rulerH + chordH, argb(0xFF1F1C19));
      if (an.chords.length === 0) {
        c.font = F.sansBold(7.5);
        c.fillStyle = C.FAINT;
        c.fillText('TAP A BAR HERE TO ADD A CHORD', gx + 6, rulerH + chordH / 2 + 3);
      }
      c.font = F.sansBold(12);
      for (const ch of an.chords) {
        const x1 = gx + (ch.bar * bar - left) / fpp, x2 = gx + ((ch.bar + 1) * bar - left) / fpp;
        if (x2 < gx || x1 > W) continue;
        const rl = Math.max(gx, x1) + 2, rr = x2 - 2, rt = rulerH + 4, rb = rulerH + chordH - 4;
        if (rr - rl < 6) continue;
        fillRoundRect(c, rl, rt, rr, rb, 3, argb(0xFF34302A));
        c.fillStyle = C.AMBER;
        if (c.measureText(ch.name).width < rr - rl - 6) c.fillText(ch.name, rl + 5, (rt + rb) / 2 + 4.5);
      }
    }

    if (an) {
      const size = Math.max(8, Math.min(11, rowH * 0.8));
      c.font = F.sansBold(size);
      for (const n of an.notes) {
        const x1 = gx + (n.start - left) / fpp, x2 = gx + (n.start + n.len - left) / fpp;
        if (x2 < gx || x1 > W) continue;
        const y = rollBot - (n.midi - lo + 1) * rowH;
        const out = root >= 0 && !inKey(n.midi, root, minor), sel = n === this.selected;
        const rl = Math.max(gx, x1), rr = Math.max(rl + 3, x2 - 1);
        fillRoundRect(c, rl, y + 1, rr, y + rowH - 1, 2, sel ? C.CREAM : out ? C.RED : C.AMBER);
        if (sel) rect(c, rl, y + rowH, rr, y + rowH + 2, C.AMBER);
        const name = noteName(n.midi);
        if (rr - rl > c.measureText(name).width + 6 && rowH >= 9) {
          c.fillStyle = C.INK;
          c.fillText(name, rl + 3, y + rowH / 2 + size * 0.35);
        }
      }
    } else {
      c.font = F.serifItalic(16);
      c.textAlign = 'center';
      c.fillStyle = C.DIM;
      c.fillText('analyze this track to see its notes and chords', gx + cw / 2, (rollTop + rollBot) / 2);
      c.textAlign = 'left';
    }

    rect(c, gx, rollBot, W, H, argb(0xFF0E0C0A));
    const mid = rollBot + waveH / 2, amp = waveH / 2 - 4, pk = t.peaks;
    c.fillStyle = a.isAudible(t) ? argb(0xCCE9A23B) : argb(0xFF3A3630);
    for (let xi = 0; xi < cw; xi++) {
      const f0 = left + xi * fpp, i0 = Math.floor(f0 / PB), i1 = Math.max(i0 + 1, Math.floor((f0 + fpp) / PB));
      if (i0 >= pk.length) break;
      let m = 0;
      for (let i = i0; i < Math.min(i1, pk.length); i++) if (pk[i] > m) m = pk[i];
      const hh = Math.max(0.5, m * amp);
      c.fillRect(gx + xi, mid - hh, 1, hh * 2);
    }

    rect(c, 0, rulerH, gx - 1, H, argb(0xFF1B1A18));
    rect(c, 0, rulerH - 1, W, rulerH, argb(0xFF2B2824));
    c.font = F.sansBold(8);
    c.fillStyle = C.FAINT;
    c.fillText('BAR', 6, rulerH - 7);
    const px = gx + (ph - left) / fpp;
    if (px >= gx && px <= W) rect(c, px - 0.75, 0, px + 0.75, H, C.CREAM);
  }
}

export const MAX_ROWS = 16;

/** Note usage histogram and phrases (repeats folded). Tapping a phrase opens it in the track view. */
export class MelodyView extends CanvasView {
  constructor(f, root, minor, beatFrames, beatsPerBar) {
    super('melody');
    this.f = f; this.root = root; this.minor = minor; this.onPhrase = null;
    this.use = new Float64Array(12);
    for (const n of f.notes) this.use[floorMod(n.midi, 12)] += n.len;
    const gap = Math.max(beatFrames, 0.35 * SR), bar = beatFrames * Math.max(1, beatsPerBar);
    const all = [];
    let cur = null, lastEnd = -Infinity;
    for (const n of f.notes) {
      if (!cur || n.start - lastEnd > gap || cur.notes.length >= 24) { cur = { notes: [], bars: [], start: n.start }; all.push(cur); }
      cur.notes.push(n);
      lastEnd = n.start + n.len;
    }
    this.totalPhrases = all.length;
    this.phrases = [];
    for (const ph of all) {
      const b = Math.floor(ph.start / bar) + 1, k = ph.notes.map(n => n.midi).join(',');
      const same = this.phrases.find(q => q.key === k);
      if (same) same.bars.push(b);
      else { ph.key = k; ph.bars.push(b); this.phrases.push(ph); }
    }
    const histH = 92, rowH = 52;
    this.el.style.height = (histH + 14 + this.rows() * rowH) + 'px';
    this.el.addEventListener('click', e => {
      const y = this.at(e).y - (histH + 14), i = Math.floor(y / rowH);
      if (y >= 0 && i < this.rows() && this.onPhrase) this.onPhrase(this.phrases[i].start);
    });
  }
  rows() { return Math.min(MAX_ROWS, this.phrases.length); }
  draw(c, w) {
    const histH = 92, rowH = 52, { root, minor, use } = this;
    let max = 0;
    for (const v of use) max = Math.max(max, v);
    const start = root >= 0 ? root : 0, colW = w / 12, baseY = histH - 18, top = 14;
    c.font = F.sansBold(8);
    c.fillStyle = C.FAINT;
    c.textAlign = 'left';
    c.fillText('HOW MUCH EACH NOTE IS USED', 0, 9);
    c.textAlign = 'center';
    for (let i = 0; i < 12; i++) {
      const pc = (start + i) % 12, ik = root < 0 || inKey(pc, root, minor);
      const hgt = max > 0 ? use[pc] / max * (baseY - top) : 0;
      fillRoundRect(c, i * colW + 3, baseY - Math.max(2, hgt), (i + 1) * colW - 3, baseY, 2, use[pc] === 0 ? argb(0xFF24211D) : ik ? C.AMBER : C.RED);
      c.font = F.sansBold(10);
      c.fillStyle = use[pc] === 0 ? C.FAINT : ik ? C.CREAM : C.RED;
      c.fillText(NOTES[pc], i * colW + colW / 2, baseY + 13);
    }
    const y0 = histH + 14;
    c.textAlign = 'left';
    c.font = F.sansBold(8);
    c.fillStyle = C.FAINT;
    c.fillText('PHRASES  (TAP ONE TO SEE IT IN THE TRACK VIEW)', 0, y0 - 4);
    const labelW = 70;
    for (let i = 0; i < this.rows(); i++) {
      const ph = this.phrases[i], y = y0 + i * rowH;
      rect(c, 0, y, w, y + rowH - 3, i % 2 === 0 ? argb(0xFF1F1C19) : argb(0xFF1A1815));
      c.font = F.sansBold(11);
      c.fillStyle = C.CREAM;
      c.fillText('bar ' + ph.bars[0], 6, y + 17);
      c.font = F.sans(9.5);
      c.fillStyle = ph.bars.length > 1 ? C.AMBER : C.DIM;
      c.fillText(ph.bars.length > 1 ? '×' + ph.bars.length : ph.notes.length + ' notes', 6, y + 31);
      const rx = labelW, rw = w - labelW - 6, ry = y + 5, rh = rowH - 22;
      const t0 = ph.notes[0].start, last = ph.notes[ph.notes.length - 1];
      const span = Math.max(1, last.start + last.len - t0);
      let lo = 999, hi = -999;
      for (const n of ph.notes) { lo = Math.min(lo, n.midi); hi = Math.max(hi, n.midi); }
      const range = Math.max(4, hi - lo);
      const names = [];
      for (const n of ph.notes) {
        const x1 = rx + (n.start - t0) / span * rw, x2 = rx + (n.start + n.len - t0) / span * rw;
        const ny = ry + rh - (n.midi - lo) / range * (rh - 4) - 4;
        const out = root >= 0 && !inKey(n.midi, root, minor);
        fillRoundRect(c, x1, ny, Math.max(x1 + 3, x2 - 1.5), ny + 4, 1.5, out ? C.RED : C.AMBER);
        names.push(NOTES[floorMod(n.midi, 12)]);
      }
      c.font = F.sans(9.5);
      c.fillStyle = C.DIM;
      const full = names.join(' ');
      let txt = full;
      while (txt.length > 1 && c.measureText(txt).width > rw) txt = txt.slice(0, -2);
      if (txt.length < full.length) txt = txt.trim() + ' …';
      c.fillText(txt, rx, y + rowH - 8);
    }
  }
}
