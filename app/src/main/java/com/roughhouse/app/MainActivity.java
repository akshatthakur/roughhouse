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
    private static final int REQ_IMPORT = 7, REQ_BACKUP = 8;
    static final String ACTION_RECORD_NOW = "com.roughhouse.app.RECORD_NOW";
    private boolean pendingQuickRecord;
    /** session mode shows everything; scratch mode keeps to the basics. */
    boolean session;
    private TextView modeLabel;

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
    private TextView lyricStrip;
    private java.util.TreeMap<Integer, String> lyricPins = new java.util.TreeMap<>();
    private final ArrayList<Row> rows = new ArrayList<>();

    private Track armed;
    private Runnable undoAction;   // reverses the last take, import, edit or bounce
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
        session = prefs.getBoolean("session", false); // fresh installs start in scratch mode
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
        applyMode();
        if (ACTION_RECORD_NOW.equals(getIntent() == null ? null : getIntent().getAction())) {
            h.post(this::quickRecord);
        } else if (!hasMic()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_RECORD_NOW.equals(intent.getAction())) h.post(this::quickRecord);
    }

    void setMode(boolean on) {
        session = on;
        prefs.edit().putBoolean("session", on).apply();
        applyMode();
        note(on ? "session mode: everything is on. tap SESSION MODE to go back to scratch."
                : "scratch mode: just the basics. tap SCRATCH MODE for the full set.");
    }

    /** Shows or hides the session-only controls. */
    private void applyMode() {
        modeLabel.setText(session ? "SESSION MODE  ·  TAP FOR SCRATCH" : "SCRATCH MODE  ·  TAP FOR SESSION");
        int vis = session ? View.VISIBLE : View.GONE;
        sigChip.setVisibility(vis);
        keyChip.setVisibility(vis);
        tuneChip.setVisibility(vis);
        if (!session && eng.monitoring) eng.stopMonitor();
        if (!session && trackDlg != null) trackDlg.dismiss();
        refreshAll();
    }

    /** "record now" shortcut: fresh jam, recording straight away (no count-in). */
    private void quickRecord() {
        if (!hasMic()) {
            pendingQuickRecord = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        if (eng.isRecording()) return;
        if (eng.isPlaying()) eng.stop();
        if (s.lengthFrames() > 0 || !s.tracks.isEmpty()) {
            leaveCurrent();
            openJam(Jam.create(root));
        }
        Track t = addTrack();
        armed = t;
        applyEngineSettings();
        eng.play(0, false);
        if (!eng.startRecording(t, 0)) {
            eng.stop();
            note("couldn't open the microphone");
            return;
        }
        tapeStarted();
        refreshAll();
        startTicking();
        note("recording a new jam. press REC or STOP when you're done.");
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (pendingQuickRecord && hasMic()) {
            pendingQuickRecord = false;
            quickRecord();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        Ui.density = getResources().getDisplayMetrics().density;
        boolean noteShown = noteBar != null && noteBar.getVisibility() == View.VISIBLE;
        CharSequence noteText = noteShown ? noteBar.getText() : null;
        setContentView(buildUi());
        rebuildRows();
        applyMode();
        if (noteShown) note(noteText.toString());
        h.removeCallbacks(tick);
        ticking = false;
        if (eng.isPlaying() || eng.monitoring) startTicking();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // recording and playback keep going in the background (RecorderService keeps the app alive);
        // only the tuner, which is just for looking at, stops
        if (eng.monitoring) { eng.stopMonitor(); refreshAll(); }
        save();
    }

    @Override
    protected void onDestroy() {
        if (eng.isPlaying()) eng.stop();
        tapeEnded();
        RecorderService.stopHook = null;
        save();
        super.onDestroy();
    }

    // ============================================================ tape running

    private android.media.AudioManager audio;
    private android.media.AudioFocusRequest focus;
    private boolean noisyRegistered;
    private final android.content.BroadcastReceiver noisy = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            if (android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) {
                interrupt("headphones unplugged");
            }
        }
    };

    /** Call whenever playback or recording starts (or a take ends but playback continues). */
    private void tapeStarted() {
        if (!eng.isPlaying()) return;
        RecorderService.stopHook = () -> { if (eng.isPlaying()) eng.stop(); };
        RecorderService.start(this, s.name, eng.isRecording());
        if (audio == null) audio = getSystemService(android.media.AudioManager.class);
        if (audio != null && focus == null) {
            focus = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == android.media.AudioManager.AUDIOFOCUS_LOSS
                                || change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            interrupt("another app (or a call) took over the audio");
                        }
                    }, h)
                    .build();
            audio.requestAudioFocus(focus);
        }
        if (!noisyRegistered) {
            registerReceiver(noisy, new android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
    }

    /** Call when the tape stops. */
    private void tapeEnded() {
        RecorderService.stop(this);
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
        focus = null;
        if (noisyRegistered) {
            try { unregisterReceiver(noisy); } catch (IllegalArgumentException ignored) {}
            noisyRegistered = false;
        }
    }

    /** A call, another app or unplugged headphones: stop cleanly and keep the take. */
    private void interrupt(String why) {
        if (!eng.isPlaying()) return;
        boolean wasRecording = eng.isRecording();
        eng.stop();
        note("stopped: " + why + "." + (wasRecording ? " your take was saved." : ""));
    }

    private void askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false)
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
        }
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
        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
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
        modeLabel = Ui.silk(this, "", 7.5f, Ui.AMBER);
        modeLabel.setPadding(0, dp(2), 0, dp(2));
        modeLabel.setOnClickListener(v -> setMode(!session));
        brand.addView(modeLabel);
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

        // --- deck panel: cassette, counter + meters, settings chips
        LinearLayout deck = new LinearLayout(this);
        deck.setOrientation(LinearLayout.VERTICAL);
        deck.setPadding(dp(8), dp(8), dp(8), dp(8));
        Ui.panel(deck);
        cassette = new Deck.Cassette(this);
        cassette.setOnClickListener(v -> renameJam());
        deck.addView(cassette, new LinearLayout.LayoutParams(-1, dp(land ? 124 : 146)));

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

        // --- timeline for the whole jam, lined up with the lanes below
        LinearLayout rulerRow = new LinearLayout(this);
        rulerRow.setPadding(dp(8 + 6), dp(8), dp(8 + 6), dp(2));
        rulerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView rl = Ui.silk(this, "BAR", 7.5f, Ui.FAINT);
        rl.setGravity(Gravity.CENTER);
        rulerRow.addView(rl, new LinearLayout.LayoutParams(dp(28), -2));
        ruler = new Ruler(this);
        ruler.setContentDescription("timeline: tap or drag to move the playhead, long-press to mark a section");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(42), 1);
        rlp.leftMargin = dp(6);
        rulerRow.addView(ruler, rlp);
        LinearLayout.LayoutParams rsp = new LinearLayout.LayoutParams(dp(34), dp(1));
        rsp.leftMargin = dp(6);
        rulerRow.addView(new View(this), rsp);
        lyricStrip = Ui.text(this, 15, Ui.CREAM, Ui.serifItalic);
        lyricStrip.setGravity(Gravity.CENTER);
        lyricStrip.setSingleLine(true);
        lyricStrip.setEllipsize(TextUtils.TruncateAt.END);
        lyricStrip.setPadding(dp(14), dp(2), dp(14), dp(2));
        lyricStrip.setVisibility(View.GONE);
        lyricStrip.setOnClickListener(v -> showLyrics());

        // --- channel strips
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        trackList = new LinearLayout(this);
        trackList.setOrientation(LinearLayout.VERTICAL);
        trackList.setPadding(dp(8), dp(2), dp(8), dp(8));
        sv.addView(trackList, new FrameLayout.LayoutParams(-1, -2));

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
        TextView lyr = Ui.chip(this, "LYRICS");
        lyr.setOnClickListener(v -> showLyrics());
        small.addView(loopChip, new LinearLayout.LayoutParams(0, -2, 1));
        small.addView(clickChip, weighted(1, 6));
        small.addView(undoChip, weighted(1, 6));
        small.addView(lyr, weighted(1.2f, 6));

        // --- assemble: one column in portrait; deck left and tape right in landscape
        if (!land) {
            col.addView(top);
            col.addView(deck, dlp);
            col.addView(rulerRow);
            col.addView(lyricStrip);
            col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
            tp.addView(keys);
            tp.addView(small);
            col.addView(tp);
        } else {
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(top);
            left.addView(deck, dlp);
            small.setPadding(dp(8), dp(8), dp(8), dp(12));
            left.addView(small);
            ScrollView ls = new ScrollView(this);
            ls.addView(left);
            col.addView(ls, new LinearLayout.LayoutParams(0, -1, 0.92f));
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.addView(rulerRow);
            right.addView(lyricStrip);
            right.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
            tp.setPadding(dp(8), dp(6), dp(8), dp(8));
            tp.addView(keys);
            right.addView(tp);
            col.addView(right, new LinearLayout.LayoutParams(0, -1, 1.08f));
        }

        // --- message strip (replaces system toasts so everything stays in the deck fonts)
        noteBar = Ui.text(this, 13, Ui.INK, Ui.sans);
        noteBar.setBackground(Ui.rounded(Ui.PAPER, dp(4), 0));
        noteBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        noteBar.setVisibility(View.GONE);
        noteBar.setOnClickListener(v -> noteBar.setVisibility(View.GONE));
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        nlp.setMargins(dp(12), 0, dp(12), dp(land ? 96 : 150));
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
        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(land ? 60 : 70), 1);
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
        undoChip.setAlpha(undoAction != null && !eng.isRecording() ? 1f : 0.4f);
        boolean playing = eng.isPlaying();
        playKey.set(playing, playing);
        recKey.set(eng.isRecording(), eng.isRecording());
        int i = 0;
        for (Row r : rows) r.refresh(i++);
        boolean showTuner = session && (eng.monitoring || (eng.isRecording() && eng.recTrack != null && eng.recTrack.vocal));
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

    /** Shows the lyric line pinned to the bar under the playhead. */
    private void updateLyricStrip() {
        if (lyricPins.isEmpty()) { lyricStrip.setVisibility(View.GONE); return; }
        long bar = (long) Math.floor(Math.max(0, playhead()) / s.barFrames(SR)) + 1;
        java.util.Map.Entry<Integer, String> e = lyricPins.floorEntry((int) Math.min(Integer.MAX_VALUE, bar));
        String line = e != null ? e.getValue() : lyricPins.firstEntry().getValue();
        lyricStrip.setText(line);
        lyricStrip.setTextColor(e != null ? Ui.CREAM : Ui.FAINT);
        lyricStrip.setVisibility(View.VISIBLE);
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
        updateLyricStrip();
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
        tapeStarted();
        refreshAll();
        startTicking();
    }

    private void onStopKey() {
        if (eng.isPlaying()) eng.stop();
        else { cursor = 0; updateDeck(); for (Row r : rows) r.wave.invalidate(); } // second STOP returns to zero
    }

    private void onRec() {
        if (eng.isRecording()) { eng.stopRecording(); tapeStarted(); refreshAll(); return; } // punch out, keep rolling
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
        tapeStarted();
        askForNotifications();
        refreshAll();
        startTicking();
        if (!prefs.getBoolean("calHint", false) && s.tracks.size() > 1) {
            prefs.edit().putBoolean("calHint", true).apply();
            note("tip: run ••• → calibrate latency once so overdubs line up.");
        }
    }

    private void onUndo() {
        if (undoAction == null || eng.isRecording()) return;
        if (eng.isPlaying()) eng.stop();
        Runnable u = undoAction;
        undoAction = null;
        u.run();
        armed = null;
        save();
        rebuildRows();
        refreshAll();
        if (trackView != null) trackView.invalidate();
        note("undone");
    }

    /** Remembers how to put a track's audio back to what it was before a change. */
    private void undoAudio(Track t, short[] before) {
        undoAction = () -> {
            t.data = before;
            t.computePeaks();
            t.invalidateAnalysis();
            t.dirty = true;
            if (!s.tracks.contains(t)) s.tracks.add(t);
        };
    }

    /** Undo for a track that didn't exist before (import, new take on an empty track). */
    private void undoAdded(Track t) {
        undoAction = () -> s.tracks.remove(t);
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
        tapeEnded();
        if (trackView != null) trackView.invalidate();
        if (!eng.monitoring) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recKey.level = 0;
        refreshAll();
        startTicking(); // let the VU needles fall back
        save();
    }

    @Override public void onTake(Track t) {
        if (t.undo == null || t.undo.length == 0) undoAdded(t);
        else undoAudio(t, t.undo);
        t.undo = null;
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
        if (session) {
            items.add("open track view");
            acts.add(() -> openTrack(t));
        }
        if (!session) {
            // scratch: nothing analysis-related
        } else if (t.analysis != null) {
            items.add("analysis…");
            acts.add(() -> showAnalysisMenu(t));
            items.add(t.showNotes ? "hide notes on the lane" : "show notes on the lane");
            acts.add(() -> { t.showNotes = !t.showNotes; save(); refreshAll(); });
        } else if (!t.isEmpty()) {
            items.add("analyze notes, key, tempo and chords");
            acts.add(() -> analyzeTrack(t, A_ALL));
        }
        if (!t.isEmpty()) {
            items.add("edit…");
            acts.add(() -> showEditMenu(t));
        }
        items.add("effects");
        acts.add(() -> showFx(t));
        if (session) {
            items.add(t.vocal ? "pitch monitor: on (vocal track)" : "pitch monitor: off");
            acts.add(() -> { t.vocal = !t.vocal; save(); refreshAll(); note(t.vocal ? "the tuner will show while recording this track." : "pitch monitor off for this track."); });
        }
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
            undoAudio(t, t.data);
            t.data = new short[0];
            t.dirty = true;
            t.invalidateAnalysis();
            t.computePeaks();
            save();
            refreshAll();
        });
        items.add("delete track");
        acts.add(() -> show(dlg(null).setMessage("delete " + t.name + "?")
                .setPositiveButton("DELETE", (d2, w2) -> {
                    int at = s.tracks.indexOf(t);
                    s.tracks.remove(t);
                    undoAction = () -> s.tracks.add(Math.min(at, s.tracks.size()), t);
                    if (armed == t) armed = null;
                    save();
                    rebuildRows();
                    refreshAll();
                })
                .setNegativeButton("CANCEL", null)));
        list(t.name, items.toArray(new CharSequence[0]), w -> acts.get(w).run());
    }

    // analysis sections (bit flags so any mix can be shown together)
    static final int A_TEMPO = 1, A_KEY = 2, A_MELODY = 4, A_CHORDS = 8, A_RANGE = 16, A_FITS = 32, A_SUGGEST = 64, A_ALL = 127;

    private void showAnalysisMenu(Track t) {
        CharSequence[] items = {"tempo", "key", "melody", "chords by bar", "chords for the melody", "range and tuning",
                "chords that fit the key", "see all analysis", "analyze again"};
        int[] map = {A_TEMPO, A_KEY, A_MELODY, A_CHORDS, A_SUGGEST, A_RANGE, A_FITS, A_ALL, -1};
        list("analysis", items, w -> {
            if (map[w] < 0) reanalyze(t);
            else showAnalysis(t, map[w]);
        });
    }

    /** Analyzing again replaces hand corrections, so ask first if there are any. */
    private void reanalyze(Track t) {
        if (t.analysis == null || !t.analysis.edited) { analyzeTrack(t, A_ALL); return; }
        show(dlg(null).setMessage("analyzing again will replace the notes and chords you've corrected on " + t.name + ".")
                .setPositiveButton("ANALYZE AGAIN", (d, w) -> analyzeTrack(t, A_ALL))
                .setNegativeButton("CANCEL", null));
    }

    /** Called after any hand edit to a track's notes or chords. */
    void analysisEdited(Track t) {
        t.analysisDirty = true;
        t.showNotes = !t.analysis.notes.isEmpty() || t.showNotes;
        save();
        refreshAll();
        if (trackView != null) trackView.invalidate();
    }

    /** Pick the chord for one bar. Suggested chords become real ones the first time you change one. */
    void editChord(Track t, int bar, boolean fromSuggested) {
        Analyzer.Full f = t.analysis;
        if (f == null) return;
        int root = s.keyRoot >= 0 ? s.keyRoot : f.base.keyRoot;
        boolean minor = s.keyRoot >= 0 ? s.keyMinor : f.base.minor;
        String current = null;
        for (Analyzer.Chord c : f.chords) if (c.bar == bar) current = c.name;
        if (current == null && fromSuggested) {
            for (Theory.Suggestion g : Theory.harmonize(f.notes, root, minor, s.beatFrames(SR), Math.max(1, s.beatsPerBar))) {
                if (g.bar == bar) current = g.best;
            }
        }
        ArrayList<String> names = new ArrayList<>();
        if (root >= 0) {
            for (String d : Theory.diatonic(root, minor)) {
                String n = d.substring(d.lastIndexOf(' ') + 1);
                if (!names.contains(n)) names.add(n);
            }
        }
        int inKey = names.size();
        ArrayList<CharSequence> items = new ArrayList<>();
        for (String n : names) items.add(n.equals(current) ? n + "   ●" : n);
        items.add("other chord…");
        items.add("no chord");
        String title = "bar " + (bar + 1) + (current != null ? " · now " + current : "");
        list(title, items.toArray(new CharSequence[0]), w -> {
            if (w < inKey) setChord(t, bar, names.get(w), fromSuggested);
            else if (w == inKey) pickAnyChord(t, bar, fromSuggested);
            else setChord(t, bar, null, fromSuggested);
        });
    }

    private void pickAnyChord(Track t, int bar, boolean fromSuggested) {
        CharSequence[] all = new CharSequence[36];
        String[] qual = {"", "m", "dim"};
        for (int i = 0; i < 12; i++) for (int q = 0; q < 3; q++) all[i * 3 + q] = Analyzer.NOTES[i] + qual[q];
        list("bar " + (bar + 1), all, w -> setChord(t, bar, all[w].toString(), fromSuggested));
    }

    private void setChord(Track t, int bar, String name, boolean fromSuggested) {
        Analyzer.Full f = t.analysis;
        if (f.chords.isEmpty() && fromSuggested) {
            // keep the other suggestions as they are by making them real chords first
            int root = s.keyRoot >= 0 ? s.keyRoot : f.base.keyRoot;
            boolean minor = s.keyRoot >= 0 ? s.keyMinor : f.base.minor;
            for (Theory.Suggestion g : Theory.harmonize(f.notes, root, minor, s.beatFrames(SR), Math.max(1, s.beatsPerBar))) {
                Analyzer.Chord c = new Analyzer.Chord();
                c.bar = g.bar;
                c.name = g.best;
                f.chords.add(c);
            }
        }
        f.chords.removeIf(c -> c.bar == bar);
        if (name != null) {
            Analyzer.Chord c = new Analyzer.Chord();
            c.bar = bar;
            c.name = name;
            f.chords.add(c);
        }
        Analyzer.refreshAfterEdit(f);
        analysisEdited(t);
        note(name == null ? "removed the chord in bar " + (bar + 1) : "bar " + (bar + 1) + " is now " + name);
    }

    private void showMenu() {
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        items.add("export jam…"); acts.add(this::exportJam);
        items.add("import audio…"); acts.add(this::pickImport);
        items.add("lyrics"); acts.add(this::showLyrics);
        if (session) {
            items.add("sections…"); acts.add(this::showSections);
            items.add("bounce tracks…"); acts.add(this::showBounce);
            items.add("back up or share this jam"); acts.add(this::backupJam);
            items.add("open a jam backup…"); acts.add(this::pickBackup);
            items.add("circle of fifths"); acts.add(this::showCircle);
        }
        items.add(session ? "switch to scratch mode (just the basics)" : "switch to session mode (everything)");
        acts.add(() -> setMode(!session));
        items.add("settings"); acts.add(this::showSettings);
        items.add("calibrate latency"); acts.add(this::calibrate);
        items.add("rename jam"); acts.add(this::renameJam);
        items.add("delete jam"); acts.add(() -> confirmDeleteJam(s.dir, s.name));
        list(null, items.toArray(new CharSequence[0]), w -> acts.get(w).run());
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
        if ((which & A_SUGGEST) != 0) {
            if (all) heading(l, "chords for the melody"); else title = "chords for the melody · " + t.name;
            if (root < 0) {
                para(l, "set a key first (KEY button, or USE FOR JAM above) so chords can be picked from it.", 14, Ui.DIM);
            } else if (f.notes.isEmpty()) {
                para(l, "no single-note melody to harmonize.", 14, Ui.DIM);
            } else {
                ArrayList<Theory.Suggestion> sg = Theory.harmonize(f.notes, root, minor, s.beatFrames(SR), Math.max(1, s.beatsPerBar));
                para(l, "chords from " + Analyzer.keyName(root, minor) + " that share the most notes with what you sang or played in each bar.", 13, Ui.DIM);
                para(l, suggestionText(sg), 14, Ui.CREAM);
            }
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

    private static String suggestionText(ArrayList<Theory.Suggestion> sg) {
        StringBuilder b = new StringBuilder();
        int perLine = 0;
        for (Theory.Suggestion g : sg) {
            if (b.length() > 0) b.append(perLine % 2 == 0 ? "\n" : "      ");
            b.append("bar ").append(g.bar + 1).append(":  ").append(g.best);
            if (g.alt != null) b.append("  (or ").append(g.alt).append(")");
            perLine++;
        }
        return b.toString();
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

    // ================================================================= editing

    private String posText() {
        double bf = s.beatFrames(SR);
        long beat = (long) Math.floor(Math.max(0, playhead()) / bf);
        int bpb = Math.max(1, s.beatsPerBar);
        return "bar " + (beat / bpb + 1) + "." + (beat % bpb + 1);
    }

    /** Trim, split, move and fade, all relative to the playhead. */
    void showEditMenu(Track t) {
        if (eng.isRecording() || t.isEmpty()) return;
        long p = Math.max(0, playhead());
        String at = posText();
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        items.add("trim everything before the playhead");
        acts.add(() -> applyEdit(t, Editor.trimBefore(t.data, p), "trimmed before " + at));
        items.add("trim everything after the playhead");
        acts.add(() -> applyEdit(t, Editor.trimAfter(t.data, p), "trimmed after " + at));
        if (session) {
            items.add("split at the playhead (the rest goes to a new track)");
            acts.add(() -> split(t, p, at));
        }
        items.add("move so it starts at the playhead");
        acts.add(() -> {
            int first = Editor.firstSound(t.data);
            if (first < 0) { note("this track is silent."); return; }
            applyEdit(t, Editor.shift(t.data, p - first), "moved to start at " + at);
        });
        items.add("nudge earlier or later…");
        acts.add(() -> showNudge(t));
        if (session) {
            items.add("fade in up to the playhead");
            acts.add(() -> applyEdit(t, Editor.fadeInTo(t.data, p), "faded in up to " + at));
            items.add("fade out from the playhead");
            acts.add(() -> applyEdit(t, Editor.fadeOutFrom(t.data, p), "fading out from " + at));
        }
        list("edit " + t.name + " · " + at, items.toArray(new CharSequence[0]), w -> acts.get(w).run());
    }

    private void applyEdit(Track t, short[] nd, String msg) {
        if (eng.isPlaying()) eng.stop();
        undoAudio(t, t.data);
        t.data = nd;
        t.dirty = true;
        t.invalidateAnalysis();
        t.computePeaks();
        save();
        refreshAll();
        if (trackView != null) trackView.invalidate();
        note(msg + ". UNDO puts it back.");
    }

    private void split(Track t, long p, String at) {
        if (p <= 0 || p >= t.data.length) { note("move the playhead inside the track to split it."); return; }
        if (eng.isPlaying()) eng.stop();
        short[] before = t.data;
        Track b = new Track(s.nextId(), t.name + " b");
        b.copySettingsFrom(t);
        b.vocal = t.vocal;
        try {
            b.data = Editor.tail(before, p);
            t.data = Editor.trimAfter(before, p);
        } catch (OutOfMemoryError e) {
            note("not enough memory to split this track.");
            return;
        }
        b.dirty = true;
        t.dirty = true;
        b.computePeaks();
        t.computePeaks();
        t.invalidateAnalysis();
        s.tracks.add(s.tracks.indexOf(t) + 1, b);
        undoAction = () -> {
            s.tracks.remove(b);
            t.data = before;
            t.computePeaks();
            t.invalidateAnalysis();
            t.dirty = true;
        };
        save();
        rebuildRows();
        refreshAll();
        note("split at " + at + ". the second half is on " + b.name + ".");
    }

    private void showNudge(Track t) {
        final short[] original = t.data;
        final long[] moved = {0};
        LinearLayout l = body();
        TextView amount = para(l, "moved 0 ms", 15, Ui.AMBER);
        para(l, "use this to line up a take that's a little early or late.", 12, Ui.DIM);
        LinearLayout row = chipRow(l);
        long beat = Math.round(s.beatFrames(SR));
        long[] steps = {-beat, -SR / 100, SR / 100, beat};
        String[] names = {"−1 BEAT", "−10 MS", "+10 MS", "+1 BEAT"};
        for (int i = 0; i < steps.length; i++) {
            long st = steps[i];
            addChip(row, names[i], v -> {
                if (eng.isPlaying()) eng.stop();
                moved[0] += st;
                t.data = Editor.shift(original, moved[0]);
                t.computePeaks();
                t.dirty = true;
                amount.setText(String.format(Locale.US, "moved %+d ms", Math.round(moved[0] * 1000.0 / SR)));
                refreshAll();
                if (trackView != null) trackView.invalidate();
            });
        }
        show(dlg("nudge " + t.name).setView(l).setPositiveButton("DONE", null)
                .setOnDismissListener(d -> {
                    if (moved[0] != 0) {
                        t.invalidateAnalysis();
                        undoAudio(t, original);
                        save();
                        refreshAll();
                    }
                }));
    }

    // ================================================================== lyrics

    private void showLyrics() {
        android.app.Dialog d = new android.app.Dialog(this, R.style.DeckFull);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BODY);
        col.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.chip(this, "BACK");
        back.setOnClickListener(v -> d.dismiss());
        top.addView(back);
        TextView title = Ui.text(this, 20, Ui.CREAM, Ui.serifItalic);
        title.setText("lyrics · " + s.name);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12), 0, dp(8), 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView pin = Ui.chip(this, "PIN TO PLAYHEAD");
        top.addView(pin);
        col.addView(top);
        TextView help = Ui.text(this, 12, Ui.DIM, Ui.sans);
        help.setText("start a line with [9] to pin it to bar 9. pinned lines show under the timeline while the jam plays. PIN TO PLAYHEAD adds the tag for the current bar.");
        help.setPadding(0, dp(8), 0, dp(8));
        col.addView(help);
        EditText e = new EditText(this);
        e.setText(s.lyrics);
        e.setTypeface(Ui.serif);
        e.setTextSize(18);
        e.setTextColor(Ui.CREAM);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setBackground(Ui.rounded(Ui.LANE, dp(6), Ui.PANEL_EDGE));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        col.addView(e, new LinearLayout.LayoutParams(-1, 0, 1));
        pin.setOnClickListener(v -> {
            long bar = (long) Math.floor(Math.max(0, playhead()) / s.barFrames(SR)) + 1;
            int c = Math.max(0, e.getSelectionStart());
            String txt = e.getText().toString();
            int lineStart = txt.lastIndexOf('\n', Math.max(0, c - 1)) + 1;
            if (c == 0) lineStart = 0;
            e.getText().insert(lineStart, "[" + bar + "] ");
        });
        d.setContentView(col);
        d.setOnDismissListener(x -> {
            s.lyrics = e.getText().toString();
            lyricPins = Jam.pinnedLyrics(s.lyrics);
            save();
            refreshAll();
        });
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
    }

    // ================================================================ sections

    static final String[] SECTION_NAMES = {"intro", "verse", "pre-chorus", "chorus", "bridge", "solo", "outro"};

    /** Long-press on the timeline: add a section at that bar, or edit the one that's there. */
    void markerMenu(long frame) {
        int bar = (int) Math.floor(Math.max(0, frame) / s.barFrames(SR));
        Jam.Marker existing = null;
        for (Jam.Marker m : s.markers) if (m.bar == bar) existing = m;
        if (existing != null) {
            final Jam.Marker m = existing;
            list(m.name + " · bar " + (bar + 1), new CharSequence[]{"loop this section", "rename", "delete"}, w -> {
                if (w == 0) loopSection(m);
                else if (w == 1) prompt("rename section", m.name, v -> { m.name = v.toLowerCase(Locale.getDefault()); save(); refreshAll(); });
                else { s.markers.remove(m); save(); refreshAll(); }
            });
            return;
        }
        CharSequence[] items = new CharSequence[SECTION_NAMES.length + 1];
        for (int i = 0; i < SECTION_NAMES.length; i++) items[i] = SECTION_NAMES[i];
        items[SECTION_NAMES.length] = "other name…";
        list("mark bar " + (bar + 1) + " as", items, w -> {
            if (w < SECTION_NAMES.length) addMarker(bar, SECTION_NAMES[w]);
            else prompt("section name", "", v -> addMarker(bar, v.toLowerCase(Locale.getDefault())));
        });
    }

    private void addMarker(int bar, String name) {
        s.markers.add(new Jam.Marker(bar, name));
        save();
        refreshAll();
        note(name + " starts at bar " + (bar + 1) + ". tap its label on the timeline to loop it.");
    }

    /** Loops from a section's bar to the next section (or the end). */
    void loopSection(Jam.Marker m) {
        double bar = s.barFrames(SR);
        int endBar = -1;
        for (Jam.Marker o : s.sortedMarkers()) if (o.bar > m.bar) { endBar = o.bar; break; }
        long in = Math.round(m.bar * bar);
        long out = endBar > 0 ? Math.round(endBar * bar) : -1;
        if (out < 0 && s.lengthFrames() <= in) { note("there's nothing recorded in " + m.name + " yet."); return; }
        s.loopIn = in;
        s.loopOut = out;
        s.loop = true;
        seekTo(in);
        refreshAll();
        note("looping " + m.name + (endBar > 0 ? " (bars " + (m.bar + 1) + " to " + endBar + ")" : " to the end"));
    }

    private void showSections() {
        java.util.List<Jam.Marker> ms = s.sortedMarkers();
        if (ms.isEmpty()) { note("long-press the timeline above the tracks to mark a section."); return; }
        CharSequence[] items = new CharSequence[ms.size()];
        for (int i = 0; i < ms.size(); i++) items[i] = ms.get(i).name + "\nbar " + (ms.get(i).bar + 1);
        list("sections (tap to loop)", items, w -> loopSection(ms.get(w)));
    }

    // ================================================================ bouncing

    private void showBounce() {
        java.util.List<Track> ts = new ArrayList<>();
        for (Track t : s.tracks) if (!t.isEmpty()) ts.add(t);
        if (ts.size() < 2) { note("you need at least two tracks with audio to bounce."); return; }
        CharSequence[] names = new CharSequence[ts.size()];
        boolean[] checked = new boolean[ts.size()];
        for (int i = 0; i < ts.size(); i++) names[i] = ts.get(i).name;
        show(dlg("bounce which tracks?")
                .setMultiChoiceItems(names, checked, (d, w, c) -> checked[w] = c)
                .setPositiveButton("NEXT", (d, w) -> {
                    ArrayList<Track> pick = new ArrayList<>();
                    for (int i = 0; i < ts.size(); i++) if (checked[i]) pick.add(ts.get(i));
                    if (pick.size() < 2) { note("pick at least two tracks."); return; }
                    list("bounce " + pick.size() + " tracks, then", new CharSequence[]{
                            "keep the originals, muted", "delete the originals"}, k -> runBounce(pick, k == 1));
                })
                .setNegativeButton("CANCEL", null));
    }

    /** Mixes the chosen tracks (with their effects) down to one new mono track. */
    private void runBounce(ArrayList<Track> pick, boolean deleteOriginals) {
        if (eng.isPlaying()) eng.stop();
        AlertDialog wait = busy("bouncing " + pick.size() + " tracks…");
        final Jam mixJam = new Jam(s.dir);
        mixJam.bpm = s.bpm;
        mixJam.beatsPerBar = s.beatsPerBar;
        mixJam.beatUnit = s.beatUnit;
        mixJam.master = 1f;
        StringBuilder nm = new StringBuilder("bounce of ");
        for (Track t : pick) {
            Track c = new Track(t.id, t.name);
            c.copySettingsFrom(t);
            c.pan = 0;
            c.data = t.data;
            mixJam.tracks.add(c);
            nm.append(s.tracks.indexOf(t) + 1).append(pick.indexOf(t) < pick.size() - 1 ? "+" : "");
        }
        new Thread(() -> {
            short[] out = null;
            try {
                long len = mixJam.lengthFrames() + SR;
                out = new short[(int) len];
                Mixer m = new Mixer(mixJam, SR);
                float[] buf = new float[2048 * 2];
                long[] pos = new long[2048];
                for (long f = 0; f < len; f += 2048) {
                    int n = (int) Math.min(2048, len - f);
                    for (int i = 0; i < n; i++) pos[i] = f + i;
                    m.render(buf, pos, n, null, false);
                    for (int i = 0; i < n; i++) {
                        float v = (buf[2 * i] + buf[2 * i + 1]) * 0.5f * 32767f;
                        out[(int) f + i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
                    }
                }
                int last = Editor.lastSound(out);
                if (last >= 0 && last + SR / 10 < out.length) out = Arrays.copyOf(out, last + SR / 10);
            } catch (OutOfMemoryError e) {
                out = null;
            }
            final short[] mixed = out;
            h.post(() -> {
                wait.dismiss();
                if (mixed == null) { note("not enough memory to bounce these tracks."); return; }
                Track b = new Track(s.nextId(), nm.toString());
                b.data = mixed;
                b.dirty = true;
                b.computePeaks();
                int at = s.tracks.indexOf(pick.get(pick.size() - 1)) + 1;
                s.tracks.add(Math.min(at, s.tracks.size()), b);
                final ArrayList<Track> removed = new ArrayList<>();
                final ArrayList<Integer> removedAt = new ArrayList<>();
                final boolean[] wasMuted = new boolean[pick.size()];
                for (int i = 0; i < pick.size(); i++) {
                    Track t = pick.get(i);
                    wasMuted[i] = t.mute;
                    if (deleteOriginals) {
                        removedAt.add(s.tracks.indexOf(t));
                        removed.add(t);
                        s.tracks.remove(t);
                    } else {
                        t.mute = true;
                    }
                }
                undoAction = () -> {
                    s.tracks.remove(b);
                    for (int i = removed.size() - 1; i >= 0; i--) {
                        s.tracks.add(Math.min(removedAt.get(i), s.tracks.size()), removed.get(i));
                    }
                    for (int i = 0; i < pick.size(); i++) pick.get(i).mute = wasMuted[i];
                };
                save();
                rebuildRows();
                refreshAll();
                note("bounced to " + b.name + (deleteOriginals ? "." : ". the originals are muted."));
            });
        }, "rh-bounce").start();
    }

    // ==================================================================== MIDI

    private void exportMidi() {
        ArrayList<Midi.Part> parts = new ArrayList<>();
        ArrayList<Midi.ChordAt> chords = new ArrayList<>();
        double bar = s.barFrames(SR);
        Analyzer.Full firstMelody = null;
        for (Track t : s.tracks) {
            if (t.analysis == null) continue;
            if (!t.analysis.notes.isEmpty()) {
                Midi.Part p = new Midi.Part();
                p.name = t.name;
                p.notes = t.analysis.notes;
                parts.add(p);
                if (firstMelody == null) firstMelody = t.analysis;
            }
            if (chords.isEmpty() && !t.analysis.chords.isEmpty()) {
                for (Analyzer.Chord c : t.analysis.chords) {
                    Midi.ChordAt ca = new Midi.ChordAt();
                    ca.start = Math.round(c.bar * bar);
                    ca.len = Math.round(bar);
                    ca.midis = Midi.voice(c.name);
                    chords.add(ca);
                }
            }
        }
        int root = s.keyRoot >= 0 ? s.keyRoot : (firstMelody != null ? firstMelody.base.keyRoot : -1);
        boolean minor = s.keyRoot >= 0 ? s.keyMinor : firstMelody != null && firstMelody.base.minor;
        if (chords.isEmpty() && firstMelody != null && root >= 0) {
            for (Theory.Suggestion g : Theory.harmonize(firstMelody.notes, root, minor, s.beatFrames(SR), Math.max(1, s.beatsPerBar))) {
                Midi.ChordAt ca = new Midi.ChordAt();
                ca.start = Math.round(g.bar * bar);
                ca.len = Math.round(bar);
                ca.midis = Midi.voice(g.best);
                chords.add(ca);
            }
        }
        if (parts.isEmpty() && chords.isEmpty()) {
            note("analyze a track first (long-press it) so there are notes or chords to export.");
            return;
        }
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            String fname = s.name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
            if (fname.isEmpty()) fname = "Roughhouse jam";
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fname + ".mid");
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/midi");
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC + "/Roughhouse");
            Uri u = getContentResolver().insert(android.provider.MediaStore.Audio.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (u == null) throw new IOException("couldn't create the file");
            try (java.io.OutputStream os = getContentResolver().openOutputStream(u)) {
                if (os == null) throw new IOException("couldn't open the file");
                Midi.write(os, s, SR, parts, chords);
            }
            note("saved to Music/Roughhouse");
            share(u, "audio/midi", "share MIDI");
        } catch (IOException e) {
            note("MIDI export failed: " + e.getMessage());
        }
    }

    private void share(Uri u, String mime, String title) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, u);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, title));
    }

    // ================================================================= backups

    private void backupJam() {
        if (s.lengthFrames() == 0) { note("there's nothing in this jam to back up yet."); return; }
        if (eng.isPlaying()) eng.stop();
        save();
        AlertDialog wait = busy("packing up the jam…");
        final Jam cur = s;
        new Thread(() -> {
            String err = null;
            Uri u = null;
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                String fname = cur.name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
                if (fname.isEmpty()) fname = "jam";
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fname + Backup.EXT);
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Roughhouse");
                u = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new IOException("couldn't create the file");
                try (java.io.OutputStream os = getContentResolver().openOutputStream(u)) {
                    if (os == null) throw new IOException("couldn't open the file");
                    Backup.write(cur, new java.io.BufferedOutputStream(os, 1 << 16));
                }
            } catch (IOException e) {
                err = e.getMessage();
            }
            final String error = err;
            final Uri done = u;
            h.post(() -> {
                wait.dismiss();
                if (error != null) { note("backup failed: " + error); return; }
                note("saved to Download/Roughhouse");
                share(done, "application/zip", "share jam");
            });
        }, "rh-backup").start();
    }

    private void pickBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "application/x-zip-compressed"});
        startActivityForResult(i, REQ_BACKUP);
    }

    private void restoreBackup(Uri uri) {
        if (eng.isPlaying()) eng.stop();
        AlertDialog wait = busy("opening the backup…");
        new Thread(() -> {
            File dir = null;
            String err = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("couldn't open that file");
                dir = Backup.read(new java.io.BufferedInputStream(in, 1 << 16), root);
            } catch (IOException e) {
                err = e.getMessage();
            }
            final File d = dir;
            final String error = err;
            h.post(() -> {
                wait.dismiss();
                if (error != null) { note(error); return; }
                Jam nj = Jam.load(d);
                if (nj == null) { Jam.deleteDir(d); note("that backup couldn't be read."); return; }
                leaveCurrent();
                openJam(nj);
                note("opened " + nj.name + ".");
            });
        }, "rh-restore").start();
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
        TextView ed = Ui.chip(this, "EDIT");
        ed.setOnClickListener(v -> { if (!t.isEmpty()) showEditMenu(t); });
        actions.addView(an, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(ed, weighted(1, 6));
        actions.addView(fit, weighted(1, 6));
        col.addView(actions);

        // shown while a note is selected
        LinearLayout noteEdit = new LinearLayout(this);
        noteEdit.setGravity(Gravity.CENTER_VERTICAL);
        noteEdit.setPadding(dp(10), dp(6), dp(10), 0);
        noteEdit.setVisibility(View.GONE);
        TextView noteName = Ui.text(this, 14, Ui.CREAM, Ui.sansBold);
        noteName.setPadding(0, 0, dp(6), 0);
        noteEdit.addView(noteName, new LinearLayout.LayoutParams(0, -2, 1.3f));
        TextView up = Ui.chip(this, "UP"), dn = Ui.chip(this, "DOWN"), sh = Ui.chip(this, "SHORTER"),
                lo = Ui.chip(this, "LONGER"), del = Ui.chip(this, "DELETE");
        for (TextView b : new TextView[]{up, dn, sh, lo, del}) {
            b.setPadding(dp(6), dp(8), dp(6), dp(8));
            noteEdit.addView(b, weighted(1, 4));
        }
        TrackView tv = new TrackView(this, t);
        fit.setOnClickListener(v -> tv.fit());
        tv.onSelect = n -> {
            noteEdit.setVisibility(n == null ? View.GONE : View.VISIBLE);
            if (n != null) {
                double bf = s.beatFrames(SR);
                long beat = (long) Math.floor(n.start / bf);
                int bpb = Math.max(1, s.beatsPerBar);
                noteName.setText(Pitch.noteName(n.midi) + "  ·  bar " + (beat / bpb + 1) + "." + (beat % bpb + 1));
            }
        };
        Runnable edited = () -> {
            Analyzer.refreshAfterEdit(t.analysis);
            analysisEdited(t);
            tv.select(tv.selected);
        };
        int half = (int) Math.round(s.beatFrames(SR) / 2);
        up.setOnClickListener(v -> { if (tv.selected != null) { tv.selected.midi++; edited.run(); } });
        dn.setOnClickListener(v -> { if (tv.selected != null) { tv.selected.midi--; edited.run(); } });
        sh.setOnClickListener(v -> { if (tv.selected != null) { tv.selected.len = Math.max(half / 4, tv.selected.len - half); edited.run(); } });
        lo.setOnClickListener(v -> { if (tv.selected != null) { tv.selected.len += half; edited.run(); } });
        del.setOnClickListener(v -> {
            if (tv.selected == null || t.analysis == null) return;
            t.analysis.notes.remove(tv.selected);
            tv.select(null);
            Analyzer.refreshAfterEdit(t.analysis);
            analysisEdited(t);
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, 0, 1);
        tlp.leftMargin = dp(8);
        tlp.rightMargin = dp(8);
        col.addView(tv, tlp);

        col.addView(noteEdit);

        TextView hint = Ui.text(this, 11, Ui.FAINT, Ui.sans);
        hint.setText("tap a note or chord to fix it. long-press the roll to add a note. pinch to zoom and drag to scroll.");
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
        if (req == REQ_BACKUP && result == RESULT_OK && data != null && data.getData() != null) {
            restoreBackup(data.getData());
            return;
        }
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
                s.tracks.add(t);
                undoAdded(t);
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
        boolean hasTempo = r != null && r.bpm > 0, hasKey = session && r != null && r.keyRoot >= 0;
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
        undoAction = null;
        cursor = 0;
        prefs.edit().putString("last", s.dir.getName()).apply();
        lyricPins = Jam.pinnedLyrics(s.lyrics);
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
        CharSequence[] items = new CharSequence[fs.length + (session ? 1 : 0)];
        for (int i = 0; i < fs.length; i++) items[i] = fs[i].label + "\n" + fs[i].hint;
        if (session) items[fs.length] = "MIDI\nnotes and chords from analyzed tracks";
        list("export jam as", items, w -> {
            if (w == fs.length) exportMidi();
            else runExport(fs[w]);
        });
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
