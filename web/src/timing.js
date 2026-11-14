// Tempo math shared by the UI thread and the audio worklet.
export const SR = 48000;
export const BLOCK = 128;
export const PB = 1024; // frames per waveform peak

/** Frames per metronome beat (the time signature's denominator note gets the BPM). */
export const beatFrames = (s, sr = SR) => 60 * sr / Math.max(30, s.bpm);
/** Frames per bar. */
export const barFrames = (s, sr = SR) => beatFrames(s, sr) * Math.max(1, s.beatsPerBar);
/** Frames per quarter note, used for tempo-synced echo. */
export const quarterFrames = (s, sr = SR) => beatFrames(s, sr) * s.beatUnit / 4;

export const floorMod = (a, b) => ((a % b) + b) % b;
