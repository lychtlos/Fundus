/* Fundus Service Worker: App-Hülle offline, Inhalte zuerst aus dem Netz, Push-Mitteilungen */
const C='fundus-v4';
const SHELL=['./','./index.html','./manifest.json','./icon-192.png','./icon-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(SHELL)));self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const u=new URL(e.request.url);
  if(e.request.method!=='GET'||u.origin!==location.origin)return;
  /* immer frisch vom Server (kein HTTP-Zwischenspeicher), offline aus dem Cache */
  e.respondWith(fetch(e.request,{cache:'no-store'}).then(r=>{const cp=r.clone();caches.open(C).then(c=>c.put(e.request,cp));return r})
    .catch(()=>caches.match(e.request).then(r=>r||caches.match('./index.html'))));
});
self.addEventListener('push',e=>{
  let d={};try{d=e.data?e.data.json():{}}catch(_){d={body:e.data&&e.data.text()}}
  e.waitUntil(self.registration.showNotification(d.title||'Fundus',{
    body:d.body||'Neuerscheinungen zu Deinen Listen',icon:'icon-192.png',badge:'icon-192.png',
    tag:d.tag||'fundus',renotify:true,data:{url:d.url||'./#neu'}}));
});
self.addEventListener('notificationclick',e=>{
  e.notification.close();const url=new URL((e.notification.data&&e.notification.data.url)||'./#neu',self.registration.scope).href;
  e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(list=>{
    for(const c of list){if(c.url.startsWith(self.registration.scope)){c.navigate(url);return c.focus()}}
    return self.clients.openWindow(url);
  }));
});
