// Custom-drawn deck hardware: cassette window, tape counter, VU meter, tuner, transport keys
// and the circle of fifths. Ported from Deck.java.
import { C, F, CanvasView, spaced, circle, rect, fillRoundRect, ellipsize, argb } from './ui.js';
import { midi as hzToMidi, noteName } from './pitch.js';

const u = 1;

export class Cassette extends CanvasView {
  constructor() {
    super('cassette');
    this.title = ''; this.sub = ''; this.angle = 0; this.frac = 0; this.recording = false;
  }
  draw(c, w, h) {
    fillRoundRect(c, 0, 0, w, h, 8, argb(0xFF0F0D0C));
    const sx = 10, sy = 8, sw = w - 20, sh = h - 16;
    fillRoundRect(c, sx, sy, sx + sw, sy + sh, 6, C.SHELL);
    const sc = 5, scr = argb(0xFF9E947C);
    circle(c, sx + sc + 2, sy + sc + 2, 2.2, scr);
    circle(c, sx + sw - sc - 2, sy + sc + 2, 2.2, scr);
    circle(c, sx + sc + 2, sy + sh - sc - 2, 2.2, scr);
    circle(c, sx + sw - sc - 2, sy + sh - sc - 2, 2.2, scr);
    const lx = sx + 14, ly = sy + 8, lw = sw - 28, lh = sh * 0.72;
    fillRoundRect(c, lx, ly, lx + lw, ly + lh, 3, C.PAPER);
    c.fillStyle = C.INK;
    c.font = F.serifItalic(16);
    c.textAlign = 'left';
    const titleBase = ly + 20;
    c.fillText(ellipsize(c, this.title, lw - 16), lx + 8, titleBase);
    const stripe = titleBase + 7;
    rect(c, lx, stripe, lx + lw, stripe + 3, C.RED);
    rect(c, lx, stripe + 3, lx + lw, stripe + 5, C.AMBER);
    c.font = F.sansBold(8.5);
    c.fillStyle = argb(0xFF6B6255);
    const subBase = ly + lh - 6;
    let sub = this.sub;
    while (sub.length > 1 && measureSpaced(c, sub, 0.12) > lw - 16) sub = sub.substring(0, sub.length - 2) + '…';
    spaced(c, sub, lx + 8, subBase, 0.12);

    const wy = stripe + 5 + 6, wh = Math.max(10, (subBase - 8.5 - 6) - wy);
    const wx = lx + lw * 0.17, ww = lw * 0.66;
    fillRoundRect(c, wx, wy, wx + ww, wy + wh, wh / 2, argb(0xFF15110F));
    const cy = wy + wh / 2, rx1 = wx + wh / 2 + 4, rx2 = wx + ww - wh / 2 - 4;
    const maxR = wh / 2 - 2, minR = maxR * 0.42;
    circle(c, rx1, cy, minR + (maxR - minR) * (1 - this.frac), C.TAPE);
    circle(c, rx2, cy, minR + (maxR - minR) * this.frac, C.TAPE);
    this.reel(c, rx1, cy, minR * 0.85, this.angle);
    this.reel(c, rx2, cy, minR * 0.85, this.angle);
  }
  reel(c, x, y, rad, a) {
    circle(c, x, y, rad, C.CREAM);
    circle(c, x, y, rad * 0.45, argb(0xFF15110F));
    for (let i = 0; i < 6; i++) {
      const th = a + i * Math.PI / 3;
      circle(c, x + Math.cos(th) * rad * 0.42, y + Math.sin(th) * rad * 0.42, rad * 0.11, C.CREAM);
    }
  }
}

function measureSpaced(c, s, em) {
  const px = parseFloat(/(\d+(\.\d+)?)px/.exec(c.font)[1]);
  return c.measureText(s).width + Math.max(0, s.length - 1) * em * px;
}

/** Mechanical-style counter: one box per digit. Shows mm:ss.t or bar.beat. */
export class Counter extends CanvasView {
  constructor() { super('counter'); this.text = '00:00.0'; this.caption = 'TIME'; this.red = false; }
  draw(c, w, h) {
    const boxH = h - 14, boxW = boxH * 0.62;
    let x = 0;
    c.font = F.sansBold(boxH * 0.68);
    c.textAlign = 'center';
    for (const ch of this.text) {
      if (ch === ':' || ch === '.') {
        c.fillStyle = C.DIM;
        c.fillText(ch, x + 4, boxH * 0.72);
        x += 8;
        continue;
      }
      fillRoundRect(c, x, 0, x + boxW, boxH, 2, argb(0xFF0B0A09));
      rect(c, x, boxH / 2 - 0.5, x + boxW, boxH / 2 + 0.5, argb(0xFF2B2824));
      c.fillStyle = this.red ? C.RED : argb(0xFFF4EEDF);
      c.fillText(ch, x + boxW / 2, boxH * 0.74);
      x += boxW + 2;
    }
    c.font = F.sansBold(8.5);
    c.fillStyle = C.DIM;
    spaced(c, this.caption, 0, h - 2, 0.15);
  }
}

/** Backlit needle meter with VU-like ballistics. */
export class Vu extends CanvasView {
  constructor(label) { super('vu'); this.label = label; this.needle = 0; }
  /** Returns true while the needle is still moving. */
  step(peak) {
    const db = peak > 1e-5 ? 20 * Math.log10(peak) : -60;
    const target = Math.max(0, Math.min(1, (db + 20) / 23));
    const k = target > this.needle ? 0.45 : 0.12;
    const prev = this.needle;
    this.needle += (target - this.needle) * k;
    if (Math.abs(this.needle - prev) > 0.002) { this.invalidate(); return true; }
    return false;
  }
  draw(c, w, h) {
    fillRoundRect(c, 0, 0, w, h, 4, argb(0xFF0F0D0C));
    fillRoundRect(c, 3, 3, w - 3, h - 3, 3, argb(0xFFF0DFAE));
    const cx = w / 2, cy = h * 1.05, rad = h * 0.78;
    c.lineWidth = 1.2;
    const a0 = -140 * Math.PI / 180, a1 = -40 * Math.PI / 180, aRed = a0 + (a1 - a0) * (20 / 23);
    for (let i = 0; i <= 23; i++) {
      const a = a0 + (a1 - a0) * i / 23;
      const major = i % 5 === 0 || i === 20 || i === 23;
      const r1 = rad - (major ? 7 : 4);
      c.strokeStyle = a >= aRed ? C.RED : C.INK;
      c.beginPath();
      c.moveTo(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1);
      c.lineTo(cx + Math.cos(a) * rad, cy + Math.sin(a) * rad);
      c.stroke();
    }
    c.font = F.serifItalic(11);
    c.textAlign = 'center';
    c.fillStyle = C.INK;
    c.fillText('VU', cx, h * 0.62);
    c.font = F.sansBold(8);
    c.fillText(this.label, w - 10, h - 7);
    const a = a0 + (a1 - a0) * this.needle;
    c.lineWidth = 1.4;
    c.strokeStyle = argb(0xFF1A1715);
    c.beginPath();
    c.moveTo(cx, cy);
    c.lineTo(cx + Math.cos(a) * (rad + 2), cy + Math.sin(a) * (rad + 2));
    c.stroke();
  }
}

/** Live pitch: note name, cents needle (-50..+50) with an in-tune zone, frequency. */
export class Tuner extends CanvasView {
  constructor() { super('tuner'); this.hz = -1; this.cents = 0; this.shown = 0; }
  set(f) {
    this.hz = f;
    if (f > 0) {
      const m = hzToMidi(f);
      this.cents = (m - Math.round(m)) * 100;
      this.shown += (this.cents - this.shown) * 0.5;
    }
    this.invalidate();
  }
  draw(c, w, h) {
    fillRoundRect(c, 0, 0, w, h, 4, argb(0xFF0F0D0C));
    const voiced = this.hz > 0, inTune = voiced && Math.abs(this.cents) <= 10;
    c.font = F.serifBold(h * 0.48);
    c.textAlign = 'left';
    c.fillStyle = !voiced ? C.FAINT : inTune ? C.GREEN : C.AMBER;
    c.fillText(voiced ? noteName(Math.round(hzToMidi(this.hz))) : '–', 10, h * 0.66);
    const sx = w * 0.42, ex = w - 12, my = h * 0.42;
    rect(c, sx, my - 1, ex, my + 1, argb(0xFF2F2B26));
    const zone = (ex - sx) * 0.1, mid = (sx + ex) / 2;
    rect(c, mid - zone, my - 7, mid + zone, my + 7, argb(0x447FA65A));
    for (let i = -5; i <= 5; i++) {
      const x = mid + (ex - sx) / 2 * i / 5, th = i === 0 ? 8 : 4;
      rect(c, x - 0.5, my - th, x + 0.5, my + th, C.DIM);
    }
    if (voiced) {
      const x = mid + (ex - sx) / 2 * Math.max(-1, Math.min(1, this.shown / 50));
      rect(c, x - 1.5, my - 11, x + 1.5, my + 11, inTune ? C.GREEN : C.AMBER);
    }
    c.font = F.sansBold(9);
    c.fillStyle = C.DIM;
    const sign = this.cents >= 0 ? '+' : '';
    const info = voiced ? `${sign}${Math.round(this.cents)} CENTS  ·  ${this.hz.toFixed(1)} HZ` : 'SING OR PLAY A NOTE';
    spaced(c, info, sx, h - 9, 0.12);
  }
}

/** Piano-style transport key with an icon and an optional lit LED. */
export class Key extends CanvasView {
  static REW = 0; static REC = 1; static PLAY = 2; static STOP = 3;
  constructor(type, label) {
    super('key');
    this.type = type; this.down = false; this.lit = false; this.level = 0; this.pressed = false;
    this.el.setAttribute('role', 'button');
    this.el.setAttribute('aria-label', label);
    this.el.tabIndex = 0;
    this.el.addEventListener('pointerdown', () => { this.pressed = true; this.invalidate(); });
    for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) this.el.addEventListener(ev, () => { if (this.pressed) { this.pressed = false; this.invalidate(); } });
  }
  set(down, lit) {
    if (this.down !== down || this.lit !== lit) { this.down = down; this.lit = lit; this.invalidate(); }
  }
  draw(c, w, h) {
    const pressed = this.down || this.pressed, drop = pressed ? 2 : 0;
    fillRoundRect(c, 0, 4, w, h, 4, argb(0xFF0B0A09));
    fillRoundRect(c, 0, drop, w, h - 4 + drop, 4, pressed ? C.KEY_DOWN : C.KEY);
    rect(c, 2, h - 10 + drop, w - 2, h - 5 + drop, argb(0x22000000));
    const lx = w - 9, ly = 9 + drop;
    circle(c, lx, ly, 2.6, this.lit ? (this.type === Key.REC ? C.RED : C.GREEN) : argb(0xFF6E685C));
    if (this.lit && this.type === Key.REC && this.level > 0) circle(c, lx, ly, 2.6 + 4 * Math.min(1, this.level * 2), argb(0x55D8452E));
    const labelBase = h - 10 + drop, labelTop = labelBase - 8.5;
    const s = Math.min(Math.min(w, h) * 0.15, (labelTop - 6 - (3 + drop)) / 2.4);
    const cx = w / 2, cy = 3 + drop + (labelTop - 6 - 3 - drop) / 2 + 2;
    c.fillStyle = this.type === Key.REC ? C.RED : C.INK;
    c.beginPath();
    switch (this.type) {
      case Key.REW:
        c.fillRect(cx - s * 1.1, cy - s, s * 0.35, 2 * s);
        c.moveTo(cx + s, cy - s); c.lineTo(cx - s * 0.6, cy); c.lineTo(cx + s, cy + s); c.closePath(); c.fill();
        break;
      case Key.REC:
        circle(c, cx, cy, s * 0.95, C.RED);
        break;
      case Key.PLAY:
        c.moveTo(cx - s * 0.7, cy - s); c.lineTo(cx + s, cy); c.lineTo(cx - s * 0.7, cy + s); c.closePath(); c.fill();
        break;
      default:
        c.fillRect(cx - s * 0.85, cy - s * 0.85, s * 1.7, s * 1.7);
    }
    c.font = F.sansBold(8.5);
    c.fillStyle = argb(0xFF5A5347);
    spaced(c, ['REW', 'REC', 'PLAY', 'STOP'][this.type], cx, labelBase, 0.15, 'center');
  }
}

/**
 * Circle of fifths: majors on the outer ring, relative minors inside. The selected key is lit
 * amber; its closest relatives are lit cream.
 */
export class Fifths extends CanvasView {
  static MAJ = ['C', 'G', 'D', 'A', 'E', 'B', 'F#', 'Db', 'Ab', 'Eb', 'Bb', 'F'];
  static MIN = ['Am', 'Em', 'Bm', 'F#m', 'C#m', 'G#m', 'Ebm', 'Bbm', 'Fm', 'Cm', 'Gm', 'Dm'];
  static rootAt(pos, minor) { const major = (pos * 7) % 12; return minor ? (major + 9) % 12 : major; }
  static posOf(root, minor) { const major = minor ? (root + 3) % 12 : root; return (major * 7) % 12; }

  constructor() {
    super('fifths');
    this.pos = 0; this.minor = false; this.onPick = null;
    this.el.addEventListener('click', e => {
      const { x, y } = this.at(e), w = this.w, cx = w / 2, cy = w / 2;
      const dx = x - cx, dy = y - cy, r = Math.hypot(dx, dy);
      const rOut = w / 2 - 2, rMid = rOut * 0.68, rIn = rOut * 0.40;
      if (r < rIn || r > rOut) return;
      const ang = Math.atan2(dx, -dy) * 180 / Math.PI;
      this.pos = ((Math.round(ang / 30) % 12) + 12) % 12;
      this.minor = r < rMid;
      this.invalidate();
      if (this.onPick) this.onPick(Fifths.rootAt(this.pos, this.minor), this.minor);
    });
  }
  family(i) { const d = (((i - this.pos) % 12) + 12) % 12; return d === 0 || d === 1 || d === 11; }
  draw(c, w) {
    const cx = w / 2, cy = w / 2, rOut = w / 2 - 2, rMid = rOut * 0.68, rIn = rOut * 0.40;
    for (let i = 0; i < 12; i++) {
      const start = (-90 - 15 + i * 30) * Math.PI / 180, end = start + Math.PI / 6;
      for (let ring = 0; ring < 2; ring++) {
        const ringMinor = ring === 1;
        const ro = ringMinor ? rMid : rOut, ri = ringMinor ? rIn : rMid;
        const sel = i === this.pos && ringMinor === this.minor, fam = this.family(i);
        c.beginPath();
        c.arc(cx, cy, ro, start, end);
        c.arc(cx, cy, ri, end, start, true);
        c.closePath();
        c.fillStyle = sel ? C.AMBER : fam ? (ringMinor ? argb(0xFFCFC4AA) : C.CREAM) : (ringMinor ? argb(0xFF201E1B) : argb(0xFF2B2926));
        c.fill();
        c.lineWidth = 1.2;
        c.strokeStyle = C.BODY;
        c.stroke();
        const a = (-90 + i * 30) * Math.PI / 180, rr = (ro + ri) / 2;
        const size = ringMinor ? 11 : 15;
        c.font = ringMinor ? F.sans(size) : F.sansBold(size);
        c.textAlign = 'center';
        c.fillStyle = sel || fam ? C.INK : (ringMinor ? C.DIM : C.CREAM);
        c.fillText(ringMinor ? Fifths.MIN[i] : Fifths.MAJ[i], cx + Math.cos(a) * rr, cy + Math.sin(a) * rr + size * 0.35);
      }
    }
    circle(c, cx, cy, rIn - 2, argb(0xFF0F0D0C));
    circle(c, cx, cy, rIn * 0.22, C.RED);
    c.font = F.serifItalic(13);
    c.fillStyle = C.CREAM;
    c.textAlign = 'center';
    const name = this.minor ? Fifths.MIN[this.pos] : Fifths.MAJ[this.pos];
    c.fillText(name.endsWith('m') ? name.slice(0, -1) + ' minor' : name + ' major', cx, cy - rIn * 0.42);
    c.font = F.sansBold(7.5);
    c.fillStyle = C.DIM;
    spaced(c, 'TAP A KEY', cx, cy + rIn * 0.55, 0.15, 'center');
  }
}
