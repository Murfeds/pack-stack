// Pack Stack service worker: keeps the whole game on the phone so it opens and plays with no internet.
// The cache name changes with every build, so a new version replaces the old one cleanly.
const CACHE = "packstack-1.2.0-be774f5a91";
const CORE = ["./", "index.html", "manifest.webmanifest", "live.json", "icon-192.png", "icon-512.png", "apple-touch-icon.png", "favicon-32.png"];
self.addEventListener("install", e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(CORE)).then(() => self.skipWaiting())); });
self.addEventListener("activate", e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener("fetch", e => {
  const req = e.request; if (req.method !== "GET") return;
  const url = new URL(req.url), mine = url.origin === location.origin, keep = res => { if (res && (res.ok || res.type === "opaque")) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); } return res; };
  // the live config: fresh from the network when there is one, the saved copy when there is not
  if (mine && url.pathname.endsWith("live.json")) { e.respondWith(fetch(req).then(keep).catch(() => caches.match(req, { ignoreSearch: true }))); return; }
  // everything else (the game page, icons, fonts): the saved copy first, the network only for what has never been seen
  e.respondWith(caches.match(req, { ignoreSearch: mine }).then(hit => hit || fetch(req).then(keep).catch(() => (mine && req.mode === "navigate" ? caches.match("index.html") : Response.error()))));
});
