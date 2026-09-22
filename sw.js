// 최소한의 서비스워커입니다.
// 이 앱은 자주 업데이트되기 때문에 일부러 캐시를 하지 않고, 항상 네트워크에서
// 최신 파일을 받아오게만 합니다. (설치 가능한 PWA가 되려면 서비스워커에
// fetch 이벤트 핸들러가 있어야 한다는 조건만 충족시키는 용도)
self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', (event) => {
  event.respondWith(fetch(event.request));
});
