// Export formats (kept apart from the encoders so the UI bundle stays small).
export const FORMATS = [
  { id: 'wav', label: 'WAV', hint: 'uncompressed, for DAWs', ext: 'wav', mime: 'audio/wav' },
  { id: 'flac', label: 'FLAC', hint: 'lossless, about half the size', ext: 'flac', mime: 'audio/flac' },
  { id: 'm4a', label: 'M4A (AAC 256k)', hint: 'small, plays everywhere', ext: 'm4a', mime: 'audio/mp4' },
  { id: 'mp3', label: 'MP3 (192k)', hint: 'universal', ext: 'mp3', mime: 'audio/mpeg' },
];

export const canM4a = async () => {
  if (typeof AudioEncoder === 'undefined') return false;
  try {
    const r = await AudioEncoder.isConfigSupported({ codec: 'mp4a.40.2', sampleRate: 48000, numberOfChannels: 2, bitrate: 256000 });
    return !!r.supported;
  } catch { return false; }
};
