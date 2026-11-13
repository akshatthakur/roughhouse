package com.roughhouse.app;

import android.content.Context;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Decodes any audio file Android can read (MP3, WAV, M4A/AAC, FLAC, OGG, Opus...)
 * into a mono 16-bit track at Engine.SR. Stereo is folded to mono, since tracks are mono.
 */
final class Importer {
    private Importer() {}

    interface Progress { void at(float fraction); }

    static final class Decoded {
        String name;
        short[] data;
    }

    static Decoded decode(Context c, Uri uri, Progress progress) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(c, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IOException("No audio found in that file");
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            int srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int pcmEnc = AudioFormat.ENCODING_PCM_16BIT;
            float[] mono = new float[(int) Math.max(srcRate * 10L, durUs > 0 ? durUs * srcRate / 1_000_000L + srcRate : 0)];
            int n = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            while (!outDone) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEnc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ByteBuffer le = ob.slice().order(ByteOrder.LITTLE_ENDIAN);
                        int bytesPer = pcmEnc == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                        int frames = info.size / (bytesPer * Math.max(1, channels));
                        if (n + frames > mono.length) mono = Arrays.copyOf(mono, Math.max(mono.length * 3 / 2, n + frames));
                        for (int f = 0; f < frames; f++) {
                            float sum = 0;
                            for (int ch = 0; ch < channels; ch++) {
                                sum += bytesPer == 4 ? le.getFloat() : le.getShort() * (1f / 32768f);
                            }
                            mono[n++] = sum / channels;
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    if (progress != null && durUs > 0) progress.at(Math.min(1f, n / (float) (durUs * srcRate / 1e6)));
                }
            }
            Decoded d = new Decoded();
            d.name = displayName(c, uri);
            d.data = toTrack(resample(mono, n, srcRate, Engine.SR));
            return d;
        } catch (OutOfMemoryError e) {
            throw new IOException("That file is too long to load on this phone");
        } catch (RuntimeException e) {
            throw new IOException("Couldn't read that file (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
            ex.release();
        }
    }

    /** Band-limited enough for music: low-pass when downsampling, then cubic interpolation. */
    static float[] resample(float[] x, int n, int from, int to) {
        if (from == to) return Arrays.copyOf(x, n);
        if (to < from) {
            Dsp.Biquad a = new Dsp.Biquad(), b = new Dsp.Biquad();
            a.lowpass(0.45 * to, 0.54, from);
            b.lowpass(0.45 * to, 1.31, from);
            for (int i = 0; i < n; i++) x[i] = b.process(a.process(x[i]));
        }
        int m = (int) ((long) n * to / from);
        float[] y = new float[m];
        double step = from / (double) to;
        for (int i = 0; i < m; i++) {
            double pos = i * step;
            int k = (int) pos;
            float t = (float) (pos - k);
            float p0 = x[Math.max(0, k - 1)], p1 = x[Math.min(n - 1, k)],
                    p2 = x[Math.min(n - 1, k + 1)], p3 = x[Math.min(n - 1, k + 2)];
            // Catmull-Rom
            y[i] = p1 + 0.5f * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)));
        }
        return y;
    }

    static short[] toTrack(float[] y) {
        short[] d = new short[y.length];
        for (int i = 0; i < y.length; i++) {
            float v = y[i] * 32767f;
            d[i] = (short) (v > 32767 ? 32767 : v < -32768 ? -32768 : v);
        }
        return d;
    }

    static String displayName(Context c, Uri uri) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (RuntimeException ignored) {
        }
        if (name == null) name = "Imported";
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.length() > 28 ? name.substring(0, 28) : name;
    }
}
