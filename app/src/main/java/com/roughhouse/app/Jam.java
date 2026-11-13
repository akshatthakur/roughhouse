package com.roughhouse.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** One jam: tracks + project settings, stored in its own folder. */
final class Jam {
    final File dir;
    String name;
    volatile float bpm = 100f;
    volatile boolean loop, metronome, countIn = true;
    volatile long loopIn = 0, loopOut = -1;
    volatile float metroVol = 0.6f, master = 0.9f;
    volatile int beatsPerBar = 4, beatUnit = 4;   // time signature
    volatile int keyRoot = -1;                    // 0 = C, -1 = not set
    volatile boolean keyMinor;

    /** Frames per metronome beat (the time signature's denominator note gets the BPM). */
    double beatFrames(int sr) {
        return 60.0 * sr / Math.max(30f, bpm);
    }

    /** Frames per quarter note, used for tempo-synced echo. */
    double quarterFrames(int sr) {
        return beatFrames(sr) * beatUnit / 4.0;
    }

    String timeSig() {
        return beatsPerBar + "/" + beatUnit;
    }

    String keyLabel() {
        return Analyzer.keyName(keyRoot, keyMinor);
    }
    final CopyOnWriteArrayList<Track> tracks = new CopyOnWriteArrayList<>();

    Jam(File dir) {
        this.dir = dir;
    }

    static Jam create(File root) {
        long now = System.currentTimeMillis();
        File d = new File(root, "s" + now);
        d.mkdirs();
        Jam s = new Jam(d);
        s.name = "jam " + new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(new Date(now)).toLowerCase(Locale.getDefault());
        return s;
    }

    long lengthFrames() {
        long m = 0;
        for (Track t : tracks) m = Math.max(m, t.data.length);
        return m;
    }

    long nextId() {
        long m = 0;
        for (Track t : tracks) m = Math.max(m, t.id);
        return m + 1;
    }

    // ------------------------------------------------------------------ save

    void save() throws IOException {
        dir.mkdirs();
        JSONObject o = new JSONObject();
        HashSet<String> keep = new HashSet<>();
        try {
            o.put("name", name);
            o.put("bpm", bpm);
            o.put("loop", loop);
            o.put("metronome", metronome);
            o.put("countIn", countIn);
            o.put("loopIn", loopIn);
            o.put("loopOut", loopOut);
            o.put("metroVol", metroVol);
            o.put("beatsPerBar", beatsPerBar);
            o.put("beatUnit", beatUnit);
            o.put("keyRoot", keyRoot);
            o.put("keyMinor", keyMinor);
            o.put("len", lengthFrames());
            JSONArray a = new JSONArray();
            for (Track t : tracks) {
                JSONObject j = new JSONObject();
                j.put("id", t.id);
                j.put("name", t.name);
                j.put("vol", t.vol);
                j.put("pan", t.pan);
                j.put("lowCut", t.lowCut);
                j.put("bass", t.bass);
                j.put("treble", t.treble);
                j.put("comp", t.comp);
                j.put("echo", t.echo);
                j.put("echoFb", t.echoFb);
                j.put("echoDiv", t.echoDiv);
                j.put("rev", t.rev);
                j.put("mute", t.mute);
                j.put("solo", t.solo);
                j.put("vocal", t.vocal);
                j.put("showNotes", t.showNotes);
                a.put(j);
                String fn = "t" + t.id + ".pcm";
                keep.add(fn);
                if (t.dirty || !new File(dir, fn).exists()) {
                    writePcm(new File(dir, fn), t.data);
                    t.dirty = false;
                }
                if (t.analysis != null) {
                    String an = "a" + t.id + ".json";
                    keep.add(an);
                    if (t.analysisDirty || !new File(dir, an).exists()) {
                        writeText(new File(dir, an), Analyzer.toJson(t.analysis).toString());
                        t.analysisDirty = false;
                    }
                }
            }
            o.put("tracks", a);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if ((n.endsWith(".pcm") || (n.startsWith("a") && n.endsWith(".json"))) && !keep.contains(n)) f.delete();
        }
        File tmp = new File(dir, "session.json.tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir, "session.json"))) throw new IOException("rename failed");
    }

    static Jam load(File d) {
        try {
            JSONObject o = readJson(d);
            Jam s = new Jam(d);
            s.name = lowerDefault(o.optString("name", d.getName()));
            s.bpm = (float) o.optDouble("bpm", 100);
            s.loop = o.optBoolean("loop", false);
            s.metronome = o.optBoolean("metronome", false);
            s.countIn = o.optBoolean("countIn", true);
            s.loopIn = o.optLong("loopIn", 0);
            s.loopOut = o.optLong("loopOut", -1);
            s.metroVol = (float) o.optDouble("metroVol", 0.6);
            s.beatsPerBar = o.optInt("beatsPerBar", 4);
            s.beatUnit = o.optInt("beatUnit", 4);
            s.keyRoot = o.optInt("keyRoot", -1);
            s.keyMinor = o.optBoolean("keyMinor", false);
            JSONArray a = o.optJSONArray("tracks");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Track t = new Track(j.getLong("id"), lowerDefault(j.optString("name", "track " + (i + 1))));
                t.vocal = j.optBoolean("vocal", false);
                t.vol = (float) j.optDouble("vol", 0.8);
                t.pan = (float) j.optDouble("pan", 0);
                t.lowCut = (float) j.optDouble("lowCut", 0);
                t.bass = (float) j.optDouble("bass", 0);
                t.treble = (float) j.optDouble("treble", 0);
                t.comp = (float) j.optDouble("comp", 0);
                t.echo = (float) j.optDouble("echo", 0);
                t.echoFb = (float) j.optDouble("echoFb", 0.35);
                t.echoDiv = j.optInt("echoDiv", 3);
                t.rev = (float) j.optDouble("rev", 0);
                t.mute = j.optBoolean("mute", false);
                t.solo = j.optBoolean("solo", false);
                File f = new File(d, "t" + t.id + ".pcm");
                if (f.exists()) t.data = readPcm(f);
                t.computePeaks();
                File af = new File(d, "a" + t.id + ".json");
                if (af.exists()) {
                    try {
                        t.analysis = Analyzer.fromJson(new JSONObject(new String(Files.readAllBytes(af.toPath()), StandardCharsets.UTF_8)));
                        t.showNotes = j.optBoolean("showNotes", false);
                    } catch (Exception ignored) {
                        // a damaged analysis file just means analyzing again
                    }
                }
                s.tracks.add(t);
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** Lightweight listing info without loading audio. */
    static final class Info {
        File dir;
        String name;
        long len;
        int tracks;
    }

    static Info info(File d) {
        try {
            JSONObject o = readJson(d);
            Info i = new Info();
            i.dir = d;
            i.name = lowerDefault(o.optString("name", d.getName()));
            i.len = o.optLong("len", 0);
            JSONArray a = o.optJSONArray("tracks");
            i.tracks = a == null ? 0 : a.length();
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject readJson(File d) throws IOException, JSONException {
        byte[] b = Files.readAllBytes(new File(d, "session.json").toPath());
        return new JSONObject(new String(b, StandardCharsets.UTF_8));
    }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    /** Names generated by older versions ("Jam Nov 13, 15:40", "Track 2") become lowercase; typed names stay. */
    static String lowerDefault(String n) {
        if (n.matches("Jam [A-Z][a-z]{2,3}\\.? \\d{1,2}, \\d{1,2}:\\d{2}") || n.matches("Track \\d+( copy)*")) {
            return n.toLowerCase(Locale.getDefault());
        }
        return n;
    }

    private static void writeText(File f, String text) throws IOException {
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writePcm(File f, short[] d) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(d.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.asShortBuffer().put(d);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(bb.array());
        }
    }

    private static short[] readPcm(File f) throws IOException {
        byte[] b = Files.readAllBytes(f.toPath());
        short[] d = new short[b.length / 2];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(d);
        return d;
    }
}
