/* Fundus: täglicher Neuerscheinungs-Check mit Push-Mitteilung.
   Liest alle Konten unter artifacts/fundus/users, prüft Merkliste und „Gefällt mir“,
   schickt pro Nutzer eine gebündelte Mitteilung an alle aktiven Geräte.
   Bereits gemeldete Ereignisse stehen in artifacts/fundus/pushstate/{uid}. */
import admin from 'firebase-admin';
import webpush from 'web-push';

const env=k=>{const v=process.env[k];if(!v)throw new Error('Umgebungsvariable fehlt: '+k);return v};
admin.initializeApp({credential:admin.credential.cert(JSON.parse(env('FIREBASE_SA')))});
const db=admin.firestore();
webpush.setVapidDetails('mailto:'+(process.env.VAPID_MAIL||'fundus@lychtlos.github.io'),env('VAPID_PUBLIC'),env('VAPID_PRIVATE'));
const TMDB=process.env.TMDB_KEY||'c59452df2b5d2d720c1e7b4b1bafd42f';
const GB=process.env.GB_KEY||'AIzaSyDhZdIY3AfJDAcTcx1fSMoKfbiDW7QPBpc';
const DRY=process.env.DRY_RUN==='1';

const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const TODAY=new Intl.DateTimeFormat('sv-SE',{timeZone:'Europe/Berlin'}).format(new Date());
const dd=s=>Math.round((Date.parse(String(s).slice(0,10)+'T12:00:00Z')-Date.parse(TODAY+'T12:00:00Z'))/864e5);
const ep=(s,e)=>'S'+String(s).padStart(2,'0')+'E'+String(e).padStart(2,'0');
const tkey=(t,a)=>String(t||'').toLowerCase().replace(/[^a-z0-9äöüß]/g,'').slice(0,40)+'|'+String(a||'').toLowerCase().trim().split(/\s+/).pop();
const baseName=n=>String(n).replace(/\s+(Amazon|Apple TV|Apple TV Plus|Prime Video|Roku Premium)?\s*Channels?$/i,'').replace(/\s+(Standard|Basic)?\s*with Ads$/i,'').replace(/\s+Free$/i,'').replace(/\+$/,' Plus').trim();
let lastItunes=0;
async function j(url,{itunes=false}={}){
  if(itunes){const w=3500-(Date.now()-lastItunes);if(w>0)await sleep(w);lastItunes=Date.now()}
  for(let i=0;i<3;i++){
    const r=await fetch(url,{headers:{'user-agent':'Fundus/1.0 (github.com/lychtlos/Fundus)'}});
    if(r.status===429||r.status===403&&itunes){await sleep(5000*(i+1));continue}
    if(!r.ok)throw new Error(r.status+' '+url.split('?')[0]);
    return r.json();
  }
  throw new Error('Limit '+url.split('?')[0]);
}
const tmdb=(p,q='')=>j('https://api.themoviedb.org/3'+p+'?api_key='+TMDB+'&language=de-DE'+q);

/* Ereignisse je Eintrag: {id,title,text} – nur, was in den letzten Tagen passiert ist.
   Serien: Termine sind Erstausstrahlung (meist USA, abends = bei uns nachts),
   daher Meldung am Tag nach dem Sendetermin. */
async function serie(it){
  const ev=[];
  if(String(it.id).startsWith('tvm')){
    const s=await j('https://api.tvmaze.com/shows/'+String(it.id).slice(3)+'?embed[]=nextepisode&embed[]=previousepisode');
    const em=s._embedded||{};
    for(const e of [em.nextepisode,em.previousepisode])if(e&&e.airdate&&dd(e.airdate)<=-1&&dd(e.airdate)>=-2)
      ev.push({id:it.k+':'+ep(e.season,e.number),title:it.title,text:(e.number===1?'Neue Staffel: ':'Neue Folge: ')+ep(e.season,e.number)+(e.name?' · '+e.name:'')});
    return ev;
  }
  const d=await tmdb('/tv/'+it.id);
  for(const e of [d.next_episode_to_air,d.last_episode_to_air])if(e&&e.air_date&&dd(e.air_date)<=-1&&dd(e.air_date)>=-2)
    ev.push({id:it.k+':'+ep(e.season_number,e.episode_number),title:it.title,text:(e.episode_number===1?'Neue Staffel: ':'Neue Folge: ')+ep(e.season_number,e.episode_number)+(e.name?' · '+e.name:'')});
  return ev;
}
async function musik(it){
  if(/^\d+$/.test(String(it.id))){
    const d=await j('https://api.deezer.com/artist/'+it.id+'/albums?limit=50');
    return (d.data||[]).filter(a=>a.release_date&&dd(a.release_date)<=0&&dd(a.release_date)>=-3)
      .map(a=>({id:'musik:alb:'+a.id,title:it.title,text:(a.record_type==='single'?'Neue Single: ':a.record_type==='ep'?'Neue EP: ':'Neues Album: ')+a.title}));
  }
  const d=await j('https://itunes.apple.com/search?term='+encodeURIComponent(it.title)+'&entity=album&country=DE&limit=25',{itunes:true});
  const n=String(it.title).toLowerCase();
  return (d.results||[]).filter(a=>String(a.artistName||'').toLowerCase()===n&&a.releaseDate&&dd(a.releaseDate)<=0&&dd(a.releaseDate)>=-3)
    .map(a=>({id:'musik:italb:'+a.collectionId,title:it.title,text:'Neu: '+a.collectionName}));
}
async function podcast(it){
  if(String(it.id).startsWith('f')){
    const d=await j('https://api.fyyd.de/0.2/podcast/episodes?podcast_id='+String(it.id).slice(1)+'&count=1');
    const e=d.data&&d.data.episodes&&d.data.episodes[0];
    return e&&e.pubdate&&dd(e.pubdate)>=-1?[{id:'podcast:fy:'+e.id,title:it.title,text:'Neue Folge: '+e.title}]:[];
  }
  const d=await j('https://itunes.apple.com/lookup?id='+it.id+'&entity=podcastEpisode&limit=1&country=DE',{itunes:true});
  const e=(d.results||[]).find(r=>r.wrapperType==='podcastEpisode'||r.kind==='podcast-episode');
  return e&&e.releaseDate&&dd(e.releaseDate)>=-1?[{id:'podcast:ep:'+e.trackId,title:it.title,text:'Neue Folge: '+e.trackName}]:[];
}
async function buch(it){
  const who=it.authorName||String(it.sub||'').split(' · ')[0];if(!who)return [];
  const d=await j('https://www.googleapis.com/books/v1/volumes?q='+encodeURIComponent('inauthor:"'+who+'"')+'&langRestrict=de&orderBy=newest&printType=books&maxResults=20&country=DE&key='+GB);
  const wl=who.toLowerCase();
  return (d.items||[]).map(v=>v.volumeInfo||{}).filter(i=>i.language==='de'&&(i.authors||[]).some(a=>a.toLowerCase()===wl)&&/^\d{4}-\d\d-\d\d$/.test(i.publishedDate||'')&&dd(i.publishedDate)<=0&&dd(i.publishedDate)>=-7)
    .map(i=>({id:'buch:new:'+tkey(i.title,(i.authors||[])[0]),title:who,text:'Neues Buch: '+i.title}));
}
const REL_T={3:'Kinostart',4:'Digital erhältlich',5:'Auf DVD & Blu-ray'};
async function filmDates(it){
  const d=await j('https://api.themoviedb.org/3/movie/'+it.id+'/release_dates?api_key='+TMDB);
  const de=(d.results||[]).find(r=>r.iso_3166_1==='DE');const out={};
  (de&&de.release_dates||[]).forEach(r=>{const t=r.type===2?3:r.type;if(!REL_T[t])return;const dt=String(r.release_date).slice(0,10);if(!out[t]||dt<out[t])out[t]=dt});
  return Object.entries(out).filter(([,dt])=>dd(dt)<=0&&dd(dt)>=-1)
    .map(([t,dt])=>({id:it.k+':rel'+t+':'+dt,title:it.title,text:t==='3'?'Heute Kinostart in Deutschland':t==='4'?'Jetzt digital zum Kaufen oder Leihen':'Jetzt auf DVD & Blu-ray'}));
}
async function film(it,user,state){
  const d=await tmdb('/movie/'+it.id+'/watch/providers','');const r=(d.results&&d.results.DE)||{};
  const mine=new Set((user.settings&&user.settings.providers)||[]);
  const pool=[...(r.flatrate||[]).filter(p=>mine.has(p.provider_id))].concat(user.settings&&user.settings.includeFree!==false?[...(r.free||[]),...(r.ads||[])]:[]);
  const ids=pool.map(p=>p.provider_id);const prev=state.av[it.k];state.av[it.k]=ids;
  if(!prev)return [];                                   // erster Lauf: nur merken
  const fresh=pool.filter(p=>!prev.includes(p.provider_id));
  return fresh.length?[{id:it.k+':av:'+ids.sort().join('-'),title:it.title,text:'Jetzt bei '+[...new Set(fresh.map(p=>baseName(p.provider_name)))].join(', ')}]:[];
}

/* Wochen-Digest: sonntags die 5 besten neuen Vorschläge (Filme, Serien, Musik) */
const WEEKDAY=new Intl.DateTimeFormat('en-US',{timeZone:'Europe/Berlin',weekday:'short'}).format(new Date());
const WEEK=(()=>{const d=new Date(TODAY+'T12:00:00Z');const t=new Date(Date.UTC(d.getUTCFullYear(),d.getUTCMonth(),d.getUTCDate()));const day=t.getUTCDay()||7;t.setUTCDate(t.getUTCDate()+4-day);const y=new Date(Date.UTC(t.getUTCFullYear(),0,1));return t.getUTCFullYear()+'-W'+Math.ceil(((t-y)/864e5+1)/7)})();
const FORCE_DIGEST=process.env.FORCE_DIGEST==='1';
async function digest(user){
  const items=Object.values(user.items||{});const known=new Set(items.map(x=>x.k));
  const cand=new Map();
  const add=(k,title,m,score,extra)=>{if(known.has(k))return;const c=cand.get(k)||{k,title,m,score:0,...extra};c.score+=score;cand.set(k,c)};
  for(const m of ['film','serie']){
    const type=m==='film'?'movie':'tv';
    const seeds=items.filter(x=>x.m===m&&(x.status==='love'||x.status==='like')&&/^\d+$/.test(String(x.id))).sort((a,b)=>(b.status==='love')-(a.status==='love')||b.ts-a.ts).slice(0,15);
    for(const [i,s] of seeds.entries()){
      const w=s.status==='love'?2:1-0.5*i/Math.max(1,seeds.length-1);
      try{const d=await j('https://api.themoviedb.org/3/'+type+'/'+s.id+'/recommendations?api_key='+TMDB+'&language=de-DE');
        const rl={minYear:0,minR:0,exGid:[],...(((user.settings||{}).rules||{})[m]||{})};
        (d.results||[]).filter(r=>{const y=parseInt((r.release_date||r.first_air_date||'').slice(0,4));if(rl.minYear&&y&&y<rl.minYear)return false;
          if(rl.minR&&r.vote_count>=20&&r.vote_average<rl.minR)return false;if((rl.exGid||[]).length&&(r.genre_ids||[]).some(g=>rl.exGid.includes(g)))return false;return true})
          .forEach((r,pos)=>add(m+':'+r.id,r.title||r.name,m,w*(1-pos/60)*((r.vote_average||6)/7.5),{id:r.id,type}))}catch(e){}
      await sleep(120);
    }
  }
  const artists=items.filter(x=>x.m==='musik'&&(x.status==='love'||x.status==='like')&&/^\d+$/.test(String(x.id))).sort((a,b)=>(b.status==='love')-(a.status==='love')||b.ts-a.ts).slice(0,10);
  for(const s of artists){try{const d=await j('https://api.deezer.com/artist/'+s.id+'/related?limit=15');(d.data||[]).forEach((a,pos)=>add('musik:'+a.id,a.name,'musik',(s.status==='love'?1.4:0.7)*(1-pos/30)))}catch(e){}}
  const top=[...cand.values()].sort((a,b)=>b.score-a.score).slice(0,25);
  const mine=new Set((user.settings&&user.settings.providers)||[]);
  for(const c of top.filter(c=>c.type).slice(0,15)){
    try{const d=await j('https://api.themoviedb.org/3/'+c.type+'/'+c.id+'/watch/providers?api_key='+TMDB);const r=(d.results&&d.results.DE)||{};
      const hit=(r.flatrate||[]).find(p=>mine.has(p.provider_id))||(user.settings&&user.settings.includeFree!==false?[...(r.free||[]),...(r.ads||[])][0]:null);
      if(hit){c.prov=baseName(hit.provider_name);c.score*=1.3}}catch(e){}
  }
  const pick=[];const per={film:0,serie:0,musik:0};
  for(const c of top.sort((a,b)=>b.score-a.score)){if(pick.length>=5)break;if(per[c.m]>=2)continue;per[c.m]++;pick.push(c)}
  return pick;
}
const LBL_M={film:'Film',serie:'Serie',musik:'Musik'};

/* Games (RAWG): Merkliste → Erscheinungstag; Gefällt mir → neues Spiel des Studios erschienen */
const RAWG_ENV=process.env.RAWG_KEY||'';
async function game(it,user){
  const k=(user.settings&&user.settings.rawgKey)||RAWG_ENV;if(!k)return [];
  const g=await j('https://api.rawg.io/api/games/'+it.id+'?key='+k);
  if(it.status==='watch')return g.released&&dd(g.released)<=0&&dd(g.released)>=-1?[{id:it.k+':rel:'+g.released,title:it.title,text:'Erscheint heute'}]:[];
  const dev=(g.developers||[])[0];if(!dev)return [];
  const r=await j('https://api.rawg.io/api/games?key='+k+'&developers='+dev.id+'&dates='+TODAY.slice(0,8)+'01,'+TODAY+'&ordering=-released&page_size=10');
  return (r.results||[]).filter(x=>x.id!==it.id&&x.released&&dd(x.released)<=0&&dd(x.released)>=-1).map(x=>({id:'game:dev:'+x.id,title:dev.name,text:'Neues Spiel: '+x.name}));
}
/* Konzerte (Ticketmaster): neue Termine im Umkreis; erster Lauf merkt nur den Bestand */
const TM_ENV=process.env.TM_KEY||'n2zn7nBOmZ6OjjIcVFQfRaApC2hhRnhh';
const geoCache={};
async function geo(plz){if(!geoCache[plz]){const d=await j('https://api.zippopotam.us/de/'+plz);const p=(d.places||[])[0];geoCache[plz]=p?{lat:+p.latitude,lon:+p.longitude}:null}return geoCache[plz]}
function geohash(lat,lon,prec=6){const B='0123456789bcdefghjkmnpqrstuvwxyz';let la=[-90,90],lo=[-180,180],h='',bit=0,ch=0,even=true;
  while(h.length<prec){const r=even?lo:la,v=even?lon:lat,mid=(r[0]+r[1])/2;if(v>mid){ch|=16>>bit;r[0]=mid}else r[1]=mid;even=!even;if(bit<4)bit++;else{h+=B[ch];bit=0;ch=0}}return h}
const normName=s=>String(s||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/ł/g,'l').replace(/[^a-z0-9]/gi,'').toLowerCase();
let lastTm=0;
async function tmj(url){const w=260-(Date.now()-lastTm);if(w>0)await sleep(w);lastTm=Date.now();return j(url)}
async function concerts(it,user){
  const st=user.settings||{};const k=st.tmKey||TM_ENV;if(!k||!/^\d{5}$/.test(st.plz||''))return [];
  const home=await geo(st.plz);if(!home)return [];const rad=+st.radius||150;
  const a=await tmj('https://app.ticketmaster.com/discovery/v2/attractions.json?apikey='+k+'&classificationName=music&size=10&keyword='+encodeURIComponent(it.title));
  const at=((a._embedded||{}).attractions||[]).find(x=>normName(x.name)===normName(it.title));if(!at)return [];
  const q=rad>=1000?'&countryCode=DE':'&geoPoint='+geohash(home.lat,home.lon)+'&radius='+rad+'&unit=km';
  const e=await tmj('https://app.ticketmaster.com/discovery/v2/events.json?apikey='+k+'&attractionId='+at.id+'&size=50&sort=date,asc&locale=*'+q);
  return ((e._embedded||{}).events||[]).filter(x=>x.dates&&x.dates.start&&x.dates.start.localDate&&dd(x.dates.start.localDate)>=0).map(x=>{const v=((x._embedded||{}).venues||[])[0]||{};
    const dt=x.dates.start.localDate.split('-');return {id:'konzert:'+x.id,kind:'konzert',title:it.title+' live',text:dt[2]+'.'+dt[1]+'.'+dt[0]+' · '+[v.name,v.city&&v.city.name].filter(Boolean).join(', ')}});
}

async function runUser(doc){
  const user=doc.data();const uid=doc.id;
  const subs=Object.entries(user.subs||{}).filter(([,s])=>!s.off&&s.endpoint&&s.keys);
  if(!subs.length)return {uid,skipped:'keine Geräte'};
  const stRef=db.collection('artifacts').doc('fundus').collection('pushstate').doc(uid);
  const stSnap=await stRef.get();const state={sent:{},av:{},...(stSnap.exists?stSnap.data():{})};
  const items=Object.values(user.items||{});
  const pick=(m,sts)=>items.filter(x=>x.m===m&&(sts.includes(x.status)||sts.includes('like')&&x.status==='love'));
  const jobs=[...pick('serie',['like','watch']).map(it=>()=>serie(it)),...pick('musik',['like','watch']).map(it=>()=>musik(it)),
    ...pick('podcast',['like','watch']).map(it=>()=>podcast(it)),...pick('buch',['like','watch']).map(it=>()=>buch(it)),
    ...pick('film',['watch']).map(it=>()=>film(it,user,state)),...pick('film',['watch']).map(it=>()=>filmDates(it)),
    ...pick('game',['like','watch']).map(it=>()=>game(it,user)),
    ...pick('musik',['like','watch']).map(it=>()=>concerts(it,user))];
  const events=[];let errors=0;
  for(const job of jobs){try{events.push(...await job())}catch(e){errors++;console.warn(uid.slice(0,6),e.message)}await sleep(150)}
  const fresh=[];const seen=new Set();
  const firstConcerts=!state.concertInit;
  for(const e of events){if(state.sent[e.id]||seen.has(e.id))continue;seen.add(e.id);
    if(e.kind==='konzert'&&firstConcerts){state.sent[e.id]=TODAY;continue}   // Bestand beim ersten Lauf nur merken
    fresh.push(e)}
  if(events.some(e=>e.kind==='konzert')||((user.settings||{}).plz&&((user.settings||{}).tmKey||TM_ENV)))state.concertInit=true;
  let sent=0,dead=[];
  const send=async(payload)=>{for(const [id,s] of subs){
      if(DRY){console.log('[Testlauf]',uid.slice(0,6),payload.title,'|',payload.body.replace(/\n/g,' / '));sent++;continue}
      try{await webpush.sendNotification({endpoint:s.endpoint,keys:s.keys},JSON.stringify(payload),{TTL:86400});sent++}
      catch(e){if(e.statusCode===404||e.statusCode===410)dead.push(id);else console.warn('Push-Fehler',e.statusCode,e.body)}}};
  if(user.settings&&user.settings.digest!==false&&(WEEKDAY==='Sun'||FORCE_DIGEST)&&state.digestWeek!==WEEK){
    try{const d=await digest(user);
      if(d.length){await send({title:'Deine Woche: '+d.length+' neue Vorschläge',body:d.map(c=>LBL_M[c.m]+': '+c.title+(c.prov?' (bei '+c.prov+')':'')).join('\n'),url:'./#entdecken',tag:'fundus-digest-'+WEEK});state.digestWeek=WEEK}
    }catch(e){console.warn('Digest',e.message)}
  }
  if(fresh.length){
    const title=fresh.length===1?fresh[0].title:'Fundus: '+fresh.length+' Neuerscheinungen';
    const body=fresh.length===1?fresh[0].text:fresh.slice(0,5).map(e=>e.title+' – '+e.text).join('\n')+(fresh.length>5?'\n… und '+(fresh.length-5)+' weitere':'');
    await send({title,body,url:'./#neu',tag:'fundus-'+TODAY});
    fresh.forEach(e=>state.sent[e.id]=TODAY);
  }
  const lim=new Date(Date.now()-120*864e5).toISOString().slice(0,10);
  const limK=new Date(Date.now()-500*864e5).toISOString().slice(0,10);
  for(const k in state.sent)if(state.sent[k]<(k.startsWith('konzert:')?limK:lim))delete state.sent[k];
  if(!DRY){
    await stRef.set({sent:state.sent,av:state.av,concertInit:!!state.concertInit,digestWeek:state.digestWeek||null,lastRun:TODAY,lastCount:fresh.length},{merge:false});
    if(dead.length){const upd={};dead.forEach(id=>upd['subs.'+id]={...user.subs[id],off:true,ts:Date.now()});await doc.ref.update(upd)}
  }
  return {uid:uid.slice(0,6),items:items.length,events:fresh.length,sent,dead:dead.length,errors};
}

const users=await db.collection('artifacts').doc('fundus').collection('users').get();
console.log('Stichtag',TODAY,'·',users.size,'Konten',DRY?'· Testlauf ohne Versand':'');
if(!users.size)throw new Error('Keine Konten gefunden – Dienstkonto gehört evtl. zu einem anderen Projekt');
for(const d of users.docs){try{console.log(await runUser(d))}catch(e){console.error('Konto',d.id.slice(0,6),e.message)}}
