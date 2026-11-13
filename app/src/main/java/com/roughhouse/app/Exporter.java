package com.roughhouse.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Renders the jam offline (same FX chain as playback) and saves it to Music/Roughhouse. */
final class Exporter {
    private Exporter() {}

    enum Format {
        WAV("WAV", "Uncompressed, for DAWs", "wav", "audio/wav"),
        FLAC("FLAC", "Lossless, about half the size", "flac", "audio/flac"),
        M4A("M4A (AAC 256k)", "Small, plays everywhere", "m4a", "audio/mp4"),
        MP3("MP3 (192k)", "Universal", "mp3", "audio/mpeg");

        final String label, hint, ext, mime;

        Format(String label, String hint, String ext, String mime) {
            this.label = label; this.hint = hint; this.ext = ext; this.mime = mime;
        }
    }

    interface Progress { void at(float fraction); }

    /** Receives the rendered mix as interleaved stereo 16-bit. */
    private interface Sink {
        void write(short[] pcm, int frames) throws IOException;
        void finish() throws IOException;
    }

    static Uri export(Context c, Jam s, Format fmt, Progress progress) throws IOException {
        final int sr = Engine.SR;
        long len = s.lengthFrames();
        if (len == 0) throw new IOException("Nothing recorded yet");
        long total = len + sr * 2L; // 2 s tail for echo / reverb

        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        String fname = s.name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (fname.isEmpty()) fname = "Roughhouse jam";
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fname + "." + fmt.ext);
        v.put(MediaStore.MediaColumns.MIME_TYPE, fmt.mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Roughhouse");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) throw new IOException("Couldn't create the file");

        OutputStream raw = null;
        ParcelFileDescriptor pfd = null;
        try {
            Sink sink;
            if (fmt == Format.M4A) {
                pfd = cr.openFileDescriptor(uri, "rw");
                if (pfd == null) throw new IOException("Couldn't open the file");
                sink = new AacSink(pfd, sr);
            } else {
                raw = cr.openOutputStream(uri);
                if (raw == null) throw new IOException("Couldn't open the file");
                OutputStream os = new BufferedOutputStream(raw, 1 << 16);
                if (fmt == Format.WAV) sink = new WavSink(os, sr, total);
                else if (fmt == Format.FLAC) sink = new FlacSink(os, sr, total);
                else sink = new Mp3Sink(os, sr);
            }
            render(s, sr, total, sink, progress);
            sink.finish();
        } catch (IOException | RuntimeException e) {
            closeQuietly(raw, pfd);
            cr.delete(uri, null, null);
            throw e;
        }
        closeQuietly(raw, pfd);
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    private static void render(Jam s, int sr, long total, Sink sink, Progress progress) throws IOException {
        Mixer m = new Mixer(s, sr);
        int bs = 2048;
        float[] out = new float[bs * 2];
        long[] p = new long[bs];
        short[] pcm = new short[bs * 2];
        long lastReport = 0;
        for (long f = 0; f < total; f += bs) {
            int n = (int) Math.min(bs, total - f);
            for (int i = 0; i < n; i++) p[i] = f + i;
            m.render(out, p, n, null, false);
            for (int i = 0; i < n * 2; i++) {
                int x = Math.round(out[i] * 32767f);
                pcm[i] = (short) (x > 32767 ? 32767 : x < -32768 ? -32768 : x);
            }
            sink.write(pcm, n);
            if (progress != null && f - lastReport > sr) {
                lastReport = f;
                progress.at(f / (float) total);
            }
        }
    }

    private static void closeQuietly(OutputStream os, ParcelFileDescriptor pfd) {
        try { if (os != null) os.close(); } catch (IOException ignored) {}
        try { if (pfd != null) pfd.close(); } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------ sinks

    private static final class WavSink implements Sink {
        private final OutputStream os;
        private final byte[] b = new byte[2048 * 4];

        WavSink(OutputStream os, int sr, long frames) throws IOException {
            this.os = os;
            int dataLen = (int) (frames * 4);
            os.write(new byte[]{'R', 'I', 'F', 'F'});
            le32(os, 36 + dataLen);
            os.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            le32(os, 16);
            le16(os, 1);
            le16(os, 2);
            le32(os, sr);
            le32(os, sr * 4);
            le16(os, 4);
            le16(os, 16);
            os.write(new byte[]{'d', 'a', 't', 'a'});
            le32(os, dataLen);
        }

        @Override public void write(short[] pcm, int frames) throws IOException {
            for (int i = 0; i < frames * 2; i++) {
                b[2 * i] = (byte) pcm[i];
                b[2 * i + 1] = (byte) (pcm[i] >> 8);
            }
            os.write(b, 0, frames * 4);
        }

        @Override public void finish() throws IOException { os.flush(); os.close(); }
    }

    private static final class FlacSink implements Sink {
        private final OutputStream os;
        private final FlacWriter w;

        FlacSink(OutputStream os, int sr, long frames) throws IOException {
            this.os = os;
            w = new FlacWriter(os, sr, frames);
        }

        @Override public void write(short[] pcm, int frames) throws IOException { w.write(pcm, frames); }

        @Override public void finish() throws IOException { w.finish(); os.close(); }
    }

    private static final class Mp3Sink implements Sink {
        private final OutputStream os;
        private final Mp3Writer w;

        Mp3Sink(OutputStream os, int sr) throws IOException {
            this.os = os;
            w = new Mp3Writer(os, sr, 192);
        }

        @Override public void write(short[] pcm, int frames) throws IOException { w.write(pcm, frames); }

        @Override public void finish() throws IOException { w.finish(); os.flush(); os.close(); }
    }

    /** AAC-LC via the platform encoder, muxed into an .m4a (MPEG-4) container. */
    private static final class AacSink implements Sink {
        private final MediaCodec codec;
        private final MediaMuxer muxer;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final int sr;
        private int track = -1;
        private boolean started;
        private long framesIn;

        AacSink(ParcelFileDescriptor pfd, int sr) throws IOException {
            this.sr = sr;
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 2);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 256_000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        @Override public void write(short[] pcm, int frames) throws IOException {
            int off = 0; // in frames
            while (off < frames) {
                int ii = codec.dequeueInputBuffer(10_000);
                if (ii >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(ii);
                    if (ib == null) throw new IOException("AAC encoder input unavailable");
                    ib.clear();
                    ib.order(ByteOrder.LITTLE_ENDIAN);
                    int fit = Math.min(frames - off, ib.remaining() / 4);
                    for (int i = 0; i < fit * 2; i++) ib.putShort(pcm[off * 2 + i]);
                    long pts = framesIn * 1_000_000L / sr;
                    codec.queueInputBuffer(ii, 0, fit * 4, pts, 0);
                    framesIn += fit;
                    off += fit;
                }
                drain(false);
            }
        }

        @Override public void finish() throws IOException {
            boolean queued = false;
            for (int tries = 0; !queued && tries < 500; tries++) {
                int ii = codec.dequeueInputBuffer(10_000);
                if (ii >= 0) {
                    codec.queueInputBuffer(ii, 0, 0, framesIn * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    queued = true;
                }
                drain(false);
            }
            drain(true);
            codec.stop();
            codec.release();
            if (started) muxer.stop();
            muxer.release();
        }

        private void drain(boolean untilEos) throws IOException {
            int idle = 0;
            while (true) {
                int oi = codec.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEos) return;
                    if (++idle > 300) throw new IOException("AAC encoder stalled"); // ~3 s with no output
                    continue;
                }
                idle = 0;
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    started = true;
                    continue;
                }
                if (oi < 0) continue;
                ByteBuffer ob = codec.getOutputBuffer(oi);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (ob != null && info.size > 0 && !config && started) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    muxer.writeSampleData(track, ob, info);
                }
                codec.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    private static void le32(OutputStream os, int v) throws IOException {
        os.write(v); os.write(v >> 8); os.write(v >> 16); os.write(v >> 24);
    }

    private static void le16(OutputStream os, int v) throws IOException {
        os.write(v); os.write(v >> 8);
    }
}
