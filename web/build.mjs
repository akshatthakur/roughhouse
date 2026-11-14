// Bundles the app, the audio worklet and the background worker into dist/ with the static files.
import { build } from 'esbuild';
import { cpSync, rmSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';

const dev = process.argv.includes('--dev');
rmSync('dist', { recursive: true, force: true });
mkdirSync('dist', { recursive: true });
cpSync('public', 'dist', { recursive: true });

const common = { bundle: true, minify: !dev, sourcemap: dev ? 'inline' : false, target: ['es2020', 'safari15'], legalComments: 'eof', logLevel: 'info' };
await build({ ...common, entryPoints: ['src/app.js'], outfile: 'dist/app.js', format: 'esm' });
await build({ ...common, entryPoints: ['src/worklet.js'], outfile: 'dist/worklet.js', format: 'iife' });
await build({ ...common, entryPoints: ['src/worker.js'], outfile: 'dist/worker.js', format: 'iife' });

const stamp = Date.now().toString(36);
const sw = readFileSync('dist/sw.js', 'utf8').replace('__BUILD__', stamp);
writeFileSync('dist/sw.js', sw);
console.log('built', stamp);
