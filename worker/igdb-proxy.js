/* Fundus – IGDB-Proxy für Cloudflare Workers
   Hält das Twitch-Client-Secret, holt das App-Token, leitet nur IGDB-Abfragen weiter.
   Einrichtung: als Worker einfügen, unter Settings → Variables and Secrets zwei Secrets anlegen:
     TWITCH_CLIENT_ID, TWITCH_CLIENT_SECRET
   Aufruf aus der App:  POST https://<worker>/games   Body: IGDB-Abfrage (Apicalypse), z. B.
     fields name,cover.image_id,similar_games; where id = 1942;                            */

const ALLOWED_ORIGINS = ['https://lychtlos.github.io'];          // Deine App
const ENDPOINTS = new Set(['games','genres','themes','platforms','search','release_dates',
  'covers','screenshots','game_videos','involved_companies','companies','websites',
  'franchises','collections','popularity_primitives','multiquery']);
const CACHE_SECONDS = 6 * 3600;

let token = null, tokenExp = 0;
async function getToken(env) {
  if (token && Date.now() < tokenExp - 60_000) return token;
  const u = 'https://id.twitch.tv/oauth2/token?client_id=' + encodeURIComponent(env.TWITCH_CLIENT_ID) +
    '&client_secret=' + encodeURIComponent(env.TWITCH_CLIENT_SECRET) + '&grant_type=client_credentials';
  const r = await fetch(u, { method: 'POST' });
  if (!r.ok) throw new Error('Twitch-Token fehlgeschlagen: ' + r.status);
  const j = await r.json();
  token = j.access_token; tokenExp = Date.now() + j.expires_in * 1000;
  return token;
}
async function sha(s) {
  const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('');
}

export default {
  async fetch(req, env, ctx) {
    const origin = req.headers.get('Origin') || '';
    const okOrigin = ALLOWED_ORIGINS.includes(origin) || /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin);
    const cors = {
      'Access-Control-Allow-Origin': okOrigin ? origin : ALLOWED_ORIGINS[0],
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Max-Age': '86400',
      'Vary': 'Origin'
    };
    if (req.method === 'OPTIONS') return new Response(null, { headers: cors });

    const ep = new URL(req.url).pathname.replace(/^\/+|\/+$/g, '');
    if (ep === '' && req.method === 'GET')
      return new Response('Fundus IGDB-Proxy läuft.', { headers: { ...cors, 'Content-Type': 'text/plain; charset=utf-8' } });
    if (req.method !== 'POST' || !ENDPOINTS.has(ep)) return new Response('Nicht gefunden', { status: 404, headers: cors });
    // Browser-Aufrufe nur von der eigenen App; Aufrufe ohne Origin (z. B. der Push-Job) sind erlaubt
    if (origin && !okOrigin) return new Response('Nicht erlaubt', { status: 403, headers: cors });

    const body = await req.text();
    if (body.length > 5000) return new Response('Abfrage zu lang', { status: 413, headers: cors });

    const cache = caches.default;
    const key = new Request('https://cache.fundus.invalid/' + ep + '/' + await sha(body));
    let res = await cache.match(key);
    if (!res) {
      try {
        const t = await getToken(env);
        const up = await fetch('https://api.igdb.com/v4/' + ep, {
          method: 'POST',
          headers: { 'Client-ID': env.TWITCH_CLIENT_ID, 'Authorization': 'Bearer ' + t, 'Accept': 'application/json' },
          body
        });
        if (up.status === 401) token = null;                    // Token ungültig → beim nächsten Mal neu holen
        res = new Response(await up.text(), { status: up.status, headers: { 'Content-Type': 'application/json' } });
        if (up.ok) { res.headers.set('Cache-Control', 'public, max-age=' + CACHE_SECONDS); ctx.waitUntil(cache.put(key, res.clone())); }
      } catch (e) {
        return new Response(JSON.stringify({ error: String(e.message || e) }), { status: 502, headers: { ...cors, 'Content-Type': 'application/json' } });
      }
    }
    const out = new Response(res.body, res);
    Object.entries(cors).forEach(([k, v]) => out.headers.set(k, v));
    return out;
  }
};
