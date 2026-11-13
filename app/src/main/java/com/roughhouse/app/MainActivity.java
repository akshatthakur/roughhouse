package com.roughhouse.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public class MainActivity extends Activity implements Engine.Listener {
    static final int SR = Engine.SR;
    private static final int REQ_IMPORT = 7;

    final Handler h = new Handler(Looper.getMainLooper());
    Engine eng;
    Jam s;
    private File root;
    private SharedPreferences prefs;

    // deck
    private Deck.Cassette cassette;
    private Deck.Counter counter;
    private Deck.Vu vuL, vuR;
    private Deck.Tuner tuner;
    private View vuPair;
    private TextView bpmChip, sigChip, keyChip, tuneChip, loopChip, clickChip, undoChip, noteBar;
    private Deck.Key rewKey, recKey, playKey, stopKey;
    private LinearLayout trackList;
    private Ruler ruler;
    private final ArrayList<Row> rows = new ArrayList<>();

    private Track armed, lastTake;
    private long cursor;
    private boolean ticking, counterBars;
    private AlertDialog calDlg;
    private final ArrayList<Long> taps = new ArrayList<>();
    private final Runnable hideNote = () -> noteBar.setVisibility(View.GONE);

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.density = getResources().getDisplayMetrics().density;
        Ui.loadFonts(this);
        prefs = getSharedPreferences("rh", MODE_PRIVATE);
        root = new File(getFilesDir(), "jams");
        File old = new File(getFilesDir(), "sessions");
        if (!root.exists() && old.isDirectory()) old.renameTo(root);
        root.mkdirs();
        eng = new Engine(h, this);
        setContentView(buildUi());

        Jam loaded = null;
        String last = prefs.getString("last", null);
        if (last != null) {
            File d = new File(root, last);
            if (new File(d, "session.json").exists()) loaded = Jam.load(d);
        }
        openJam(loaded != null ? loaded : Jam.create(root));
        if (!hasMic()) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (eng.isPlaying()) eng.stop();
        if (eng.monitoring) { eng.stopMonitor(); refreshAll(); }
        save();
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needMic() {
        if (hasMic()) return true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        return false;
    }

    // ======================================================================= UI

    private int dp(float v) { return Ui.dp(v); }

    private View buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BODY);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        frame.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // --- brand strip
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(10), dp(10), dp(6));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView word = Ui.silk(this, "ROUGHHOUSE", 15, Ui.CREAM);
        word.setLetterSpacing(0.3f);
        brand.addView(word);
        brand.addView(Ui.silk(this, "MULTITRACK CASSETTE RECORDER", 7.5f, Ui.DIM));
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView jams = Ui.chip(this, "JAMS");
        jams.setOnClickListener(v -> showJams());
        TextView nw = Ui.chip(this, "NEW");
        nw.setOnClickListener(v -> newJam());
        TextView more = Ui.chip(this, "•••");
        more.setOnClickListener(v -> showMenu());
        top.addView(nw, gap(0));
        top.addView(jams, gap(6));
        top.addView(more, gap(6));
        col.addView(top);

        // --- deck panel: cassette, counter + meters, settings chips
        LinearLayout deck = new LinearLayout(this);
        deck.setOrientation(LinearLayout.VERTICAL);
        deck.setPadding(dp(8), dp(8), dp(8), dp(8));
        Ui.panel(deck);
        cassette = new Deck.Cassette(this);
        cassette.setOnClickListener(v -> renameJam());
        deck.addView(cassette, new LinearLayout.LayoutParams(-1, dp(146)));

        LinearLayout meters = new LinearLayout(this);
        meters.setGravity(Gravity.CENTER_VERTICAL);
        meters.setPadding(0, dp(8), 0, 0);
        counter = new Deck.Counter(this);
        counter.setOnClickListener(v -> { counterBars = !counterBars; updateDeck(); });
        meters.addView(counter, new LinearLayout.LayoutParams(0, dp(46), 1));
        FrameLayout meterSlot = new FrameLayout(this);
        LinearLayout pair = new LinearLayout(this);
        vuL = new Deck.Vu(this, "L");
        vuR = new Deck.Vu(this, "R");
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(78), -1);
        pair.addView(vuL, vlp);
        LinearLayout.LayoutParams vlp2 = new LinearLayout.LayoutParams(dp(78), -1);
        vlp2.leftMargin = dp(4);
        pair.addView(vuR, vlp2);
        vuPair = pair;
        meterSlot.addView(pair, new FrameLayout.LayoutParams(-2, -1, Gravity.END));
        tuner = new Deck.Tuner(this);
        tuner.setVisibility(View.GONE);
        meterSlot.addView(tuner, new FrameLayout.LayoutParams(dp(160), -1, Gravity.END));
        meters.addView(meterSlot, new LinearLayout.LayoutParams(dp(164), dp(46)));
        deck.addView(meters);

        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(8), 0, 0);
        bpmChip = Ui.chip(this, "100 BPM");
        bpmChip.setOnClickListener(v -> showTempo());
        sigChip = Ui.chip(this, "4/4");
        sigChip.setOnClickListener(v -> showTimeSig());
        keyChip = Ui.chip(this, "KEY");
        keyChip.setOnClickListener(v -> showKeyPicker());
        keyChip.setOnLongClickListener(v -> { showCircle(); return true; });
        tuneChip = Ui.chip(this, "TUNE");
        tuneChip.setOnClickListener(v -> toggleTune());
        chips.addView(bpmChip, new LinearLayout.LayoutParams(0, -2, 1.3f));
        chips.addView(sigChip, weighted(1, 6));
        chips.addView(keyChip, weighted(1.3f, 6));
        chips.addView(tuneChip, weighted(1, 6));
        deck.addView(chips);

        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.leftMargin = dp(8);
        dlp.rightMargin = dp(8);
        col.addView(deck, dlp);

        // --- timeline for the whole jam, lined up with the lanes below
        LinearLayout rulerRow = new LinearLayout(this);
        rulerRow.setPadding(dp(8 + 6), dp(8), dp(8 + 6), dp(2));
        rulerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView rl = Ui.silk(this, "BAR", 7.5f, Ui.FAINT);
        rl.setGravity(Gravity.CENTER);
        rulerRow.addView(rl, new LinearLayout.LayoutParams(dp(28), -2));
        ruler = new Ruler(this);
        ruler.setContentDescription("timeline: tap or drag to move the playhead");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(30), 1);
        rlp.leftMargin = dp(6);
        rulerRow.addView(ruler, rlp);
        LinearLayout.LayoutParams rsp = new LinearLayout.LayoutParams(dp(34), dp(1));
        rsp.leftMargin = dp(6);
        rulerRow.addView(new View(this), rsp);
        col.addView(rulerRow);

        // --- channel strips
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        trackList = new LinearLayout(this);
        trackList.setOrientation(LinearLayout.VERTICAL);
        trackList.setPadding(dp(8), dp(2), dp(8), dp(8));
        sv.addView(trackList, new FrameLayout.LayoutParams(-1, -2));
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- transport
        LinearLayout tp = new LinearLayout(this);
        tp.setOrientation(LinearLayout.VERTICAL);
        tp.setPadding(dp(8), dp(8), dp(8), dp(12));
        tp.setBackgroundColor(Ui.PANEL);
        LinearLayout keys = new LinearLayout(this);
        rewKey = new Deck.Key(this, Deck.Key.REW);
        rewKey.setOnClickListener(v -> seekTo(0));
        recKey = new Deck.Key(this, Deck.Key.REC);
        recKey.setOnClickListener(v -> onRec());
        playKey = new Deck.Key(this, Deck.Key.PLAY);
        playKey.setOnClickListener(v -> onPlay());
        stopKey = new Deck.Key(this, Deck.Key.STOP);
        stopKey.setOnClickListener(v -> onStopKey());
        rewKey.setContentDescription("rewind to start");
        recKey.setContentDescription("record");
        playKey.setContentDescription("play");
        stopKey.setContentDescription("stop");
        keys.addView(rewKey, keyLp(0));
        keys.addView(recKey, keyLp(6));
        keys.addView(playKey, keyLp(6));
        keys.addView(stopKey, keyLp(6));
        tp.addView(keys);
        LinearLayout small = new LinearLayout(this);
        small.setPadding(0, dp(8), 0, 0);
        loopChip = Ui.chip(this, "LOOP");
        loopChip.setOnClickListener(v -> { s.loop = !s.loop; refreshAll(); });
        loopChip.setOnLongClickListener(v -> { showLoopMenu(); return true; });
        clickChip = Ui.chip(this, "CLICK");
        clickChip.setOnClickListener(v -> { s.metronome = !s.metronome; refreshAll(); });
        clickChip.setOnLongClickListener(v -> { showSettings(); return true; });
        undoChip = Ui.chip(this, "UNDO");
        undoChip.setOnClickListener(v -> onUndo());
        small.addView(loopChip, new LinearLayout.LayoutParams(0, -2, 1));
        small.addView(clickChip, weighted(1, 6));
        small.addView(undoChip, weighted(1, 6));
        tp.addView(small);
        col.addView(tp);

        // --- message strip (replaces system toasts so everything stays in the deck fonts)
        noteBar = Ui.text(this, 13, Ui.INK, Ui.sans);
        noteBar.setBackground(Ui.rounded(Ui.PAPER, dp(4), 0));
        noteBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        noteBar.setVisibility(View.GONE);
        noteBar.setOnClickListener(v -> noteBar.setVisibility(View.GONE));
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        nlp.setMargins(dp(12), 0, dp(12), dp(150));
        frame.addView(noteBar, nlp);
        return frame;
    }

    private LinearLayout.LayoutParams gap(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(left);
        return lp;
    }

    private LinearLayout.LayoutParams weighted(float w, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, w);
        lp.leftMargin = dp(left);
        return lp;
    }

    private LinearLayout.LayoutParams keyLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(70), 1);
        lp.leftMargin = dp(left);
        return lp;
    }

    /** One channel strip: coloured cap (tap = arm) · tape lane · M / S / FX. */
    private final class Row {
        final Track t;
        final LinearLayout v;
        final TextView cap, m, so, fx;
        final WaveView wave;

        Row(Track t, int index) {
            this.t = t;
            v = new LinearLayout(MainActivity.this);
            v.setGravity(Gravity.CENTER_VERTICAL);
            Ui.panel(v);
            v.setPadding(dp(6), dp(5), dp(6), dp(5));
            cap = Ui.text(MainActivity.this, 12, Ui.INK, Ui.sansBold);
            cap.setGravity(Gravity.CENTER);
            cap.setText(String.valueOf(index + 1));
            cap.setContentDescription("arm channel " + (index + 1) + " for recording");
            cap.setOnClickListener(x -> { armed = t; refreshAll(); });
            v.addView(cap, new LinearLayout.LayoutParams(dp(28), dp(28)));
            wave = new WaveView(MainActivity.this, t);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -1, 1);
            wl.leftMargin = dp(6);
            v.addView(wave, wl);
            LinearLayout btns = new LinearLayout(MainActivity.this);
            btns.setOrientation(LinearLayout.VERTICAL);
            m = small("M");
            m.setOnClickListener(x -> { t.mute = !t.mute; refreshAll(); });
            so = small("S");
            so.setOnClickListener(x -> { t.solo = !t.solo; refreshAll(); });
            fx = small("FX");
            fx.setOnClickListener(x -> showFx(t));
            btns.addView(m, smallLp(0));
            btns.addView(so, smallLp(3));
            btns.addView(fx, smallLp(3));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(34), -2);
            bl.leftMargin = dp(6);
            v.addView(btns, bl);
        }

        void refresh(int index) {
            boolean isArmed = nextRecTrack() == t;
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Ui.CAPS[index % Ui.CAPS.length]);
            if (isArmed) g.setStroke(dp(3), eng.isRecording() ? Ui.RED : Ui.CREAM);
            cap.setBackground(g);
            Ui.setChip(m, t.mute, Ui.BLUE);
            Ui.setChip(so, t.solo, Ui.YELLOW);
            Ui.setChip(fx, t.fxActive(), Ui.AMBER);
            wave.invalidate();
        }
    }

    private TextView small(String label) {
        TextView t = Ui.chip(this, label);
        t.setPadding(0, 0, 0, 0);
        t.setTextSize(10);
        return t;
    }

    private LinearLayout.LayoutParams smallLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(20));
        lp.topMargin = dp(top);
        return lp;
    }

    private void rebuildRows() {
        trackList.removeAllViews();
        rows.clear();
        int i = 0;
        for (Track t : s.tracks) {
            Row r = new Row(t, i++);
            rows.add(r);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(78));
            lp.topMargin = dp(6);
            trackList.addView(r.v, lp);
        }
        if (s.tracks.isEmpty()) {
            TextView e = Ui.text(this, 17, Ui.DIM, Ui.serifItalic);
            e.setText("press REC to lay down a jam.\nevery take lands on a new track.");
            e.setGravity(Gravity.CENTER);
            e.setLineSpacing(0, 1.25f);
            e.setPadding(0, dp(40), 0, dp(16));
            trackList.addView(e, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout foot = new LinearLayout(this);
        foot.setPadding(0, dp(8), 0, 0);
        TextView add = Ui.chip(this, "+ TRACK");
        add.setOnClickListener(v -> { armed = addTrack(); refreshAll(); });
        TextView imp = Ui.chip(this, "IMPORT AUDIO");
        imp.setOnClickListener(v -> pickImport());
        foot.addView(add, new LinearLayout.LayoutParams(0, -2, 1));
        foot.addView(imp, weighted(1, 6));
        trackList.addView(foot, new LinearLayout.LayoutParams(-1, -2));
    }

    void refreshAll() {
        bpmChip.setText(fmtBpm(s.bpm) + " BPM");
        sigChip.setText(s.timeSig());
        keyChip.setText(s.keyRoot < 0 ? "KEY –" : (Analyzer.NOTES[s.keyRoot] + (s.keyMinor ? " MIN" : " MAJ")));
        Ui.setChip(tuneChip, eng.monitoring, Ui.GREEN);
        Ui.setChip(loopChip, s.loop, Ui.AMBER);
        Ui.setChip(clickChip, s.metronome, Ui.YELLOW);
        undoChip.setAlpha(lastTake != null && !eng.isRecording() ? 1f : 0.4f);
        boolean playing = eng.isPlaying();
        playKey.set(playing, playing);
        recKey.set(eng.isRecording(), eng.isRecording());
        int i = 0;
        for (Row r : rows) r.refresh(i++);
        boolean showTuner = eng.monitoring || (eng.isRecording() && eng.recTrack != null && eng.recTrack.vocal);
        tuner.setVisibility(showTuner ? View.VISIBLE : View.GONE);
        vuPair.setVisibility(showTuner ? View.INVISIBLE : View.VISIBLE);
        updateDeck();
        ruler.invalidate();
        if (trackRefresh != null) trackRefresh.run();
        if (trackView != null) trackView.invalidate();
    }

    private static String fmtBpm(float b) {
        return b == Math.round(b) ? String.valueOf(Math.round(b)) : String.format(Locale.US, "%.1f", b);
    }

    /** Counter, cassette label and reels. */
    private void updateDeck() {
        long p = playhead();
        boolean rec = eng.isRecording();
        cassette.title = s.name;
        cassette.sub = (fmtBpm(s.bpm) + " BPM  ·  " + s.timeSig() + "  ·  "
                + (s.keyRoot < 0 ? "NO KEY" : s.keyLabel()) + "  ·  " + s.tracks.size()
                + (s.tracks.size() == 1 ? " TRACK" : " TRACKS")).toUpperCase(Locale.ROOT);
        cassette.recording = rec;
        long view = viewFrames();
        cassette.frac = view > 0 ? Math.max(0, Math.min(1, p / (float) view)) : 0;
        cassette.angle = (float) (Math.max(0, p) / (double) SR * Math.PI * 1.2);
        cassette.invalidate();

        counter.red = rec;
        if (eng.isPlaying() && eng.position() < eng.startPosition() && rec) {
            long left = (long) Math.ceil((eng.startPosition() - eng.position()) / s.beatFrames(SR));
            counter.text = String.format(Locale.US, "IN %d", Math.max(1, left));
            counter.caption = "COUNT-IN";
        } else if (counterBars) {
            double bf = s.beatFrames(SR);
            long beat = (long) Math.floor(Math.max(0, p) / bf);
            int bpb = Math.max(1, s.beatsPerBar);
            counter.text = String.format(Locale.US, "%03d.%d", beat / bpb + 1, beat % bpb + 1);
            counter.caption = "BAR . BEAT   (TAP FOR TIME)";
        } else {
            long tenths = Math.max(0, p) * 10 / SR;
            counter.text = String.format(Locale.US, "%02d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
            counter.caption = "TAPE TIME   (TAP FOR BARS)";
        }
        counter.invalidate();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean active = eng.isPlaying() || eng.monitoring;
            boolean moving = vuL.step(eng.isPlaying() ? eng.levelL : eng.monitoring ? eng.inputLevel : 0);
            moving |= vuR.step(eng.isPlaying() ? eng.levelR : eng.monitoring ? eng.inputLevel : 0);
            if (active) {
                updateDeck();
                for (Row r : rows) r.wave.invalidate();
                ruler.invalidate();
                if (trackView != null) trackView.invalidate();
                recKey.level = eng.inputLevel;
                recKey.invalidate();
                if (tuner.getVisibility() == View.VISIBLE) tuner.set(eng.pitchHz);
            }
            if (!active && !moving) { ticking = false; return; }
            h.postDelayed(this, 33);
        }
    };

    private void startTicking() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!ticking) { ticking = true; h.post(tick); }
    }

    void note(String msg) {
        noteBar.setText(msg);
        noteBar.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideNote);
        h.postDelayed(hideNote, Math.min(6000, 2200 + msg.length() * 40L));
    }

    // ============================================================ shared state

    long viewFrames() {
        long m = Math.max(s.lengthFrames(), 15L * SR);
        if (eng.isPlaying()) m = Math.max(m, eng.position());
        return (long) (m * 1.1);
    }

    long playhead() {
        return eng.isPlaying() ? eng.position() : cursor;
    }

    boolean isAudible(Track t) {
        if (t.mute) return false;
        for (Track o : s.tracks) if (o.solo) return t.solo;
        return true;
    }

    /** The track the next REC will record into (null = a new track). */
    Track nextRecTrack() {
        if (eng.isRecording()) return eng.recTrack;
        if (armed != null && s.tracks.contains(armed)) return armed;
        for (Track t : s.tracks) if (t.isEmpty()) return t;
        return null;
    }

    void seekTo(long frame) {
        frame = Math.max(0, frame);
        cursor = frame;
        if (eng.isPlaying() && !eng.isRecording()) eng.seek(frame);
        updateDeck();
        for (Row r : rows) r.wave.invalidate();
        if (ruler != null) ruler.invalidate();
    }

    private Track addTrack() {
        Track t = new Track(s.nextId(), "track " + (s.tracks.size() + 1));
        s.tracks.add(t);
        rebuildRows();
        return t;
    }

    private void applyEngineSettings() {
        eng.s = s;
        eng.latencyFrames = prefs.getInt("lat", 120) * SR / 1000;
        eng.inputGain = (float) Math.pow(10, prefs.getInt("boost", 0) / 20.0);
    }

    // ================================================================ transport

    private void onPlay() {
        if (eng.isPlaying()) return; // like the real key: already rolling
        if (s.lengthFrames() == 0 && !s.metronome) { note("nothing on tape yet. press REC to record."); return; }
        applyEngineSettings();
        eng.play(cursor, false);
        refreshAll();
        startTicking();
    }

    private void onStopKey() {
        if (eng.isPlaying()) eng.stop();
        else { cursor = 0; updateDeck(); for (Row r : rows) r.wave.invalidate(); } // second STOP returns to zero
    }

    private void onRec() {
        if (eng.isRecording()) { eng.stopRecording(); refreshAll(); return; } // punch out, keep rolling
        if (!needMic()) return;
        Track t = nextRecTrack();
        if (t == null) t = addTrack();
        applyEngineSettings();
        boolean wasPlaying = eng.isPlaying();
        if (!wasPlaying) eng.play(cursor, s.countIn);
        long punch = wasPlaying ? Math.max(0, eng.position()) : cursor;
        if (!eng.startRecording(t, punch)) {
            note("couldn't open the microphone (or the phone is out of memory).");
            if (!wasPlaying) eng.stop();
            return;
        }
        armed = t;
        refreshAll();
        startTicking();
        if (!prefs.getBoolean("calHint", false) && s.tracks.size() > 1) {
            prefs.edit().putBoolean("calHint", true).apply();
            note("tip: run ••• → calibrate latency once so overdubs line up.");
        }
    }

    private void onUndo() {
        Track t = lastTake;
        if (t == null || eng.isRecording()) return;
        if (eng.isPlaying()) eng.stop();
        if (t.undo == null || t.undo.length == 0) {
            s.tracks.remove(t);
        } else {
            t.data = t.undo;
            t.computePeaks();
            t.invalidateAnalysis();
            t.dirty = true;
        }
        t.undo = null;
        lastTake = null;
        armed = null;
        save();
        rebuildRows();
        refreshAll();
        note("undone");
    }

    private void toggleTune() {
        if (eng.monitoring) {
            eng.stopMonitor();
        } else {
            if (!needMic()) return;
            if (eng.isRecording()) { note("the tuner already shows while recording a VOX track."); return; }
            applyEngineSettings();
            if (!eng.startMonitor()) { note("couldn't open the microphone"); return; }
            startTicking();
        }
        refreshAll();
    }

    // ========================================================= engine callbacks

    @Override public void onEngineStopped() {
        if (trackView != null) trackView.invalidate();
        if (!eng.monitoring) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recKey.level = 0;
        refreshAll();
        startTicking(); // let the VU needles fall back
        save();
    }

    @Override public void onTake(Track t) {
        lastTake = t;
        armed = null;
        save();
        refreshAll();
    }

    @Override public void onCalibrated(int ms) {
        if (calDlg != null) { calDlg.dismiss(); calDlg = null; }
        if (ms < 0) {
            note("couldn't hear the clicks. turn the volume up, unplug headphones and try again.");
        } else {
            prefs.edit().putInt("lat", ms).apply();
            note("latency set to " + ms + " ms");
        }
    }

    @Override public void onEngineError(String msg) {
        note(msg);
    }

    // =================================================================== dialogs

    private AlertDialog.Builder dlg(String title) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DeckDialog);
        if (title != null) {
            TextView t = Ui.text(this, 21, Ui.CREAM, Ui.serifBold);
            t.setText(title);
            t.setPadding(dp(24), dp(20), dp(24), dp(4));
            b.setCustomTitle(t);
        }
        return b;
    }

    /** Shows the dialog and puts its buttons and message in the deck fonts. */
    private AlertDialog show(AlertDialog.Builder b) {
        AlertDialog d = b.show();
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button btn = d.getButton(which);
            if (btn != null) {
                btn.setTypeface(Ui.sansBold);
                btn.setTextColor(Ui.AMBER);
                btn.setLetterSpacing(0.1f);
            }
        }
        View msg = d.findViewById(android.R.id.message);
        if (msg instanceof TextView) {
            ((TextView) msg).setTypeface(Ui.sans);
            ((TextView) msg).setTextColor(Ui.CREAM);
        }
        return d;
    }

    /** A list of choices in the deck fonts. */
    private AlertDialog list(String title, CharSequence[] items, IntConsumer on) {
        BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return items.length; }
            @Override public Object getItem(int i) { return items[i]; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                TextView t = cv instanceof TextView ? (TextView) cv : Ui.text(MainActivity.this, 16, Ui.CREAM, Ui.sans);
                t.setText(items[i]);
                t.setPadding(dp(24), dp(12), dp(24), dp(12));
                return t;
            }
        };
        return show(dlg(title).setAdapter(ad, (d, w) -> on.accept(w)));
    }

    private LinearLayout body() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(4), dp(24), dp(10));
        return l;
    }

    private ScrollView scroll(View v) {
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        return sv;
    }

    private TextView para(LinearLayout parent, String text, float sp, int color) {
        TextView t = Ui.text(this, sp, color, Ui.sans);
        t.setText(text);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dp(4), 0, 0);
        parent.addView(t);
        return t;
    }

    private void heading(LinearLayout parent, String text) {
        TextView t = Ui.text(this, 17, Ui.CREAM, Ui.serifBold);
        t.setText(text);
        t.setPadding(0, dp(16), 0, 0);
        parent.addView(t);
    }

    private void slider(LinearLayout parent, String label, int max, int val,
                        IntFunction<String> fmt, IntConsumer on) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, 0);
        LinearLayout head = new LinearLayout(this);
        TextView l = Ui.silk(this, label.toUpperCase(Locale.ROOT), 10.5f, Ui.CREAM);
        TextView v = Ui.text(this, 13, Ui.AMBER, Ui.sansBold);
        v.setText(fmt.apply(val));
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(v);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(val);
        sb.setProgressTintList(ColorStateList.valueOf(Ui.AMBER));
        sb.setThumbTintList(ColorStateList.valueOf(Ui.CREAM));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                v.setText(fmt.apply(p));
                on.accept(p);
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        box.addView(head);
        box.addView(sb);
        parent.addView(box);
    }

    private LinearLayout chipRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(8), 0, 0);
        parent.addView(r);
        return r;
    }

    private TextView addChip(LinearLayout row, String label, View.OnClickListener l) {
        TextView c = Ui.chip(this, label);
        c.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (row.getChildCount() > 0) lp.leftMargin = dp(6);
        row.addView(c, lp);
        return c;
    }

    private static String db(float v) {
        if (Math.abs(v) < 0.1f) return "0 dB";
        return String.format(Locale.US, "%+.1f dB", v);
    }

    private static String pct(int p) {
        return p == 0 ? "off" : p + "%";
    }

    void showFx(Track t) {
        LinearLayout l = body();
        slider(l, "volume", 150, Math.round(t.vol * 100), p -> p + "%", p -> t.vol = p / 100f);
        slider(l, "pan", 100, Math.round(t.pan * 50 + 50),
                p -> p == 50 ? "center" : (p < 50 ? "L " + (50 - p) * 2 : "R " + (p - 50) * 2),
                p -> t.pan = (p - 50) / 50f);
        slider(l, "low cut", 100, t.lowCut <= 0 ? 0 : (int) Math.round(Math.log(t.lowCut / 20.0) / Math.log(20) * 100),
                p -> p == 0 ? "off" : Math.round(20 * Math.pow(20, p / 100.0)) + " Hz",
                p -> t.lowCut = p == 0 ? 0 : (float) (20 * Math.pow(20, p / 100.0)));
        slider(l, "bass", 48, Math.round(t.bass * 2 + 24), p -> db((p - 24) / 2f), p -> t.bass = (p - 24) / 2f);
        slider(l, "treble", 48, Math.round(t.treble * 2 + 24), p -> db((p - 24) / 2f), p -> t.treble = (p - 24) / 2f);
        slider(l, "compression", 100, Math.round(t.comp * 100), MainActivity::pct, p -> t.comp = p / 100f);
        slider(l, "echo", 100, Math.round(t.echo / 0.8f * 100), MainActivity::pct, p -> t.echo = p / 100f * 0.8f);
        slider(l, "echo time (synced)", Dsp.DIV_NAMES.length - 1, t.echoDiv, p -> Dsp.DIV_NAMES[p], p -> t.echoDiv = p);
        slider(l, "echo repeats", 90, Math.round(t.echoFb * 100), p -> p + "%", p -> t.echoFb = p / 100f);
        slider(l, "reverb", 100, Math.round(t.rev * 100), MainActivity::pct, p -> t.rev = p / 100f);
        show(dlg(t.name)
                .setView(scroll(l))
                .setPositiveButton("DONE", null)
                .setNeutralButton("RESET", (d, w) -> { t.resetFx(); refreshAll(); showFx(t); })
                .setOnDismissListener(d -> { refreshAll(); save(); }));
    }

    private void showTempo() {
        LinearLayout l = body();
        slider(l, "tempo", 200, Math.round(s.bpm) - 40, p -> (p + 40) + " BPM", p -> { s.bpm = p + 40; refreshAll(); });
        LinearLayout row = chipRow(l);
        TextView[] tapRef = new TextView[1];
        tapRef[0] = addChip(row, "TAP TEMPO", v -> {
            long now = SystemClock.uptimeMillis();
            if (!taps.isEmpty() && now - taps.get(taps.size() - 1) > 2000) taps.clear();
            taps.add(now);
            while (taps.size() > 6) taps.remove(0);
            if (taps.size() >= 2) {
                double avg = (taps.get(taps.size() - 1) - taps.get(0)) / (double) (taps.size() - 1);
                s.bpm = (float) Math.max(40, Math.min(240, Math.round(60000.0 / avg)));
                refreshAll();
                tapRef[0].setText("TAP · " + fmtBpm(s.bpm));
            }
        });
        addChip(row, "×2", v -> { s.bpm = Math.min(240, s.bpm * 2); refreshAll(); note(fmtBpm(s.bpm) + " BPM"); });
        addChip(row, "÷2", v -> { s.bpm = Math.max(40, s.bpm / 2); refreshAll(); note(fmtBpm(s.bpm) + " BPM"); });
        para(l, "the metronome clicks on every " + (s.beatUnit == 8 ? "eighth" : "quarter")
                + " note at this tempo.", 12, Ui.DIM);
        show(dlg("tempo").setView(scroll(l)).setPositiveButton("DONE", null)
                .setOnDismissListener(d -> { refreshAll(); save(); }));
    }

    private static final int[][] SIGS = {{2, 4}, {3, 4}, {4, 4}, {5, 4}, {6, 4}, {7, 4}, {3, 8}, {5, 8}, {6, 8}, {7, 8}, {9, 8}, {12, 8}};

    private void showTimeSig() {
        CharSequence[] items = new CharSequence[SIGS.length];
        for (int i = 0; i < SIGS.length; i++) {
            String cur = (SIGS[i][0] == s.beatsPerBar && SIGS[i][1] == s.beatUnit) ? "   ●" : "";
            items[i] = SIGS[i][0] + "/" + SIGS[i][1] + cur;
        }
        list("time signature", items, w -> {
            s.beatsPerBar = SIGS[w][0];
            s.beatUnit = SIGS[w][1];
            refreshAll();
            save();
            note("metronome accents every " + s.beatsPerBar + " beats; count-in is one bar.");
        });
    }

    private void showKeyPicker() {
        CharSequence[] items = new CharSequence[26];
        items[0] = "circle of fifths…";
        items[1] = "no key";
        for (int i = 0; i < 12; i++) {
            items[2 + i * 2] = Analyzer.NOTES[i] + " major";
            items[3 + i * 2] = Analyzer.NOTES[i] + " minor";
        }
        list("key", items, w -> {
            if (w == 0) { showCircle(); return; }
            if (w == 1) s.keyRoot = -1;
            else { s.keyRoot = (w - 2) / 2; s.keyMinor = (w - 2) % 2 == 1; }
            refreshAll();
            save();
            if (s.keyRoot >= 0) showKeyHelper(s.keyRoot, s.keyMinor);
        });
    }

    /** Interactive circle of fifths; the panel below follows whichever key is tapped. */
    private void showCircle() {
        LinearLayout l = body();
        Deck.Fifths circle = new Deck.Fifths(this);
        int root0 = s.keyRoot >= 0 ? s.keyRoot : 0;
        boolean minor0 = s.keyRoot >= 0 && s.keyMinor;
        circle.pos = Deck.Fifths.posOf(root0, minor0);
        circle.minor = minor0;
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(6);
        l.addView(circle, clp);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        l.addView(info);
        int[] sel = {root0, minor0 ? 1 : 0};
        Runnable fill = () -> {
            info.removeAllViews();
            int r = sel[0];
            boolean m = sel[1] == 1;
            heading(info, Analyzer.keyName(r, m));
            int major = m ? (r + 3) % 12 : r;
            int relMinor = (major + 9) % 12;
            para(info, "next door on the circle:  IV " + Analyzer.NOTES[(major + 5) % 12] + "   ·   V "
                    + Analyzer.NOTES[(major + 7) % 12] + "\nrelative " + (m ? "major: " + Analyzer.NOTES[major]
                    : "minor: " + Analyzer.NOTES[relMinor] + "m")
                    + "\nkeys next to each other share six of seven notes, so moving one step is the smoothest key change.",
                    13, Ui.DIM);
            LinearLayout row = chipRow(info);
            addChip(row, "USE FOR JAM", v -> {
                s.keyRoot = r;
                s.keyMinor = m;
                refreshAll();
                save();
                note("jam key set to " + s.keyLabel());
            });
            fitsKey(info, r, m);
        };
        circle.onPick = (r, m) -> { sel[0] = r; sel[1] = m ? 1 : 0; fill.run(); };
        fill.run();
        show(dlg("circle of fifths").setView(scroll(l)).setPositiveButton("DONE", null));
    }

    /** Scale, diatonic chords and progressions for a key. */
    private void showKeyHelper(int root, boolean minor) {
        LinearLayout l = body();
        fitsKey(l, root, minor);
        show(dlg(Analyzer.keyName(root, minor)).setView(scroll(l)).setPositiveButton("DONE", null)
                .setNeutralButton("CIRCLE OF FIFTHS", (d, w) -> showCircle()));
    }

    private void fitsKey(LinearLayout l, int root, boolean minor) {
        heading(l, "scale");
        para(l, Theory.scaleNames(root, minor), 15, Ui.AMBER);
        heading(l, "chords in the key");
        para(l, TextUtils.join("\n", Theory.diatonic(root, minor)), 14, Ui.CREAM);
        heading(l, "progressions to try");
        para(l, TextUtils.join("\n", Theory.progressions(root, minor)), 14, Ui.CREAM);
    }

    private void showSettings() {
        LinearLayout l = body();
        Switch ci = new Switch(this);
        ci.setText("one-bar count-in before recording");
        ci.setTypeface(Ui.sans);
        ci.setTextColor(Ui.CREAM);
        ci.setChecked(s.countIn);
        ci.setOnCheckedChangeListener((b, c) -> s.countIn = c);
        ci.setPadding(0, dp(12), 0, dp(4));
        l.addView(ci);
        slider(l, "click volume", 100, Math.round(s.metroVol * 100), p -> p + "%", p -> s.metroVol = p / 100f);
        slider(l, "input boost", 24, prefs.getInt("boost", 0), p -> "+" + p + " dB",
                p -> prefs.edit().putInt("boost", p).apply());
        slider(l, "latency compensation", 400, prefs.getInt("lat", 120), p -> p + " ms",
                p -> prefs.edit().putInt("lat", p).apply());
        para(l, "shifts new takes earlier so they line up with what you heard. calibrate measures it for you.", 12, Ui.DIM);
        show(dlg("settings")
                .setView(scroll(l))
                .setPositiveButton("DONE", null)
                .setNeutralButton("CALIBRATE", (d, w) -> calibrate())
                .setOnDismissListener(d -> { refreshAll(); save(); }));
    }

    private void calibrate() {
        if (!needMic()) return;
        show(dlg("calibrate latency")
                .setMessage("unplug headphones, turn media volume up and stay quiet. "
                        + "Roughhouse will play a few clicks and listen for them (about 3 seconds).")
                .setPositiveButton("START", (d, w) -> {
                    if (eng.monitoring) eng.stopMonitor();
                    applyEngineSettings();
                    if (!eng.calibrate()) { note("couldn't open the microphone"); return; }
                    calDlg = show(dlg(null).setMessage("listening…").setCancelable(false));
                    refreshAll();
                    startTicking();
                    h.postDelayed(() -> eng.stop(), 3400);
                })
                .setNegativeButton("CANCEL", null));
    }

    private void showLoopMenu() {
        list("loop region", new CharSequence[]{"loop starts here", "loop ends here", "loop the whole jam"}, w -> {
            long p = Math.max(0, playhead());
            if (w == 0) {
                s.loopIn = p;
                if (s.loopOut > 0 && s.loopOut <= p) s.loopOut = -1;
            } else if (w == 1) {
                if (p > s.loopIn) s.loopOut = p; else note("loop end must be after the start");
            } else {
                s.loopIn = 0;
                s.loopOut = -1;
            }
            s.loop = true;
            refreshAll();
        });
    }

    void showTrackMenu(Track t) {
        if (eng.isRecording()) return;
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        items.add("open track view");
        acts.add(() -> openTrack(t));
        if (t.analysis != null) {
            items.add("analysis…");
            acts.add(() -> showAnalysisMenu(t));
            items.add(t.showNotes ? "hide notes on the lane" : "show notes on the lane");
            acts.add(() -> { t.showNotes = !t.showNotes; save(); refreshAll(); });
        } else if (!t.isEmpty()) {
            items.add("analyze notes, key, tempo and chords");
            acts.add(() -> analyzeTrack(t, A_ALL));
        }
        items.add(t.vocal ? "pitch monitor: on (vocal track)" : "pitch monitor: off");
        acts.add(() -> { t.vocal = !t.vocal; save(); refreshAll(); note(t.vocal ? "the tuner will show while recording this track." : "pitch monitor off for this track."); });
        items.add("rename");
        acts.add(() -> prompt("rename track", t.name, v -> { t.name = v; save(); refreshAll(); }));
        items.add("duplicate");
        acts.add(() -> {
            Track c = new Track(s.nextId(), t.name + " copy");
            c.copySettingsFrom(t);
            c.vocal = t.vocal;
            try {
                c.data = Arrays.copyOf(t.data, t.data.length);
            } catch (OutOfMemoryError e) {
                note("not enough memory to duplicate this track.");
                return;
            }
            c.analysis = t.analysis;
            c.analysisDirty = t.analysis != null;
            c.showNotes = t.showNotes;
            c.dirty = true;
            c.computePeaks();
            s.tracks.add(s.tracks.indexOf(t) + 1, c);
            save();
            rebuildRows();
            refreshAll();
        });
        items.add("clear audio");
        acts.add(() -> {
            if (eng.isPlaying()) eng.stop();
            t.undo = t.data;
            t.data = new short[0];
            t.dirty = true;
            t.invalidateAnalysis();
            t.computePeaks();
            lastTake = t;
            save();
            refreshAll();
        });
        items.add("delete track");
        acts.add(() -> show(dlg(null).setMessage("delete " + t.name + "?")
                .setPositiveButton("DELETE", (d2, w2) -> {
                    s.tracks.remove(t);
                    if (lastTake == t) lastTake = null;
                    if (armed == t) armed = null;
                    save();
                    rebuildRows();
                    refreshAll();
                })
                .setNegativeButton("CANCEL", null)));
        list(t.name, items.toArray(new CharSequence[0]), w -> acts.get(w).run());
    }

    // analysis sections (bit flags so any mix can be shown together)
    static final int A_TEMPO = 1, A_KEY = 2, A_MELODY = 4, A_CHORDS = 8, A_RANGE = 16, A_FITS = 32, A_ALL = 63;

    private void showAnalysisMenu(Track t) {
        CharSequence[] items = {"tempo", "key", "melody", "chords by bar", "range and tuning",
                "chords that fit the key", "see all analysis", "analyze again"};
        int[] map = {A_TEMPO, A_KEY, A_MELODY, A_CHORDS, A_RANGE, A_FITS, A_ALL, -1};
        list("analysis", items, w -> {
            if (map[w] < 0) analyzeTrack(t, A_ALL);
            else showAnalysis(t, map[w]);
        });
    }

    private void showMenu() {
        list(null, new CharSequence[]{"export jam…", "import audio…", "circle of fifths", "settings",
                "calibrate latency", "rename jam", "delete jam"}, w -> {
            switch (w) {
                case 0: exportJam(); break;
                case 1: pickImport(); break;
                case 2: showCircle(); break;
                case 3: showSettings(); break;
                case 4: calibrate(); break;
                case 5: renameJam(); break;
                default: confirmDeleteJam(s.dir, s.name); break;
            }
        });
    }

    private interface StrCb { void on(String v); }

    private void prompt(String title, String value, StrCb cb) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(value);
        e.setSelectAllOnFocus(true);
        e.setTextColor(Ui.CREAM);
        e.setTypeface(Ui.serifItalic);
        e.setTextSize(18);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(22), dp(8), dp(22), 0);
        f.addView(e);
        AlertDialog ad = show(dlg(title).setView(f)
                .setPositiveButton("OK", (d, w) -> {
                    String v = e.getText().toString().trim();
                    if (!v.isEmpty()) cb.on(v);
                })
                .setNegativeButton("CANCEL", null));
        if (ad.getWindow() != null) ad.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        e.requestFocus();
    }

    private void renameJam() {
        prompt("rename jam", s.name, v -> { s.name = v; save(); refreshAll(); });
    }

    // ================================================================= analysis

    private AlertDialog busy(String msg) {
        return show(dlg(null).setMessage(msg).setCancelable(false));
    }

    private void analyzeTrack(Track t, int which) {
        if (eng.isPlaying()) eng.stop();
        AlertDialog wait = busy("listening to " + t.name + "…");
        final short[] data = t.data;
        final double bf = s.beatFrames(SR);
        final int bpb = Math.max(1, s.beatsPerBar);
        new Thread(() -> {
            Analyzer.Full f;
            try {
                f = Analyzer.analyzeTrack(data, SR, bf, bpb);
            } catch (OutOfMemoryError e) {
                f = null;
            }
            final Analyzer.Full res = f;
            h.post(() -> {
                wait.dismiss();
                if (res == null) { note("not enough memory to analyze this track."); return; }
                t.analysis = res;
                t.analysisDirty = true;
                t.showNotes = !res.notes.isEmpty();
                save();
                refreshAll();
                if (trackView != null && trackView.t == t) trackView.invalidate();
                showAnalysis(t, which);
            });
        }, "rh-analyze").start();
    }

    private void showAnalysis(Track t, int which) {
        Analyzer.Full f = t.analysis;
        if (f == null) return;
        LinearLayout l = body();
        int root = s.keyRoot >= 0 ? s.keyRoot : f.base.keyRoot;
        boolean minor = s.keyRoot >= 0 ? s.keyMinor : f.base.minor;
        boolean all = which == A_ALL;
        String title = t.name;

        if ((which & A_TEMPO) != 0) {
            if (all) heading(l, "tempo"); else title = "tempo · " + t.name;
            if (f.base.bpm > 0) {
                para(l, "about " + fmtBpm(f.base.bpm) + " BPM" + (f.base.bpmConfidence < 0.3f ? " (low confidence)" : "")
                        + ". this jam is at " + fmtBpm(s.bpm) + " BPM.", 14, Ui.CREAM);
                tempoChoices(l, f.base.bpm);
            } else {
                para(l, "no steady pulse found. it needs a few seconds of rhythmic playing.", 14, Ui.DIM);
            }
        }
        if ((which & A_KEY) != 0) {
            if (all) heading(l, "key"); else title = "key · " + t.name;
            if (f.base.keyRoot >= 0) {
                para(l, Analyzer.keyName(f.base.keyRoot, f.base.minor)
                        + (f.base.keyConfidence < 0.25f ? " (it could also be the relative "
                        + (f.base.minor ? "major" : "minor") + ")" : ""), 14, Ui.CREAM);
                LinearLayout row = chipRow(l);
                addChip(row, "USE FOR JAM", v -> {
                    s.keyRoot = f.base.keyRoot;
                    s.keyMinor = f.base.minor;
                    refreshAll();
                    save();
                    note("jam key set to " + s.keyLabel());
                });
            } else {
                para(l, "not enough pitched material to tell.", 14, Ui.DIM);
            }
        }
        if ((which & A_RANGE) != 0) {
            if (all) heading(l, "range and tuning"); else title = "range · " + t.name;
            if (f.notes.isEmpty()) {
                para(l, "no single-note melody found, so there's no range to show.", 14, Ui.DIM);
            } else {
                para(l, Pitch.noteName(f.low) + " to " + Pitch.noteName(f.high) + " (" + rangeText(f.high - f.low) + ")", 14, Ui.CREAM);
                para(l, Math.abs(f.avgCents) < 6 ? "in tune on average."
                        : String.format(Locale.US, "%d cents %s on average.", Math.round(Math.abs(f.avgCents)),
                        f.avgCents > 0 ? "sharp" : "flat"), 13, Math.abs(f.avgCents) < 6 ? Ui.DIM : Ui.AMBER);
            }
        }
        if ((which & A_MELODY) != 0) {
            if (all) heading(l, "melody"); else title = "melody · " + t.name;
            if (f.notes.isEmpty()) {
                para(l, "no single-note melody found. this works best on one voice or instrument at a time.", 14, Ui.DIM);
            } else {
                MelodyView mv = new MelodyView(this, f, root, minor, s.beatFrames(SR), Math.max(1, s.beatsPerBar));
                int outCount = 0;
                if (root >= 0) for (Analyzer.Note n : f.notes) if (!Theory.inKey(n.midi, root, minor)) outCount++;
                String summary = f.notes.size() + " notes in " + mv.totalPhrases
                        + (mv.totalPhrases == 1 ? " phrase" : " phrases");
                int repeated = mv.totalPhrases - mv.phrases.size();
                if (repeated > 0) summary += ", " + repeated + (repeated == 1 ? " of them a repeat." : " of them repeats.");
                else summary += ".";
                if (root >= 0) summary += outCount == 0 ? " every note is in " + Analyzer.keyName(root, minor) + "."
                        : " " + outCount + (outCount == 1 ? " note falls" : " notes fall") + " outside "
                        + Analyzer.keyName(root, minor) + " (in red).";
                para(l, summary, 14, Ui.CREAM);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
                mlp.topMargin = dp(10);
                l.addView(mv, mlp);
                if (mv.phrases.size() > MelodyView.MAX_ROWS) {
                    para(l, "showing the first " + MelodyView.MAX_ROWS + " of " + mv.phrases.size()
                            + " different phrases. the track view has all of them.", 12, Ui.DIM);
                }
                pendingPhrase = mv;
                LinearLayout row = chipRow(l);
                addChip(row, t.showNotes ? "HIDE NOTES ON LANE" : "SHOW NOTES ON LANE", v -> {
                    t.showNotes = !t.showNotes;
                    ((TextView) v).setText(t.showNotes ? "HIDE NOTES ON LANE" : "SHOW NOTES ON LANE");
                    save();
                    refreshAll();
                });
            }
        }
        if ((which & A_CHORDS) != 0) {
            if (all) heading(l, "chords by bar"); else title = "chords · " + t.name;
            if (f.chords.isEmpty()) para(l, "no clear chords. that's normal for a single melody line.", 14, Ui.DIM);
            else para(l, chordText(f), 14, Ui.CREAM);
        }
        if ((which & A_FITS) != 0) {
            if (root >= 0) {
                if (all) heading(l, "writing in " + Analyzer.keyName(root, minor));
                else title = Analyzer.keyName(root, minor);
                fitsKey(l, root, minor);
            } else {
                para(l, "no key yet. set one with the KEY button or analyze a track with more pitched material.", 14, Ui.DIM);
            }
        }
        if ((which & (A_MELODY | A_CHORDS)) != 0) {
            para(l, "bars and beats follow the jam's tempo and time signature.", 12, Ui.DIM);
        }
        AlertDialog sheet = show(dlg(all ? t.name : title).setView(scroll(l)).setPositiveButton("DONE", null)
                .setNeutralButton("TRACK VIEW", (d, w) -> openTrack(t)));
        if (pendingPhrase != null) {
            pendingPhrase.onPhrase = frame -> {
                sheet.dismiss();
                seekTo(Math.max(0, frame - (long) (s.beatFrames(SR) / 2)));
                openTrack(t);
                if (trackView != null) trackView.focusOn(frame);
            };
            pendingPhrase = null;
        }
    }

    private MelodyView pendingPhrase;

    private void tempoChoices(LinearLayout l, float bpm) {
        LinearLayout row = chipRow(l);
        for (float b : new float[]{bpm, bpm * 2, bpm / 2}) {
            if (b < 40 || b > 240) continue;
            float use = Math.round(b * 2) / 2f;
            addChip(row, "USE " + fmtBpm(use), v -> {
                s.bpm = use;
                refreshAll();
                save();
                note("tempo set to " + fmtBpm(use) + " BPM");
            });
        }
    }

    private static String rangeText(int semis) {
        int oct = semis / 12, rest = semis % 12;
        if (oct == 0) return semis + " semitones";
        return oct + (oct == 1 ? " octave" : " octaves") + (rest > 0 ? " + " + rest : "");
    }

    private static String chordText(Analyzer.Full f) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < f.chords.size()) {
            Analyzer.Chord c = f.chords.get(i);
            int j = i;
            while (j + 1 < f.chords.size() && f.chords.get(j + 1).name.equals(c.name)
                    && f.chords.get(j + 1).bar == f.chords.get(j).bar + 1) j++;
            int a = c.bar + 1, z = f.chords.get(j).bar + 1;
            if (b.length() > 0) b.append(i % 4 == 0 ? "\n" : "    ");
            b.append(a == z ? "bar " + a : "bars " + a + "–" + z).append(":  ").append(c.name);
            i = j + 1;
        }
        return b.toString();
    }

    // =============================================================== track view

    private TrackView trackView;
    private android.app.Dialog trackDlg;

    /** Full-screen view of one track: notes, chords, bars and waveform, with zoom and transport. */
    void openTrack(Track t) {
        if (eng.isRecording()) return;
        android.app.Dialog d = new android.app.Dialog(this, R.style.DeckFull);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BODY);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(10), dp(10), dp(4));
        TextView back = Ui.chip(this, "BACK");
        back.setOnClickListener(v -> d.dismiss());
        top.addView(back);
        TextView name = Ui.text(this, 20, Ui.CREAM, Ui.serifItalic);
        name.setText(t.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(12), 0, dp(8), 0);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView m = Ui.chip(this, "M"), so = Ui.chip(this, "S"), fx = Ui.chip(this, "FX");
        Runnable chips = () -> {
            Ui.setChip(m, t.mute, Ui.BLUE);
            Ui.setChip(so, t.solo, Ui.YELLOW);
            Ui.setChip(fx, t.fxActive(), Ui.AMBER);
        };
        m.setOnClickListener(v -> { t.mute = !t.mute; chips.run(); refreshAll(); });
        so.setOnClickListener(v -> { t.solo = !t.solo; chips.run(); refreshAll(); });
        fx.setOnClickListener(v -> showFx(t));
        chips.run();
        top.addView(m, gap(0));
        top.addView(so, gap(6));
        top.addView(fx, gap(6));
        col.addView(top);

        TextView info = Ui.text(this, 12, Ui.DIM, Ui.sans);
        info.setPadding(dp(14), dp(2), dp(14), dp(6));
        col.addView(info);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(10), 0, dp(10), dp(8));
        TextView an = Ui.chip(this, t.analysis != null ? "ANALYSIS" : "ANALYZE");
        an.setOnClickListener(v -> {
            if (t.analysis != null) showAnalysisMenu(t);
            else if (!t.isEmpty()) analyzeTrack(t, A_ALL);
        });
        TextView fit = Ui.chip(this, "FIT");
        actions.addView(an, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(fit, weighted(1, 6));
        col.addView(actions);

        TrackView tv = new TrackView(this, t);
        fit.setOnClickListener(v -> tv.fit());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, 0, 1);
        tlp.leftMargin = dp(8);
        tlp.rightMargin = dp(8);
        col.addView(tv, tlp);

        TextView hint = Ui.text(this, 11, Ui.FAINT, Ui.sans);
        hint.setText("pinch to zoom and drag to scroll. tap to move the playhead.");
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(6));
        col.addView(hint);

        LinearLayout keys = new LinearLayout(this);
        keys.setPadding(dp(8), dp(4), dp(8), dp(12));
        keys.setBackgroundColor(Ui.PANEL);
        Deck.Key rw = new Deck.Key(this, Deck.Key.REW), pl = new Deck.Key(this, Deck.Key.PLAY), st = new Deck.Key(this, Deck.Key.STOP);
        rw.setOnClickListener(v -> { seekTo(0); tv.invalidate(); });
        pl.setOnClickListener(v -> { onPlay(); pl.set(eng.isPlaying(), eng.isPlaying()); });
        st.setOnClickListener(v -> { onStopKey(); pl.set(false, false); tv.invalidate(); });
        keys.addView(rw, keyLp(0));
        keys.addView(pl, keyLp(6));
        keys.addView(st, keyLp(6));
        col.addView(keys);

        Runnable refreshInfo = () -> {
            Analyzer.Full f = t.analysis;
            an.setText(f != null ? "ANALYSIS" : "ANALYZE");
            if (f == null) {
                info.setText(t.isEmpty() ? "this track is empty." : "not analyzed yet. tap ANALYZE to find its notes and chords.");
                return;
            }
            StringBuilder b = new StringBuilder();
            if (f.base.keyRoot >= 0) b.append(Analyzer.keyName(f.base.keyRoot, f.base.minor));
            if (f.base.bpm > 0) b.append(b.length() > 0 ? "  ·  " : "").append("about ").append(fmtBpm(f.base.bpm)).append(" BPM");
            if (f.low > 0) b.append(b.length() > 0 ? "  ·  " : "").append("range ").append(Pitch.noteName(f.low)).append(" to ").append(Pitch.noteName(f.high));
            b.append(b.length() > 0 ? "  ·  " : "").append(f.notes.size()).append(" notes");
            info.setText(b.toString());
        };
        refreshInfo.run();
        trackRefresh = () -> { refreshInfo.run(); chips.run(); pl.set(eng.isPlaying(), eng.isPlaying()); };

        d.setContentView(col);
        d.setOnDismissListener(x -> { trackView = null; trackDlg = null; trackRefresh = null; refreshAll(); });
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        trackView = tv;
        trackDlg = d;
        d.show();
    }

    private Runnable trackRefresh;

    // ==================================================================== import

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (eng.isPlaying()) eng.stop();
        AlertDialog wait = busy("loading audio…");
        final boolean jamWasEmpty = s.lengthFrames() == 0;
        new Thread(() -> {
            Importer.Decoded dec = null;
            Analyzer.Result an = null;
            String err = null;
            try {
                dec = Importer.decode(this, uri, null);
                an = Analyzer.analyze(dec.data, SR);
            } catch (IOException e) {
                err = e.getMessage();
            } catch (OutOfMemoryError e) {
                err = "that file is too long to load on this phone.";
            }
            final Importer.Decoded d = dec;
            final Analyzer.Result r = an;
            final String error = err;
            h.post(() -> {
                wait.dismiss();
                if (error != null) { note(error); return; }
                Track t = new Track(s.nextId(), d.name);
                t.data = d.data;
                t.dirty = true;
                t.computePeaks();
                t.undo = new short[0];
                s.tracks.add(t);
                lastTake = t;
                armed = null;
                save();
                rebuildRows();
                refreshAll();
                offerDetected(t, r, jamWasEmpty);
            });
        }, "rh-import").start();
    }

    /** Applies (empty jam) or offers (existing jam) the tempo/key found in an import. */
    private void offerDetected(Track t, Analyzer.Result r, boolean auto) {
        boolean hasTempo = r != null && r.bpm > 0, hasKey = r != null && r.keyRoot >= 0;
        if (!hasTempo && !hasKey) { note("imported " + t.name + "."); return; }
        if (auto) {
            StringBuilder b = new StringBuilder("imported " + t.name + ".");
            if (hasTempo) { s.bpm = Math.max(40, Math.min(240, r.bpm)); b.append(" tempo set to ").append(fmtBpm(s.bpm)).append(" BPM"); }
            if (hasKey) { s.keyRoot = r.keyRoot; s.keyMinor = r.minor; b.append(hasTempo ? ", key " : " key set to ").append(s.keyLabel()); }
            b.append(". tap BPM or KEY to adjust.");
            refreshAll();
            save();
            note(b.toString());
            return;
        }
        LinearLayout l = body();
        if (hasTempo) {
            heading(l, "tempo");
            para(l, "about " + fmtBpm(r.bpm) + " BPM (jam is " + fmtBpm(s.bpm) + ")", 14, Ui.CREAM);
            tempoChoices(l, r.bpm);
        }
        if (hasKey) {
            heading(l, "key");
            para(l, Analyzer.keyName(r.keyRoot, r.minor) + " (jam is " + s.keyLabel() + ")", 14, Ui.CREAM);
            LinearLayout row = chipRow(l);
            addChip(row, "USE FOR JAM", v -> {
                s.keyRoot = r.keyRoot;
                s.keyMinor = r.minor;
                refreshAll();
                save();
                note("jam key set to " + s.keyLabel());
            });
        }
        show(dlg("found in " + t.name).setView(scroll(l)).setPositiveButton("KEEP CURRENT", null));
    }

    // ===================================================================== jams

    private void openJam(Jam nj) {
        if (eng.isPlaying()) eng.stop();
        s = nj;
        eng.s = s;
        armed = null;
        lastTake = null;
        cursor = 0;
        prefs.edit().putString("last", s.dir.getName()).apply();
        rebuildRows();
        refreshAll();
    }

    /** Saves the current jam, or throws it away if nothing was recorded. */
    private void leaveCurrent() {
        if (eng.isPlaying()) eng.stop();
        if (s.lengthFrames() == 0) Jam.deleteDir(s.dir);
        else save();
    }

    private void newJam() {
        if (s.lengthFrames() == 0 && s.tracks.isEmpty()) { note("you're already on a fresh jam."); return; }
        leaveCurrent();
        openJam(Jam.create(root));
        note("new jam");
    }

    private void showJams() {
        if (eng.isPlaying()) eng.stop();
        save();
        File[] ds = root.listFiles(File::isDirectory);
        if (ds == null) ds = new File[0];
        Arrays.sort(ds, (a, b) -> b.getName().compareTo(a.getName()));
        ArrayList<Jam.Info> infos = new ArrayList<>();
        for (File d : ds) {
            Jam.Info i = Jam.info(d);
            if (i == null) {
                if (!d.equals(s.dir)) Jam.deleteDir(d);
                continue;
            }
            if (i.len == 0 && !d.equals(s.dir)) continue;
            infos.add(i);
        }
        if (infos.isEmpty()) { note("no saved jams yet."); return; }
        CharSequence[] labels = new CharSequence[infos.size()];
        for (int k = 0; k < infos.size(); k++) {
            Jam.Info i = infos.get(k);
            long sec = i.len / SR;
            labels[k] = (i.dir.equals(s.dir) ? "● " : "") + i.name + "\n"
                    + String.format(Locale.US, "%d:%02d", sec / 60, sec % 60) + "  ·  " + i.tracks
                    + (i.tracks == 1 ? " track" : " tracks");
        }
        AlertDialog d = list("jams", labels, w -> openJamDir(infos.get(w).dir));
        if (d.getListView() != null) {
            d.getListView().setOnItemLongClickListener((parent, view, pos, id) -> {
                d.dismiss();
                Jam.Info i = infos.get(pos);
                confirmDeleteJam(i.dir, i.name);
                return true;
            });
        }
        note("long-press a jam to delete it.");
    }

    private void openJamDir(File dir) {
        if (dir.equals(s.dir)) return;
        Jam nj = Jam.load(dir);
        if (nj == null) { note("couldn't open that jam."); return; }
        leaveCurrent();
        openJam(nj);
    }

    private void confirmDeleteJam(File dir, String name) {
        show(dlg(null).setMessage("delete \"" + name + "\" and all its tracks?")
                .setPositiveButton("DELETE", (d, w) -> {
                    boolean current = dir.equals(s.dir);
                    if (current && eng.isPlaying()) eng.stop();
                    Jam.deleteDir(dir);
                    if (current) openJam(Jam.create(root));
                    note("deleted");
                })
                .setNegativeButton("CANCEL", null));
    }

    // ==================================================================== export

    private void exportJam() {
        if (s.lengthFrames() == 0) { note("nothing to export yet."); return; }
        Exporter.Format[] fs = Exporter.Format.values();
        CharSequence[] items = new CharSequence[fs.length];
        for (int i = 0; i < fs.length; i++) items[i] = fs[i].label + "\n" + fs[i].hint;
        list("export jam as", items, w -> runExport(fs[w]));
    }

    private void runExport(Exporter.Format fmt) {
        if (eng.isPlaying()) eng.stop();
        save();
        AlertDialog wait = busy("rendering " + fmt.ext.toUpperCase(Locale.ROOT) + "…");
        final Jam cur = s;
        new Thread(() -> {
            try {
                Uri u = Exporter.export(this, cur, fmt, f -> h.post(() -> wait.setMessage(
                        "rendering " + fmt.ext.toUpperCase(Locale.ROOT) + "…  " + Math.round(f * 100) + "%")));
                h.post(() -> {
                    wait.dismiss();
                    note("saved to Music/Roughhouse");
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(fmt.mime);
                    send.putExtra(Intent.EXTRA_STREAM, u);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "share jam"));
                });
            } catch (Exception e) {
                h.post(() -> {
                    wait.dismiss();
                    note("export failed: " + e.getMessage());
                });
            } catch (OutOfMemoryError e) {
                h.post(() -> {
                    wait.dismiss();
                    note("export failed: out of memory.");
                });
            }
        }, "rh-export").start();
    }

    // =================================================================== helpers

    private void save() {
        if (s == null) return;
        if (s.lengthFrames() == 0 && s.tracks.isEmpty()) return; // don't litter empty jams
        try {
            s.save();
        } catch (IOException e) {
            note("save failed: " + e.getMessage());
        }
    }
}
