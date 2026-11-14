// Offline support: serve the app shell from cache, refresh it in the background.
const CACHE = 'roughhouse-__BUILD__';
const SHELL = ['./', 'index.html', 'style.css', 'app.js', 'worklet.js', 'worker.js', 'manifest.webmanifest',
  'fonts/heros_regular.otf', 'fonts/heros_bold.otf', 'fonts/termes_regular.otf', 'fonts/termes_italic.otf', 'fonts/termes_bold.otf',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/icon.svg', 'icons/apple-touch-icon.png', 'icons/favicon-32.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  e.respondWith(caches.open(CACHE).then(async c => {
    const hit = await c.match(req, { ignoreSearch: true });
    const net = fetch(req).then(r => { if (r.ok) c.put(req, r.clone()); return r; }).catch(() => hit);
    return hit || net;
  }));
});
