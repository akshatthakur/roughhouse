package com.roughhouse.app;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Minimal lossless FLAC encoder: 16-bit stereo, fixed-blocksize frames, FIXED predictors
 * (order 0-4) with partitioned Rice coding, and per-frame choice of stereo decorrelation
 * (independent / left-side / side-right / mid-side). Total sample count is written up front.
 */
final class FlacWriter {
    private static final int BLOCK = 4096;

    private final OutputStream os;
    private final int sr;
    private final short[] pend = new short[BLOCK * 2];
    private int pendN;
    private long frameNo;
    private final Bits bw = new Bits();
    private final int[] l = new int[BLOCK], r = new int[BLOCK], m = new int[BLOCK], s = new int[BLOCK];
    private final int[] res = new int[BLOCK];

    FlacWriter(OutputStream os, int sampleRate, long totalFrames) throws IOException {
        this.os = os;
        this.sr = sampleRate;
        Bits h = new Bits();
        h.put(0x664C6143L, 32);          // "fLaC"
        h.put(1, 1); h.put(0, 7);        // last metadata block, STREAMINFO
        h.put(34, 24);
        h.put(BLOCK, 16); h.put(BLOCK, 16);
        h.put(0, 24); h.put(0, 24);      // min/max frame size unknown
        h.put(sampleRate, 20);
        h.put(1, 3);                     // channels - 1
        h.put(15, 5);                    // bits per sample - 1
        h.put(totalFrames >>> 32, 4);
        h.put(totalFrames & 0xFFFFFFFFL, 32);
        for (int i = 0; i < 4; i++) h.put(0, 32); // MD5 unknown
        os.write(h.buf, 0, h.bytes());
    }

    /** Appends n stereo frames from interleaved 16-bit samples. */
    void write(short[] in, int n) throws IOException {
        int i = 0;
        while (i < n) {
            int take = Math.min(n - i, BLOCK - pendN);
            System.arraycopy(in, i * 2, pend, pendN * 2, take * 2);
            pendN += take;
            i += take;
            if (pendN == BLOCK) { frame(BLOCK); pendN = 0; }
        }
    }

    void finish() throws IOException {
        if (pendN > 0) frame(pendN);
        pendN = 0;
        os.flush();
    }

    private void frame(int n) throws IOException {
        for (int i = 0; i < n; i++) {
            int a = pend[2 * i], b = pend[2 * i + 1];
            l[i] = a; r[i] = b; s[i] = a - b; m[i] = (a + b) >> 1;
        }
        // estimate cost of each channel signal with its best predictor
        long cl = cost(l, n, 16), cr = cost(r, n, 16), cs = cost(s, n, 17), cm = cost(m, n, 16);
        int mode = 1; // independent
        long best = cl + cr;
        if (cl + cs < best) { best = cl + cs; mode = 8; }  // left/side
        if (cs + cr < best) { best = cs + cr; mode = 9; }  // side/right
        if (cm + cs < best) { mode = 10; }                 // mid/side

        bw.reset();
        bw.put(0x3FFE, 14); bw.put(0, 1); bw.put(0, 1);   // sync, reserved, fixed blocksize
        boolean full = n == BLOCK;
        bw.put(full ? 12 : 7, 4);                          // 12 = 4096, 7 = 16-bit size follows
        bw.put(srCode(), 4);
        bw.put(mode, 4);
        bw.put(4, 3);                                      // 16 bits per sample
        bw.put(0, 1);
        utf8(bw, frameNo++);
        if (!full) bw.put(n - 1, 16);
        if (srCode() == 12) bw.put(sr / 1000, 8);
        bw.put(crc8(bw.buf, bw.bytes()), 8);

        switch (mode) {
            case 1: subframe(l, n, 16); subframe(r, n, 16); break;
            case 8: subframe(l, n, 16); subframe(s, n, 17); break;
            case 9: subframe(s, n, 17); subframe(r, n, 16); break;
            default: subframe(m, n, 16); subframe(s, n, 17); break;
        }
        bw.align();
        bw.put(crc16(bw.buf, bw.bytes()), 16);
        os.write(bw.buf, 0, bw.bytes());
    }

    private int srCode() {
        switch (sr) {
            case 44100: return 9;
            case 48000: return 10;
            case 96000: return 11;
            case 32000: return 8;
            case 22050: return 6;
            default: return 12; // 8-bit kHz value follows (only for whole kHz rates)
        }
    }

    // ------------------------------------------------------------- subframes

    private int bestOrder;

    private long cost(int[] x, int n, int bps) {
        long best = Long.MAX_VALUE;
        int maxOrder = Math.min(4, n - 1);
        for (int ord = 0; ord <= maxOrder; ord++) {
            residual(x, n, ord);
            long c = (long) ord * bps + riceCost(res, ord, n);
            if (c < best) { best = c; bestOrder = ord; }
        }
        return best;
    }

    private void subframe(int[] x, int n, int bps) {
        boolean constant = true;
        for (int i = 1; i < n && constant; i++) if (x[i] != x[0]) constant = false;
        if (constant) {
            bw.put(0, 1); bw.put(0, 6); bw.put(0, 1);
            bw.putSigned(x[0], bps);
            return;
        }
        cost(x, n, bps); // sets bestOrder
        int ord = bestOrder;
        long verbatim = (long) n * bps;
        residual(x, n, ord);
        long fixed = (long) ord * bps + riceCost(res, ord, n);
        if (verbatim <= fixed) {
            bw.put(0, 1); bw.put(1, 6); bw.put(0, 1);
            for (int i = 0; i < n; i++) bw.putSigned(x[i], bps);
            return;
        }
        bw.put(0, 1); bw.put(8 | ord, 6); bw.put(0, 1);
        for (int i = 0; i < ord; i++) bw.putSigned(x[i], bps);
        writeResidual(res, ord, n);
    }

    private void residual(int[] x, int n, int ord) {
        for (int i = ord; i < n; i++) {
            int e;
            switch (ord) {
                case 0: e = x[i]; break;
                case 1: e = x[i] - x[i - 1]; break;
                case 2: e = x[i] - 2 * x[i - 1] + x[i - 2]; break;
                case 3: e = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]; break;
                default: e = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]; break;
            }
            res[i] = e;
        }
    }

    private int bestPartOrder;
    private final int[] bestParams = new int[1 << 8];
    private final int[] tmpParams = new int[1 << 8];

    /** Bits needed for the residual using the best partition order; remembers the choice. */
    private long riceCost(int[] e, int ord, int n) {
        long best = Long.MAX_VALUE;
        int maxPo = 0;
        while (maxPo < 8 && (n % (1 << (maxPo + 1))) == 0 && (n >> (maxPo + 1)) > ord) maxPo++;
        for (int po = 0; po <= maxPo; po++) {
            int parts = 1 << po, psize = n >> po;
            long bits = 2 + 4;
            for (int p = 0; p < parts; p++) {
                int start = p == 0 ? ord : p * psize, end = (p + 1) * psize;
                long sum = 0;
                for (int i = start; i < end; i++) sum += fold(e[i]);
                int cnt = end - start;
                int k = 0;
                if (cnt > 0) {
                    long mean = sum / cnt;
                    while (k < 14 && (1L << (k + 1)) <= mean) k++;
                }
                // exact cost for k
                long c = 4 + (long) cnt * (k + 1) + (sum >> k);
                tmpParams[p] = k;
                bits += c;
            }
            if (bits < best) {
                best = bits;
                bestPartOrder = po;
                System.arraycopy(tmpParams, 0, bestParams, 0, parts);
            }
        }
        return best;
    }

    private void writeResidual(int[] e, int ord, int n) {
        riceCost(e, ord, n); // refresh chosen partitioning for this residual
        int po = bestPartOrder, parts = 1 << po, psize = n >> po;
        bw.put(0, 2);   // 4-bit rice parameters
        bw.put(po, 4);
        for (int p = 0; p < parts; p++) {
            int k = bestParams[p];
            bw.put(k, 4);
            int start = p == 0 ? ord : p * psize, end = (p + 1) * psize;
            for (int i = start; i < end; i++) {
                long u = fold(e[i]);
                long q = u >>> k;
                bw.unary(q);
                if (k > 0) bw.put(u & ((1L << k) - 1), k);
            }
        }
    }

    private static long fold(int v) {
        return v >= 0 ? (long) v << 1 : (((long) -v) << 1) - 1;
    }

    private static void utf8(Bits b, long v) {
        if (v < 0x80) { b.put(v, 8); return; }
        int bytes = v < 0x800 ? 2 : v < 0x10000 ? 3 : v < 0x200000 ? 4 : v < 0x4000000 ? 5 : 6;
        int lead = (0xFF00 >> bytes) & 0xFF;
        b.put(lead | (v >>> (6 * (bytes - 1))), 8);
        for (int i = bytes - 2; i >= 0; i--) b.put(0x80 | ((v >>> (6 * i)) & 0x3F), 8);
    }

    // ------------------------------------------------------------------ CRCs

    private static int crc8(byte[] d, int len) {
        int c = 0;
        for (int i = 0; i < len; i++) {
            c ^= d[i] & 0xFF;
            for (int k = 0; k < 8; k++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
        }
        return c;
    }

    private static int crc16(byte[] d, int len) {
        int c = 0;
        for (int i = 0; i < len; i++) {
            c ^= (d[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x8005) & 0xFFFF : (c << 1) & 0xFFFF;
        }
        return c;
    }

    // ------------------------------------------------------------ bit writer

    private static final class Bits {
        byte[] buf = new byte[1 << 16];
        int pos;      // bytes completed
        long acc;     // pending bits (right-aligned)
        int nacc;

        void reset() { pos = 0; acc = 0; nacc = 0; }

        int bytes() { return pos; }

        void put(long v, int n) {
            while (n > 0) {
                int take = Math.min(n, 32);
                long part = (v >>> (n - take)) & ((1L << take) - 1);
                acc = (acc << take) | part;
                nacc += take;
                n -= take;
                while (nacc >= 8) {
                    nacc -= 8;
                    if (pos >= buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    buf[pos++] = (byte) (acc >>> nacc);
                }
                acc &= (1L << nacc) - 1;
            }
        }

        void putSigned(int v, int n) { put(v & ((1L << n) - 1), n); }

        void unary(long zeros) {
            while (zeros >= 32) { put(0, 32); zeros -= 32; }
            put(1, (int) zeros + 1);
        }

        void align() { if (nacc > 0) put(0, 8 - nacc); }
    }
}
