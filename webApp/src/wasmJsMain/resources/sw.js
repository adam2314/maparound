// MapAround web: works offline. The app's own files (code, map style, fonts, world map, symbols,
// the tile worker in tiles/) are kept in the cache as they're loaded and served from it, refreshed
// in the background when online (stale-while-revalidate). The page itself falls back to the cached
// copy offline. Other sites (online maps, search) aren't cached here; downloaded maps are in the
// browser's file storage, not in this cache.
const CACHE = 'maparound-app-v1';
const CORE = ['./', 'index.html', 'app.css', 'mapview.js', 'maplibre-gl.js', 'maplibre-gl.css', 'mapbox-gl-rtl-text.js', 'qrcode.js', 'maparound.js',
  'manifest.webmanifest', 'icon-192.png', 'themes/topo.xml', 'world.bin', 'borders.bin', 'regions.bin',
  'tiles/tiles-worker.js', 'tiles/maptiles.js'];

// A party notification tapped: back to the app.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    .then((cs) => (cs.length ? cs[0].focus() : self.clients.openWindow('./'))));
});

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(CORE)).catch(() => {}).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('maparound-app-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  event.respondWith(
    caches.open(CACHE).then(async (cache) => {
      const key = req.mode === 'navigate' ? 'index.html' : req;
      const cached = await cache.match(key, { ignoreSearch: req.mode === 'navigate' });
      const network = fetch(req)
        .then((res) => {
          if (res.ok && res.type === 'basic') cache.put(key, res.clone());
          return res;
        })
        .catch(() => cached || Response.error());
      return cached || network;
    }),
  );
});
