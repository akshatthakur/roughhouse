// Growable byte buffer with the little/big-endian helpers the file writers need.
export class Bytes {
  constructor(cap = 1 << 16) { this.b = new Uint8Array(cap); this.n = 0; }
  ensure(k) {
    if (this.n + k <= this.b.length) return;
    let c = this.b.length * 2;
    while (c < this.n + k) c *= 2;
    const nb = new Uint8Array(c);
    nb.set(this.b.subarray(0, this.n));
    this.b = nb;
  }
  u8(v) { this.ensure(1); this.b[this.n++] = v & 0xFF; }
  bytes(a) { this.ensure(a.length); this.b.set(a, this.n); this.n += a.length; }
  ascii(s) { for (let i = 0; i < s.length; i++) this.u8(s.charCodeAt(i)); }
  le16(v) { this.u8(v); this.u8(v >> 8); }
  le32(v) { this.u8(v); this.u8(v >> 8); this.u8(v >> 16); this.u8(v >>> 24); }
  be16(v) { this.u8(v >> 8); this.u8(v); }
  be32(v) { this.u8(v >>> 24); this.u8(v >> 16); this.u8(v >> 8); this.u8(v); }
  result() { return this.b.slice(0, this.n); }
}

export const utf8 = s => new TextEncoder().encode(s);
