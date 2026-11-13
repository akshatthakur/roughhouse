# Roughhouse

a multitrack recorder for Android that looks like an old cassette 4-track. you hit REC
and play, and every take lands on its own track.

i made it for getting songs down quickly. once something's recorded, it can also work out
the key and tempo, and show you the notes and chords you played.

it's written in Java and runs on Android 10 and up. it records at 48 kHz, with mono tracks
and a stereo mix. there's no limit on tracks apart from your phone's memory, and each
minute of a track takes about 5.8 MB.

## installing

every push to `main` builds the app and puts it in the **latest** release:

**https://github.com/akshatthakur/roughhouse/releases/latest/download/Roughhouse.apk**

open that link on your phone and allow installs from your browser when Android asks.

to build it yourself, open the folder in Android Studio and use *Build > Build APK(s)*,
or run `./gradlew assembleRelease`.

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
| **timeline** | the strip above the tracks shows bar numbers and every track at once. tap or drag along it to move the playhead, while stopped or playing. |
| **tap a lane** | opens the track view. |
| **long-press a lane** | opens that track's menu. |
| **M / S / FX** | mute, solo and effects. the effects are volume, pan, low cut, bass, treble, compression, echo synced to the tempo, and reverb. changing them never touches the recording. |
| **LOOP** | loops the jam. long-press to set where the loop starts and ends. |
| **CLICK** | metronome on or off. long-press for settings like count-in, click volume, input boost and latency. |
| **UNDO** | removes the last take or import, or restores what it recorded over. |
| **+ TRACK / IMPORT AUDIO** | adds an empty track or brings in an audio file. |
| **JAMS / NEW** | opens your saved jams or starts a new one. long-press a jam in the list to delete it. jams save automatically, and empty ones are thrown away. |
| **•••** | export, import, circle of fifths, settings, latency calibration, and renaming or deleting the jam. |

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
- the scale and chords for the key, and some common progressions written out in it

the detected notes can also be drawn on the lane, with out-of-key notes in red. melody
detection follows one note at a time, so it works on a single voice or instrument. chord
detection is for parts where you're playing chords.

**track view.** tap a lane to open the track full screen. it shows bar numbers, the chord in
each bar, the detected notes on a piano roll with their names, and the waveform underneath.
pinch to zoom and drag to scroll. tapping moves the playhead, and the track view has its own
REW, PLAY and STOP keys. FIT zooms back out to the whole track.

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
