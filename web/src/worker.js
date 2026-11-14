// Background thread for the slow jobs: analysis, export rendering and bouncing.
import { analyzeTrack, analyze } from './analyzer.js';
import { exportJam, bounce } from './exporter.js';
import { SR } from './timing.js';

self.onmessage = async e => {
  const { id, job } = e.data;
  try {
    let result;
    if (job.type === 'analyzeTrack') {
      result = analyzeTrack(job.data, SR, job.beatFrames, job.beatsPerBar);
    } else if (job.type === 'analyze') {
      result = analyze(job.data, SR);
    } else if (job.type === 'export') {
      result = await exportJam(job.jam, SR, job.format, f => self.postMessage({ id, progress: f }));
    } else if (job.type === 'bounce') {
      const d = bounce(job.jam, SR);
      self.postMessage({ id, result: d }, [d.buffer]);
      return;
    }
    self.postMessage({ id, result });
  } catch (err) {
    self.postMessage({ id, error: String(err && err.message || err) });
  }
};
