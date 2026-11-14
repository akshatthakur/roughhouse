// Palette, fonts and canvas helpers for the cassette-deck look. Ported from Ui.java.

/** 0xAARRGGBB -> CSS colour */
export function argb(v) {
  const a = ((v >>> 24) & 0xFF) / 255, r = (v >>> 16) & 0xFF, g = (v >>> 8) & 0xFF, b = v & 0xFF;
  return a >= 1 ? `rgb(${r},${g},${b})` : `rgba(${r},${g},${b},${a.toFixed(3)})`;
}

export const C = {
  BODY: argb(0xFF1B1A18), PANEL: argb(0xFF262522), PANEL_EDGE: argb(0xFF3A3833), LANE: argb(0xFF12100E),
  CREAM: argb(0xFFE8DFC8), DIM: argb(0xFF9C9584), FAINT: argb(0xFF5E5A51),
  AMBER: argb(0xFFE9A23B), RED: argb(0xFFD8452E), GREEN: argb(0xFF7FA65A), BLUE: argb(0xFF5B8DB8), YELLOW: argb(0xFFE5C547),
  KEY: argb(0xFFD9D2C0), KEY_DOWN: argb(0xFFB5AD98), INK: argb(0xFF2A2420), PAPER: argb(0xFFF1E8D0), SHELL: argb(0xFFCFC4AA),
  TAPE: argb(0xFF4A3222),
};

/** Channel cap colours, like the coloured knobs on a Portastudio. */
export const CAPS = [C.RED, C.AMBER, C.GREEN, C.BLUE];

export const F = {
  sans: (px) => `${px}px Heros, "Helvetica Neue", Helvetica, Arial, sans-serif`,
  sansBold: (px) => `bold ${px}px Heros, "Helvetica Neue", Helvetica, Arial, sans-serif`,
  serif: (px) => `${px}px Termes, "Times New Roman", Times, serif`,
  serifItalic: (px) => `italic ${px}px Termes, "Times New Roman", Times, serif`,
  serifBold: (px) => `bold ${px}px Termes, "Times New Roman", Times, serif`,
};

/** Text with letter spacing in em, like Android's setLetterSpacing. align: left | center | right. */
export function spaced(c, s, x, y, em, align = 'left') {
  const px = parseFloat(/(\d+(\.\d+)?)px/.exec(c.font)[1]);
  const gap = em * px;
  let w = 0;
  const ws = [];
  for (const ch of s) { const cw = c.measureText(ch).width; ws.push(cw); w += cw + gap; }
  w -= gap;
  let cx = align === 'center' ? x - w / 2 : align === 'right' ? x - w : x;
  const saved = c.textAlign;
  c.textAlign = 'left';
  let i = 0;
  for (const ch of s) { c.fillText(ch, cx, y); cx += ws[i++] + gap; }
  c.textAlign = saved;
  return w;
}

export function spacedWidth(c, s, em) {
  const px = parseFloat(/(\d+(\.\d+)?)px/.exec(c.font)[1]);
  let w = 0;
  for (const ch of s) w += c.measureText(ch).width + em * px;
  return s.length ? w - em * px : 0;
}

export function roundRect(c, x, y, w, h, r) {
  r = Math.max(0, Math.min(r, w / 2, h / 2));
  c.beginPath();
  c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r);
  c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r);
  c.arcTo(x, y, x + w, y, r);
  c.closePath();
}

export function fillRoundRect(c, x1, y1, x2, y2, r, color) {
  c.fillStyle = color;
  roundRect(c, x1, y1, x2 - x1, y2 - y1, r);
  c.fill();
}

export function circle(c, x, y, r, color) {
  c.fillStyle = color;
  c.beginPath();
  c.arc(x, y, Math.max(0, r), 0, Math.PI * 2);
  c.fill();
}

export function rect(c, x1, y1, x2, y2, color) {
  c.fillStyle = color;
  c.fillRect(x1, y1, x2 - x1, y2 - y1);
}

export function ellipsize(c, s, max) {
  if (c.measureText(s).width <= max) return s;
  while (s.length > 1 && c.measureText(s + '…').width > max) s = s.substring(0, s.length - 1);
  return s + '…';
}

/** A canvas that keeps itself sized to its box at device resolution and redraws on demand. */
export class CanvasView {
  constructor(cls) {
    this.el = document.createElement('canvas');
    if (cls) this.el.className = cls;
    this.w = 0; this.h = 0;
    this.pending = false;
    this.ro = new ResizeObserver(() => this.invalidate());
    this.ro.observe(this.el);
  }
  invalidate() {
    if (this.pending) return;
    this.pending = true;
    requestAnimationFrame(() => { this.pending = false; this.paint(); });
  }
  paint() {
    const r = this.el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return;
    const dpr = window.devicePixelRatio || 1;
    const W = Math.round(r.width * dpr), H = Math.round(r.height * dpr);
    if (this.el.width !== W || this.el.height !== H) { this.el.width = W; this.el.height = H; }
    this.w = r.width; this.h = r.height;
    const c = this.el.getContext('2d');
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.textBaseline = 'alphabetic';
    c.clearRect(0, 0, r.width, r.height);
    this.draw(c, r.width, r.height);
  }
  draw() {}
  destroy() { this.ro.disconnect(); }
  /** Pointer position in CSS px relative to the canvas. */
  at(e) {
    const r = this.el.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }
}

/**
 * Tap / long-press / drag recognizer on an element. Handlers get CSS-px points.
 * Right-click counts as a long-press so desktop users have a quick way in.
 */
export function gestures(el, h) {
  let down = null, timer = 0, longFired = false, dragging = false;
  const pt = e => { const r = el.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };
  el.addEventListener('contextmenu', e => {
    e.preventDefault();
    if (h.long && e.pointerType !== 'touch' && !longFired) h.long(pt(e));
  });
  el.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    down = { ...pt(e), id: e.pointerId, t: performance.now() };
    longFired = false;
    dragging = false;
    if (h.down) h.down(pt(e), e);
    clearTimeout(timer);
    if (h.long) timer = setTimeout(() => { if (down && !dragging) { longFired = true; h.long(down); } }, 500);
  });
  el.addEventListener('pointermove', e => {
    if (!down || e.pointerId !== down.id) return;
    const p = pt(e);
    if (!dragging && Math.hypot(p.x - down.x, p.y - down.y) > 8) {
      dragging = true;
      clearTimeout(timer);
      if (h.drag) { try { el.setPointerCapture(e.pointerId); } catch { /* fine */ } }
    }
    if (dragging && h.drag) h.drag(p, down, e);
  });
  const up = e => {
    if (!down || e.pointerId !== down.id) return;
    clearTimeout(timer);
    const p = pt(e);
    if (!longFired && !dragging && h.tap) h.tap(p, e);
    if (h.up) h.up(p, e);
    down = null;
  };
  el.addEventListener('pointerup', up);
  el.addEventListener('pointercancel', e => { clearTimeout(timer); down = null; });
}
