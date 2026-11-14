// Deck-styled dialogs, lists, prompts and form pieces (the web stand-in for AlertDialog).
import { gestures } from './ui.js';

const stack = [];
let historyArmed = false;

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}
export { el };

// the phone's back gesture closes the top dialog instead of leaving the page
window.addEventListener('popstate', () => {
  historyArmed = false;
  const top = stack[stack.length - 1];
  if (top && top.cancelable) top.close();
  if (stack.length) arm();
});
function arm() {
  if (!historyArmed) { history.pushState({ rhDialog: true }, ''); historyArmed = true; }
}

export const anyOpen = () => stack.length > 0;

/**
 * opts: { title, body: Node, buttons: [{label, fn, keep}], full, cancelable=true, onDismiss, cls }
 * Buttons close the dialog unless keep is true. Returns { el, close }.
 */
export function dialog(opts) {
  const scrim = el('div', 'scrim' + (opts.full ? ' full' : ''));
  const box = el('div', 'dlg' + (opts.cls ? ' ' + opts.cls : ''));
  box.setAttribute('role', 'dialog');
  box.setAttribute('aria-modal', 'true');
  if (opts.title) {
    const t = el('div', 'dlg-title', opts.title);
    box.appendChild(t);
    box.setAttribute('aria-label', opts.title);
  }
  const content = el('div', 'dlg-body');
  if (opts.body) content.appendChild(opts.body);
  box.appendChild(content);
  const d = { el: box, body: content, cancelable: opts.cancelable !== false, closed: false };
  if (opts.buttons && opts.buttons.length) {
    const bar = el('div', 'dlg-buttons');
    for (const b of opts.buttons) {
      const btn = el('button', 'dlg-btn' + (b.left ? ' left' : ''), b.label);
      btn.addEventListener('click', () => { if (!b.keep) d.close(); if (b.fn) b.fn(); });
      bar.appendChild(btn);
    }
    box.appendChild(bar);
    d.buttons = bar;
  }
  scrim.appendChild(box);
  scrim.addEventListener('pointerdown', e => { if (e.target === scrim && d.cancelable) d.downOnScrim = true; });
  scrim.addEventListener('click', e => { if (e.target === scrim && d.downOnScrim && d.cancelable) d.close(); d.downOnScrim = false; });
  d.close = () => {
    if (d.closed) return;
    d.closed = true;
    scrim.remove();
    const i = stack.indexOf(d);
    if (i >= 0) stack.splice(i, 1);
    if (opts.onDismiss) opts.onDismiss();
  };
  d.setMessage = m => { const p = content.querySelector('.dlg-msg'); if (p) p.textContent = m; };
  document.body.appendChild(scrim);
  stack.push(d);
  arm();
  const focusable = box.querySelector('input, textarea, button, [tabindex]');
  if (focusable && !opts.noFocus) setTimeout(() => focusable.focus({ preventScroll: true }), 30);
  return d;
}

export function closeTop() {
  const top = stack[stack.length - 1];
  if (top && top.cancelable) { top.close(); return true; }
  return false;
}

export function message(text, buttons, opts = {}) {
  const p = el('div', 'dlg-msg', text);
  return dialog({ body: p, buttons, ...opts });
}

export function busy(text) {
  return message(text, [], { cancelable: false });
}

/** A list of choices; items may have a second line after '\n'. onLong(i) for long-press. */
export function list(title, items, onPick, onLong) {
  const ul = el('div', 'dlg-list');
  let d;
  items.forEach((it, i) => {
    const row = el('button', 'dlg-item');
    const [a, b] = String(it).split('\n');
    row.appendChild(el('span', 'dlg-item-main', a));
    if (b) row.appendChild(el('span', 'dlg-item-sub', b));
    if (onLong) {
      gestures(row, { tap: () => { d.close(); onPick(i); }, long: () => { d.close(); onLong(i); } });
    } else {
      row.addEventListener('click', () => { d.close(); onPick(i); });
    }
    ul.appendChild(row);
  });
  d = dialog({ title, body: ul, cls: 'listdlg' });
  return d;
}

export function prompt(title, value, cb) {
  const wrap = el('div', 'prompt');
  const input = el('input', 'prompt-input');
  input.type = 'text';
  input.value = value;
  input.autocapitalize = 'sentences';
  wrap.appendChild(input);
  const ok = () => { const v = input.value.trim(); if (v) cb(v); };
  const d = dialog({ title, body: wrap, buttons: [{ label: 'CANCEL' }, { label: 'OK', fn: ok }] });
  input.addEventListener('keydown', e => { if (e.key === 'Enter') { d.close(); ok(); } });
  setTimeout(() => { input.focus(); input.select(); }, 40);
  return d;
}

export function confirm(text, okLabel, fn) {
  return message(text, [{ label: 'CANCEL' }, { label: okLabel, fn }]);
}

// ---------------------------------------------------------------- body pieces

export function body() { return el('div', 'body'); }

export function para(parent, text, size = 14, color = 'cream') {
  const p = el('div', 'para ' + color, text);
  p.style.fontSize = size + 'px';
  parent.appendChild(p);
  return p;
}

export function heading(parent, text) {
  const h = el('div', 'heading', text);
  parent.appendChild(h);
  return h;
}

export function slider(parent, label, max, val, fmt, on) {
  const box = el('label', 'slider');
  const head = el('div', 'slider-head');
  head.appendChild(el('span', 'silk', label.toUpperCase()));
  const v = el('span', 'slider-val', fmt(val));
  head.appendChild(v);
  const r = el('input');
  r.type = 'range';
  r.min = 0;
  r.max = max;
  r.step = 1;
  r.value = val;
  const paint = () => r.style.setProperty('--fill', (max ? (r.value / max) * 100 : 0) + '%');
  paint();
  r.addEventListener('input', () => { const p = +r.value; v.textContent = fmt(p); paint(); on(p); });
  box.appendChild(head);
  box.appendChild(r);
  parent.appendChild(box);
  return r;
}

export function toggle(parent, label, checked, on) {
  const row = el('label', 'toggle');
  const input = el('input');
  input.type = 'checkbox';
  input.checked = checked;
  input.addEventListener('change', () => on(input.checked));
  row.appendChild(el('span', '', label));
  row.appendChild(input);
  parent.appendChild(row);
  return input;
}

export function chipRow(parent) {
  const r = el('div', 'chiprow');
  parent.appendChild(r);
  return r;
}

export function chip(label, fn, cls) {
  const c = el('button', 'chip' + (cls ? ' ' + cls : ''), label);
  if (fn) c.addEventListener('click', fn);
  return c;
}

export function addChip(row, label, fn) {
  const c = chip(label, fn);
  row.appendChild(c);
  return c;
}

/** Multi-choice list with checkboxes. Returns a getter for the checked flags. */
export function checks(parent, names) {
  const flags = names.map(() => false);
  names.forEach((n, i) => {
    const row = el('label', 'check');
    const input = el('input');
    input.type = 'checkbox';
    input.addEventListener('change', () => { flags[i] = input.checked; });
    row.appendChild(input);
    row.appendChild(el('span', '', n));
    parent.appendChild(row);
  });
  return () => flags;
}
