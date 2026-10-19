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

/* Ereignisse je Eintrag: {id,title,text} – nur, was heute bzw. in den letzten Tagen passiert ist */
async function serie(it){
  const ev=[];
  if(String(it.id).startsWith('tvm')){
    const s=await j('https://api.tvmaze.com/shows/'+String(it.id).slice(3)+'?embed[]=nextepisode&embed[]=previousepisode');
    const em=s._embedded||{};
    for(const e of [em.nextepisode,em.previousepisode])if(e&&e.airdate&&dd(e.airdate)<=0&&dd(e.airdate)>=-1)
      ev.push({id:it.k+':'+ep(e.season,e.number),title:it.title,text:(e.number===1?'Neue Staffel: ':'Neue Folge: ')+ep(e.season,e.number)+(e.name?' · '+e.name:'')});
    return ev;
  }
  const d=await tmdb('/tv/'+it.id);
  for(const e of [d.next_episode_to_air,d.last_episode_to_air])if(e&&e.air_date&&dd(e.air_date)<=0&&dd(e.air_date)>=-1)
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
async function film(it,user,state){
  const d=await tmdb('/movie/'+it.id+'/watch/providers','');const r=(d.results&&d.results.DE)||{};
  const mine=new Set((user.settings&&user.settings.providers)||[]);
  const pool=[...(r.flatrate||[]).filter(p=>mine.has(p.provider_id))].concat(user.settings&&user.settings.includeFree!==false?[...(r.free||[]),...(r.ads||[])]:[]);
  const ids=pool.map(p=>p.provider_id);const prev=state.av[it.k];state.av[it.k]=ids;
  if(!prev)return [];                                   // erster Lauf: nur merken
  const fresh=pool.filter(p=>!prev.includes(p.provider_id));
  return fresh.length?[{id:it.k+':av:'+ids.sort().join('-'),title:it.title,text:'Jetzt bei '+[...new Set(fresh.map(p=>baseName(p.provider_name)))].join(', ')}]:[];
}

async function runUser(doc){
  const user=doc.data();const uid=doc.id;
  const subs=Object.entries(user.subs||{}).filter(([,s])=>!s.off&&s.endpoint&&s.keys);
  if(!subs.length)return {uid,skipped:'keine Geräte'};
  const stRef=db.collection('artifacts').doc('fundus').collection('pushstate').doc(uid);
  const stSnap=await stRef.get();const state={sent:{},av:{},...(stSnap.exists?stSnap.data():{})};
  const items=Object.values(user.items||{});
  const pick=(m,sts)=>items.filter(x=>x.m===m&&sts.includes(x.status));
  const jobs=[...pick('serie',['like','watch']).map(it=>()=>serie(it)),...pick('musik',['like','watch']).map(it=>()=>musik(it)),
    ...pick('podcast',['like','watch']).map(it=>()=>podcast(it)),...pick('buch',['like','watch']).map(it=>()=>buch(it)),
    ...pick('film',['watch']).map(it=>()=>film(it,user,state))];
  const events=[];let errors=0;
  for(const job of jobs){try{events.push(...await job())}catch(e){errors++;console.warn(uid.slice(0,6),e.message)}await sleep(150)}
  const fresh=[];const seen=new Set();
  for(const e of events){if(state.sent[e.id]||seen.has(e.id))continue;seen.add(e.id);fresh.push(e)}
  let sent=0,dead=[];
  if(fresh.length){
    const title=fresh.length===1?fresh[0].title:'Fundus: '+fresh.length+' Neuerscheinungen';
    const body=fresh.length===1?fresh[0].text:fresh.slice(0,5).map(e=>e.title+' – '+e.text).join('\n')+(fresh.length>5?'\n… und '+(fresh.length-5)+' weitere':'');
    const msg=JSON.stringify({title,body,url:'./#neu',tag:'fundus-'+TODAY});
    for(const [id,s] of subs){
      if(DRY){console.log('[Testlauf]',uid.slice(0,6),title,'|',body.replace(/\n/g,' / '));sent++;continue}
      try{await webpush.sendNotification({endpoint:s.endpoint,keys:s.keys},msg,{TTL:86400});sent++}
      catch(e){if(e.statusCode===404||e.statusCode===410)dead.push(id);else console.warn('Push-Fehler',e.statusCode,e.body)}
    }
    fresh.forEach(e=>state.sent[e.id]=TODAY);
  }
  const lim=new Date(Date.now()-120*864e5).toISOString().slice(0,10);
  for(const k in state.sent)if(state.sent[k]<lim)delete state.sent[k];
  if(!DRY){
    await stRef.set({sent:state.sent,av:state.av,lastRun:TODAY,lastCount:fresh.length},{merge:false});
    if(dead.length){const upd={};dead.forEach(id=>upd['subs.'+id]={...user.subs[id],off:true,ts:Date.now()});await doc.ref.update(upd)}
  }
  return {uid:uid.slice(0,6),items:items.length,events:fresh.length,sent,dead:dead.length,errors};
}

const users=await db.collection('artifacts').doc('fundus').collection('users').get();
console.log('Stichtag',TODAY,'·',users.size,'Konten',DRY?'· Testlauf ohne Versand':'');
if(!users.size)throw new Error('Keine Konten gefunden – Dienstkonto gehört evtl. zu einem anderen Projekt');
for(const d of users.docs){try{console.log(await runUser(d))}catch(e){console.error('Konto',d.id.slice(0,6),e.message)}}
