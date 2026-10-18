// 讓瀏覽器把網頁當成可安裝的 App（才能出現在「分享」選單）；內容一律走網路，不做離線快取。
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
