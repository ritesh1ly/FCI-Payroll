/*
 * FCILekha service worker (docs/architecture/RESPONSIVE_AND_PWA.md). Registered only when the server
 * runs with FCI_PWA_ENABLED=true, as /sw.js?v=<build>, so every deployment gets fresh caches.
 *
 * It never stores personal or payroll data:
 *   - /nginx/fci-payroll/**                    not handled at all: always the network, never a cache;
 *   - page navigations           network only (pages are per user, no-store); offline -> /offline.html;
 *   - /_next/static/**           cache first: hashed, public, immutable build assets;
 *   - /icons/**, the manifest    stale-while-revalidate: public;
 *   - anything else              not handled (the browser's normal fetch).
 * Paths are under the pages' base path (the worker's own folder: '' at the root, '/nginx' behind the
 * shared NGINX); the API uses the fixed /nginx/fci-payroll/ prefix. Caches are named fcilekha-<build>; activation deletes every other fcilekha-* cache, and the page asks
 * for a full clear at sign-out ({type: 'CLEAR'}).
 */
const VERSION = new URL(self.location.href).searchParams.get('v') || 'dev';
const CACHE = `fcilekha-${VERSION}`;
const BASE = new URL('.', self.location.href).pathname.replace(/\/$/, '');
const OFFLINE = `${BASE}/offline.html`;
const PRECACHE = [
  OFFLINE,
  `${BASE}/offline.css`,
  `${BASE}/icons/icon-192.png`,
  `${BASE}/manifest.webmanifest`,
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(PRECACHE.map((url) => new Request(url, { cache: 'reload' }))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((k) => k.startsWith('fcilekha-') && k !== CACHE).map((k) => caches.delete(k)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'CLEAR') {
    event.waitUntil(
      caches
        .keys()
        .then((keys) =>
          Promise.all(keys.filter((k) => k.startsWith('fcilekha-')).map((k) => caches.delete(k))),
        ),
    );
  }
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/nginx/fci-payroll/') || url.pathname.startsWith('/api/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match(OFFLINE)));
    return;
  }
  if (url.pathname.startsWith(`${BASE}/_next/static/`)) {
    event.respondWith(
      caches.open(CACHE).then((cache) =>
        cache.match(request).then(
          (hit) =>
            hit ||
            fetch(request).then((response) => {
              if (response.ok) cache.put(request, response.clone());
              return response;
            }),
        ),
      ),
    );
    return;
  }
  if (
    url.pathname.startsWith(`${BASE}/icons/`) ||
    url.pathname === `${BASE}/manifest.webmanifest` ||
    url.pathname === `${BASE}/offline.css`
  ) {
    event.respondWith(
      caches.open(CACHE).then((cache) =>
        cache.match(request).then((hit) => {
          const network = fetch(request)
            .then((response) => {
              if (response.ok) cache.put(request, response.clone());
              return response;
            })
            .catch(() => hit);
          return hit || network;
        }),
      ),
    );
  }
});
