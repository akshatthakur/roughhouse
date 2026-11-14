// Edits on a track's audio; each returns a new Int16Array. Ported from Editor.java.
import { SR } from './timing.js';

const FADE_EDGE = SR / 200; // 5 ms ramp so cuts don't click

function ramp(o, from, to, up) {
  const n = to - from;
  if (n <= 0) return;
  for (let i = 0; i < n; i++) {
    const g = up ? i / n : 1 - i / n;
    o[from + i] = Math.round(o[from + i] * g);
  }
}

/** Silences everything before frame p (keeps the timeline position of what's left). */
export function trimBefore(d, p) {
  const at = Math.max(0, Math.min(d.length, p));
  const o = d.slice();
  o.fill(0, 0, at);
  ramp(o, at, Math.min(d.length, at + FADE_EDGE), true);
  return o;
}

/** Cuts everything from frame p onward. */
export function trimAfter(d, p) {
  const at = Math.max(0, Math.min(d.length, p));
  const o = d.slice(0, at);
  ramp(o, Math.max(0, at - FADE_EDGE), at, false);
  return o;
}

export const tail = trimBefore;

/** Moves the audio later (positive) or earlier (negative) by n frames. */
export function shift(d, n) {
  n = Math.round(n);
  if (n === 0) return d.slice();
  if (n > 0) {
    const o = new Int16Array(d.length + n);
    o.set(d, n);
    return o;
  }
  const drop = Math.min(d.length, -n);
  const o = d.slice(drop);
  ramp(o, 0, Math.min(o.length, FADE_EDGE), true);
  return o;
}

/** First frame louder than about -40 dBFS, or -1 if the track is silent. */
export function firstSound(d) {
  for (let i = 0; i < d.length; i++) if (d[i] > 328 || d[i] < -328) return i;
  return -1;
}

/** Last frame louder than about -40 dBFS, or -1. */
export function lastSound(d) {
  for (let i = d.length - 1; i >= 0; i--) if (d[i] > 328 || d[i] < -328) return i;
  return -1;
}

/** Linear fade in from the first sound up to frame p. */
export function fadeInTo(d, p) {
  const a = Math.max(0, firstSound(d)), b = Math.max(a + 1, Math.min(d.length, p));
  const o = d.slice();
  o.fill(0, 0, a);
  ramp(o, a, b, true);
  return o;
}

/** Linear fade out from frame p to the last sound; silence after. */
export function fadeOutFrom(d, p) {
  const end = lastSound(d);
  if (end < 0) return d.slice();
  const a = Math.max(0, Math.min(end, p));
  const o = d.slice(0, end + 1);
  ramp(o, a, end + 1, false);
  return o;
}
