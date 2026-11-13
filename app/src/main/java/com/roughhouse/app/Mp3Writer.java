package com.roughhouse.app;

import java.io.IOException;
import java.io.OutputStream;

import mp3.BRHist;
import mp3.BitStream;
import mp3.GainAnalysis;
import mp3.GetAudio;
import mp3.ID3Tag;
import mp3.Lame;
import mp3.LameGlobalFlags;
import mp3.MPEGMode;
import mp3.Parse;
import mp3.Presets;
import mp3.Quantize;
import mp3.QuantizePVT;
import mp3.Reservoir;
import mp3.Takehiro;
import mp3.VBRTag;
import mp3.Version;
import mpg.Common;
import mpg.Interface;
import mpg.MPGLib;

/** Stereo 16-bit PCM -> CBR MP3 using the vendored LAME Java port (mp3/, mpg/ packages, LGPL). */
final class Mp3Writer {
    private final Lame lame = new Lame();
    private final LameGlobalFlags gfp;
    private final OutputStream os;
    private byte[] buf = new byte[16384];

    Mp3Writer(OutputStream os, int sampleRate, int kbps) throws IOException {
        this.os = os;
        GetAudio gaud = new GetAudio();
        GainAnalysis ga = new GainAnalysis();
        BitStream bs = new BitStream();
        Presets p = new Presets();
        QuantizePVT qupvt = new QuantizePVT();
        Quantize qu = new Quantize();
        VBRTag vbr = new VBRTag();
        Version ver = new Version();
        ID3Tag id3 = new ID3Tag();
        Reservoir rv = new Reservoir();
        Takehiro tak = new Takehiro();
        Parse parse = new Parse();
        new BRHist();
        MPGLib mpg = new MPGLib();
        Interface intf = new Interface();
        Common common = new Common();
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg);
        bs.setModules(ga, mpg, ver, vbr);
        id3.setModules(bs, ver);
        p.setModules(lame);
        qu.setModules(bs, rv, qupvt, tak);
        qupvt.setModules(tak, rv, lame.enc.psy);
        rv.setModules(bs);
        tak.setModules(qupvt);
        vbr.setModules(lame, bs, ver);
        gaud.setModules(parse, mpg);
        parse.setModules(ver, id3, p);
        mpg.setModules(intf, common);
        intf.setModules(vbr, common);

        gfp = lame.lame_init();
        gfp.num_channels = 2;
        gfp.in_samplerate = sampleRate;
        gfp.out_samplerate = sampleRate;
        gfp.mode = MPEGMode.JOINT_STEREO;
        gfp.brate = kbps;
        gfp.quality = 5;
        gfp.write_id3tag_automatic = false;
        if (lame.lame_init_params(gfp) < 0) throw new IOException("MP3 encoder rejected settings");
    }

    /** n stereo frames from interleaved 16-bit samples. */
    void write(short[] interleaved, int n) throws IOException {
        int[] l = new int[n], r = new int[n];
        for (int i = 0; i < n; i++) {
            l[i] = interleaved[2 * i]; // this port expects +/-32768, not 32-bit full scale
            r[i] = interleaved[2 * i + 1];
        }
        int need = (int) (1.25 * n) + 7200;
        if (buf.length < need) buf = new byte[need];
        int k = lame.lame_encode_buffer_int(gfp, l, r, n, buf, 0, buf.length);
        if (k < 0) throw new IOException("MP3 encode error " + k);
        os.write(buf, 0, k);
    }

    void finish() throws IOException {
        int k = lame.lame_encode_flush(gfp, buf, 0, buf.length);
        if (k > 0) os.write(buf, 0, k);
        lame.lame_close(gfp);
    }
}
