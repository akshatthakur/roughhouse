# Roughhouse

a multitrack recorder for Android that looks like an old cassette 4-track. you hit REC
and play, and every take lands on its own track.

i made it for getting songs down quickly. once something's recorded, it can also work out
the key and tempo, and show you the notes and chords you played.

it's written in Java and runs on Android 10 and up. it records at 48 kHz, with mono tracks
and a stereo mix. there's no limit on tracks apart from your phone's memory, and each
minute of a track takes about 5.8 MB.

## two modes

Roughhouse has a **scratch** mode for getting things down and a **session** mode for working
on them. the line under the ROUGHHOUSE name shows which one you're in, and tapping it
switches. you can also switch under *•••*. a fresh install starts in scratch mode.

scratch mode keeps recording and playback, the timeline, tracks with mute, solo and effects,
the click and tempo, UNDO, jams, importing, exporting audio, lyrics, and simple editing (trim
and move). tapping a lane opens its menu.

session mode adds everything else in this readme: time signature, KEY and the circle of
fifths, the tuner, analysis and the track view, splitting and fades, sections, bouncing,
MIDI export and backups. tapping a lane opens the track view.

## installing

every push to `main` builds the app and puts it in the **latest** release:

**https://github.com/akshatthakur/roughhouse/releases/latest/download/Roughhouse.apk**

open that link on your phone and allow installs from your browser when Android asks.

once it's installed, long-press the app icon and choose *record now* to start a new jam and
record straight away.

every build is signed with the same key, so a new version installs over the old one and
your jams stay put. the key is kept in the repo's secrets (`RH_KEYSTORE_B64` and
`RH_STORE_PASSWORD`), not in the code. builds stop with an error if they're missing.

to build it yourself, open the folder in Android Studio and use *Build > Build APK(s)*,
or run `./gradlew assembleRelease`. to sign it with the release key, set `RH_KEYSTORE_FILE`
(the path to the key file) and `RH_STORE_PASSWORD` first.

## the deck

words in caps are the labels on the buttons in the app.

| control | what it does |
| --- | --- |
| **REC** | from stop, it starts the tape with a one-bar count-in and records. while playing, it starts recording at the playhead. while recording, it stops recording and keeps playing. |
| **PLAY / STOP** | STOP goes back to where you started playing, so you can do another take straight away. press STOP again to go back to the start. |
| **REW** | back to the start. |
| **cassette** | tap the label to rename the jam. the reels turn while the tape plays. |
| **tape counter** | tap it to switch between time and bar.beat. |
| **VU meters** | show the output level. they turn into a tuner when the pitch monitor is on. |
| **BPM** | tempo slider and tap tempo, plus buttons to double or halve it. |
| **time signature** | from 2/4 to 12/8. it sets the metronome accent, the bar lines on the lanes and the length of the count-in. |
| **KEY** | the jam's key. picking one shows its scale and chords, along with progressions in that key. long-press it to open the circle of fifths. |
| **TUNE** | turns on the tuner without recording. |
| **channel number** | arms that track, so the next take records over it. only the part you record over is replaced. if no track is armed, takes go to the first empty track or a new one. |
| **timeline** | the strip above the tracks shows bar numbers, sections and every track at once. tap or drag along it to move the playhead, while stopped or playing. long-press it to mark a section, and tap a section's name to loop it. |
| **tap a lane** | opens the track view. |
| **long-press a lane** | opens that track's menu. |
| **M / S / FX** | mute, solo and effects. the effects are volume, pan, low cut, bass, treble, compression, echo synced to the tempo, and reverb. changing them never touches the recording. |
| **LOOP** | loops the jam. long-press to set where the loop starts and ends. |
| **CLICK** | metronome on or off. long-press for settings like count-in, click volume, input boost and latency. |
| **UNDO** | reverses the last take, import, edit, split, bounce or deleted track. |
| **LYRICS** | opens the lyrics for this jam. |
| **+ TRACK / IMPORT AUDIO** | adds an empty track or brings in an audio file. |
| **JAMS / NEW** | opens your saved jams or starts a new one. long-press a jam in the list to delete it. jams save automatically, and empty ones are thrown away. |
| **•••** | export, import, sections, bouncing, lyrics, backups, circle of fifths, settings, latency calibration, and renaming or deleting the jam. |

## songwriting tools

**analyzing a track.** long-press a lane and choose *analyze notes, key, tempo and chords*.
the results are saved with the jam. after that, the same menu has an *analysis* option where
you can look at each part on its own or choose *see all analysis*. it covers:
- the tempo, with buttons to use it for the jam (or double or half of it)
- the key, with a button to set it as the jam's key
- the melody, summarized: a chart of how much each note is used, then the melody split into
  phrases at the rests. each phrase is drawn as a small piano roll, repeated phrases are shown
  once with a count, and notes outside the key are red. tapping a phrase opens it in the
  track view
- your range, and whether you're singing or playing sharp or flat on average
- the chord in each bar
- chords for the melody: for each bar, the chords in the key that share the most notes with
  what you sang or played. if a track has no chords of its own, these also show in the
  track view, marked as suggested
- the scale and chords for the key, and some common progressions written out in it

**fixing what it got wrong.** detection isn't perfect, so you can correct it in the track
view. tap a note to select it, then move it UP or DOWN a semitone, make it SHORTER or LONGER,
or DELETE it. long-press an empty spot on the piano roll to add a note there. tap a chord, or
an empty bar in the chord row, to pick a different chord (or none). corrections are saved,
and *analyze again* asks before it replaces them.

the detected notes can also be drawn on the lane, with out-of-key notes in red. melody
detection follows one note at a time, so it works on a single voice or instrument. chord
detection is for parts where you're playing chords.

**track view.** tap a lane to open the track full screen. it shows bar numbers, the chord in
each bar, the detected notes on a piano roll with their names, and the waveform underneath.
pinch to zoom and drag to scroll. tapping moves the playhead, and the track view has its own
REW, PLAY and STOP keys. FIT zooms back out to the whole track.

**editing.** open a track's *edit* menu from the track view or by long-pressing it. edits
work from the playhead: trim everything before or after it, split the track there (the
second half moves to a new track), move the track so it starts at the playhead, nudge it
earlier or later by 10 ms or a beat, or fade in up to the playhead or out from it.
UNDO reverses any of these.

**sections.** long-press the timeline and pick a name like verse or chorus to mark where a
section starts. a section runs until the next one. tap its name on the timeline to loop it,
or find all of them under *••• > sections*.

**lyrics.** LYRICS opens a page for writing the jam's lyrics. start a line with `[9]` to pin
it to bar 9, or use PIN TO PLAYHEAD. while the jam plays, the pinned line for the current
bar shows under the timeline.

**bouncing.** *••• > bounce tracks* mixes the tracks you pick, with their effects, down to
one new track. you can keep the originals (they get muted) or delete them.

**circle of fifths.** long-press KEY, or open it from •••. tapping a key highlights it and
the keys closest to it, and shows its relative key, scale, chords and progressions below
the wheel.

**pitch monitor.** turn on *pitch monitor* in a track's menu for anything you sing on. the
lane gets a `VOX` tag, and while that track records, the VU meters switch to a tuner that
shows the note you're singing and how many cents off it is. TUNE opens the same tuner at
any time.

**importing.** MP3, WAV, M4A/AAC, FLAC and OGG all work, along with most other formats
Android can play. stereo files are mixed down to mono. Roughhouse detects the file's tempo
and key and uses them if the jam is empty. otherwise it asks first. the tempo it finds is
sometimes half or double the real one, so there are buttons to fix that.

## exporting

*••• > export jam* renders the mix with its effects, saves it to `Music/Roughhouse` and
opens the share sheet.

| format | notes |
| --- | --- |
| WAV | 16-bit stereo, uncompressed |
| FLAC | lossless, about half the size of WAV |
| M4A | AAC at 256 kbps, using Android's built-in encoder |
| MP3 | 192 kbps, using a bundled Java port of LAME |
| MIDI | the notes from every analyzed track, plus a chord track (detected chords, or the suggested ones), with the jam's tempo, time signature and key |

## backups

*••• > back up or share this jam* packs every track, its settings, the lyrics and any
analysis into one `.roughhouse.zip` file in `Download/Roughhouse`, and opens the share sheet.
*••• > open a jam backup* brings one back as a new jam.

## recording in the background

the tape keeps rolling if the screen turns off or you switch to another app. a notification
shows that Roughhouse is recording or playing, with a STOP button. if a call comes in,
another app starts playing, or headphones get unplugged, the tape stops and the take is
kept. on Android 13 and up, Roughhouse asks once if it can show notifications. recording
works either way.

## landscape

turn the phone sideways and the deck moves to the left, with the timeline, tracks and
transport keys on the right. rotating doesn't interrupt recording or playback.

## lining up overdubs

phones have a small delay between what you hear and what the mic records, which makes
overdubs land late. run *••• > calibrate latency* once on your phone, with the speaker on,
headphones unplugged and the room quiet. it plays clicks, measures how long they take to
come back, and moves every new take earlier by that amount. bluetooth headphones add
150-300 ms and it varies, so use wired headphones or the speaker when you overdub.

## code

```
app/src/main/java/com/roughhouse/app/
  MainActivity.java  the main screen, dialogs, jams, importing, analysis and export
  Deck.java          drawn controls: cassette, counter, VU meter, tuner, keys, circle of fifths
  WaveView.java      a track lane: waveform, bar lines, live take, notes, level meter
  Ruler.java         the timeline above the tracks, for moving the playhead
  MelodyView.java    the melody summary: note usage and phrases
  TrackView.java     the full-screen track view: piano roll, chord row, zoom and scroll
  Ui.java            colours, fonts and styling helpers
  Engine.java        playback, recording, placing takes, live pitch, calibration
  Mixer.java         mixes tracks through effects, reverb, metronome and a limiter
  Dsp.java           filters, EQ, compressor, echo, reverb
  Pitch.java         pitch detection (YIN)
  Analyzer.java      tempo, key, melody and chords
  Theory.java        scales, chords in a key, progressions
  Importer.java      decodes audio files into tracks
  Exporter.java      WAV, FLAC, M4A and MP3 export
  FlacWriter.java    FLAC encoder
  Mp3Writer.java     wrapper for the bundled LAME port
  Editor.java        trimming, splitting, moving and fading
  Midi.java          MIDI file export
  Backup.java        packing a jam into a zip and unpacking it
  Jam.java, Track.java  data and saving
app/src/main/java/mp3, mpg   LAME MP3 encoder, Java port (LGPL)
app/src/main/res/font        TeX Gyre Heros and Termes, similar to Helvetica and Times
licenses/                    licence texts for the encoder and fonts
```

## credits

- MP3 encoding uses a Java port of LAME by Ken Händel (jump3r), based on the original LAME
  project. it's LGPL. the licence is in `licenses/LAME-LGPL.txt`, and the source is
  included unmodified, apart from a removed JUnit test, in `app/src/main/java/mp3` and `mpg`.
- the fonts are TeX Gyre Heros and TeX Gyre Termes from the GUST e-foundry, under the GUST
  Font License (`licenses/TeX-Gyre-GUST-Font-License.txt`).
