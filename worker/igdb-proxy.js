/* Fundus – IGDB-Proxy für Cloudflare Workers
   Hält das Twitch-Client-Secret, holt das App-Token, leitet nur IGDB-Abfragen weiter.
   Einrichtung: als Worker einfügen, unter Settings → Variables and Secrets zwei Secrets anlegen:
     TWITCH_CLIENT_ID, TWITCH_CLIENT_SECRET
   Aufruf aus der App:  POST https://<worker>/games   Body: IGDB-Abfrage (Apicalypse), z. B.
     fields name,cover.image_id,similar_games; where id = 1942;
   Wunsch-Suche:        POST https://<worker>/wish    Body: {"idToken":"<Firebase-ID-Token>"}
     prüft die Google-Anmeldung und startet den GitHub-Lauf „Fundus Wunsch“.
     Dafür ein weiteres Secret: GH_TOKEN (Fine-grained Token, nur Repo Fundus, Actions: Read and write). */

const ALLOWED_ORIGINS = ['https://lychtlos.github.io'];          // Deine App
const ENDPOINTS = new Set(['games','genres','themes','platforms','search','release_dates',
  'covers','screenshots','game_videos','involved_companies','companies','websites',
  'franchises','collections','popularity_primitives','multiquery']);
const CACHE_SECONDS = 6 * 3600;
const FB_PROJECT = 'wanderlust-8b3ba';
const GH_REPO = 'lychtlos/Fundus';

/* Firebase-ID-Token prüfen (RS256, Schlüssel von Google) */
let jwks = null, jwksExp = 0;
const b64u = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), c => c.charCodeAt(0));
async function verifyIdToken(tok) {
  const [h, p, sig] = String(tok || '').split('.');
  if (!h || !p || !sig) throw new Error('Token fehlt');
  const head = JSON.parse(new TextDecoder().decode(b64u(h))), pay = JSON.parse(new TextDecoder().decode(b64u(p)));
  if (head.alg !== 'RS256') throw new Error('Falscher Algorithmus');
  if (!jwks || Date.now() > jwksExp) {
    const r = await fetch('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com');
    jwks = (await r.json()).keys; jwksExp = Date.now() + 3600_000;
  }
  const jwk = jwks.find(k => k.kid === head.kid); if (!jwk) throw new Error('Unbekannter Schlüssel');
  const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, b64u(sig), new TextEncoder().encode(h + '.' + p));
  const now = Date.now() / 1000;
  if (!ok || pay.aud !== FB_PROJECT || pay.iss !== 'https://securetoken.google.com/' + FB_PROJECT || !(pay.exp > now) || !(pay.iat <= now + 60) || !pay.sub) throw new Error('Token ungültig');
  return pay.sub;
}
async function wish(req, env, cors) {
  const J = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { ...cors, 'Content-Type': 'application/json' } });
  if (!env.GH_TOKEN) return J({ error: 'GH_TOKEN fehlt im Worker' }, 500);
  let uid;
  try { const b = await req.json(); uid = await verifyIdToken(b.idToken); } catch (e) { return J({ error: 'Anmeldung ungültig: ' + e.message }, 401); }
  /* höchstens ein Start pro Minute und Konto */
  const cache = caches.default, ck = new Request('https://cache.fundus.invalid/wish/' + uid);
  if (await cache.match(ck)) return J({ error: 'Bitte eine Minute warten' }, 429);
  const r = await fetch('https://api.github.com/repos/' + GH_REPO + '/actions/workflows/wish.yml/dispatches', {
    method: 'POST',
    headers: { 'Authorization': 'Bearer ' + env.GH_TOKEN, 'Accept': 'application/vnd.github+json', 'User-Agent': 'fundus-worker', 'X-GitHub-Api-Version': '2022-11-28' },
    body: JSON.stringify({ ref: 'main', inputs: { uid } })
  });
  if (r.status !== 204) return J({ error: 'GitHub: ' + r.status + ' ' + (await r.text()).slice(0, 200) }, 502);
  await cache.put(ck, new Response('1', { headers: { 'Cache-Control': 'public, max-age=60' } }));
  return J({ ok: true });
}

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
    if (ep === 'wish' && req.method === 'POST') {
      if (!okOrigin) return new Response('Nicht erlaubt', { status: 403, headers: cors });
      return wish(req, env, cors);
    }
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
