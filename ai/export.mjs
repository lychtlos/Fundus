/* Schritt 1: Konten aus Firestore lesen, je Konto ein Profil (profile.md) für die KI schreiben.
   Ohne FIREBASE_SA: LOCAL_USERS=datei.json ({uid:{...}}) für Tests. */
import fs from 'node:fs';
import {MEDIA,LBL,j,TMDB,writeJ,WEEK,WEEKDAY} from './lib.mjs';

const WISH=process.env.ONLY_UID||'';
const MIN_LIKES=WISH?1:5;
/* optional: nur diese Konten (GitHub-Variable AI_UIDS, kommagetrennt) – schützt das Abo vor fremden Konten */
const ALLOW=new Set(String(process.env.AI_UIDS||'').split(',').map(x=>x.trim()).filter(Boolean));
async function loadUsers(){
  if(process.env.LOCAL_USERS)return Object.entries(JSON.parse(fs.readFileSync(process.env.LOCAL_USERS,'utf8')));
  const {default:admin}=await import('firebase-admin');
  admin.initializeApp({credential:admin.credential.cert(JSON.parse(process.env.FIREBASE_SA))});
  if(WISH){const d=await admin.firestore().doc('artifacts/fundus/users/'+WISH).get();return d.exists?[[d.id,d.data()]]:[]}
  const s=await admin.firestore().collection('artifacts/fundus/users').get();
  return s.docs.map(d=>[d.id,d.data()]);
}
const baseName=n=>String(n).replace(/\s+(Amazon|Apple TV|Apple TV Plus|Prime Video|Roku Premium)?\s*Channels?$/i,'').replace(/\s+(Standard|Basic)?\s*with Ads$/i,'').replace(/\s+Free$/i,'').replace(/\+$/,' Plus').trim();
let PROV=null;
async function provNames(ids){
  if(!ids||!ids.length)return [];
  if(!PROV){PROV={};for(const t of ['movie','tv'])try{const d=await j('https://api.themoviedb.org/3/watch/providers/'+t+'?api_key='+TMDB+'&watch_region=DE');(d.results||[]).forEach(p=>PROV[p.provider_id]=p.provider_name)}catch(e){}}
  return [...new Set(ids.map(i=>PROV[i]?baseName(PROV[i]):null).filter(Boolean))];
}
const PLAT={pc:'PC',playstation:'PlayStation',xbox:'Xbox',nintendo:'Nintendo Switch',mobile:'Smartphone'};
const line=it=>{
  const bits=[it.year&&it.m!=='buch'?it.year:'',it.m==='buch'?it.authorName||'':'',(it.genres||[]).slice(0,3).join(', ')].filter(Boolean);
  return '- '+it.title+(bits.length?' ('+bits.join('; ')+')':'');
};

const users=await loadUsers();
fs.rmSync('work',{recursive:true,force:true});fs.mkdirSync('work');
const index=[];let n=0;
for(const [uid,u] of users){
  const st=u.settings||{};
  if(ALLOW.size&&!ALLOW.has(uid)){console.log(uid.slice(0,6)+'…: nicht freigegeben (AI_UIDS)');continue}
  if(WISH&&!(u.wishReq&&u.wishReq.q)){console.log('Kein Wunsch hinterlegt');continue}
  if(!WISH&&st.ai===false){console.log(uid.slice(0,6)+'…: KI-Tipps ausgeschaltet');continue}
  const items=Object.values(u.items||{});
  items.forEach(x=>{if(x.status==='follow'){delete x.status;x.fol=true}});
  const pos=items.filter(x=>x.status==='like'||x.status==='love'||x.fol&&!x.status);
  if(pos.length<MIN_LIKES){console.log(uid.slice(0,6)+'…: zu wenig Bewertungen ('+pos.length+')');continue}
  const dir='work/u'+(++n);fs.mkdirSync(dir);
  const by=(m,s)=>items.filter(x=>x.m===m&&s.includes(x.status)).sort((a,b)=>(b.ts||0)-(a.ts||0));
  let p='# Profil\n\nBewertungen dieses Kontos. ♥ = Lieblingstitel (zählt doppelt), dann „Gefällt mir“ (neueste zuerst).\n';
  for(const m of MEDIA){
    const love=by(m,['love']),like=by(m,['like']).slice(0,40),bad=by(m,['dislike']).slice(0,25),watch=by(m,['watch']).slice(0,25),hide=by(m,['hide']).slice(0,30);
    if(!love.length&&!like.length&&!watch.length&&!bad.length&&!items.some(x=>x.m===m&&x.fol))continue;
    p+='\n## '+LBL[m]+'\n';
    if(love.length)p+='♥ Lieblinge:\n'+love.map(line).join('\n')+'\n';
    const fol=items.filter(x=>x.m===m&&x.fol);if(fol.length)p+='Verfolgt (schaut gerade, gefällt):\n'+fol.map(line).join('\n')+'\n';
    if(like.length)p+='Gefällt mir:\n'+like.map(line).join('\n')+'\n';
    if(bad.length)p+='Gefällt NICHT (Ähnliches meiden):\n'+bad.map(line).join('\n')+'\n';
    if(watch.length)p+='Schon auf der Merkliste:\n'+watch.map(line).join('\n')+'\n';
    const hr=hide.filter(x=>x.nr),hn=hide.filter(x=>!x.nr);
    if(hr.length)p+='Abgelehnt mit Grund (wichtig!): '+hr.map(x=>x.title+' ('+x.nr+')').join(' · ')+'\n';
    if(hn.length)p+='Kein Interesse: '+hn.map(x=>x.title).join(' · ')+'\n';
  }
  const R=st.rules||{};const rl=Object.entries(R).map(([m,r])=>[m,[r.minYear?'nicht vor '+r.minYear:'',r.minR?'Bewertung ab '+r.minR:'',(r.exG||[]).length?'keine Genres: '+r.exG.join(', '):''].filter(Boolean)]).filter(x=>x[1].length);
  p+='\n# Einstellungen\n';
  p+='Regeln: '+(rl.length?rl.map(([m,a])=>LBL[m]+' – '+a.join(', ')).join('; '):'keine')+'\n';
  const pv=await provNames(st.providers);
  p+='Streamingdienste: '+(pv.length?pv.join(', '):'keine angegeben')+(st.includeFree!==false?' (plus kostenlose Mediatheken)':'')+'\n';
  p+='Game-Plattformen: '+((st.platforms||[]).length?st.platforms.map(x=>PLAT[x]||x).join(', '):'alle')+'\n';
  const hist=((u.ai||{}).hist||[]);
  if(hist.length)p+='\n# Bereits als KI-Tipp gezeigt (nicht wiederholen)\n'+hist.map(h=>h.t).join(' · ')+'\n';
  /* Zusatzaufgaben */
  const tasks=[];
  if(WISH){writeJ(dir+'/wish.json',{id:u.wishReq.id,q:String(u.wishReq.q).slice(0,300),ts:u.wishReq.ts});fs.writeFileSync(dir+'/profile.md',p);
    writeJ(dir+'/user.json',{uid,items:u.items||{},settings:st,ai:{hist:[]},provNames:pv,tasks:[],week:WEEK});index.push({uid,dir,likes:pos.length});continue}
  const watch=items.filter(x=>x.status==='watch').sort((a,b)=>(b.ts||0)-(a.ts||0)).slice(0,60);
  if(watch.length>=3){tasks.push('order');writeJ(dir+'/watch.json',watch.map(x=>({k:x.k,m:x.m,title:x.title,year:x.year||'',by:x.authorName||'',genres:(x.genres||[]).slice(0,3)})))}
  let conc=null;
  if(st.concerts!==false&&/^\d{5}$/.test(st.plz||'')){
    try{const z=await j('https://api.zippopotam.us/de/'+st.plz);const pl=(z.places||[])[0];
      if(pl){const bands=items.filter(x=>x.m==='musik'&&['love','like','watch'].includes(x.status)).sort((a,b)=>(b.status==='love')-(a.status==='love')||(b.ts||0)-(a.ts||0)).map(x=>x.title);
        const N=12,off=((u.ai||{}).concOff||0)%Math.max(1,bands.length);const pick=bands.length<=N?bands:bands.slice(off).concat(bands.slice(0,off)).slice(0,N);
        conc={plz:st.plz,city:pl['place name'],lat:+pl.latitude,lon:+pl.longitude,radius:+st.radius||150,bands:pick,next:(off+N)%Math.max(1,bands.length)};
        if(pick.length){tasks.push('concerts');writeJ(dir+'/concert_job.json',conc)}}}catch(e){console.log('PLZ nicht auflösbar: '+e.message)}
  }
  if(items.some(x=>['musik','buch'].includes(x.m)&&['love','like'].includes(x.status)))tasks.push('radar');
  tasks.push('gametext');
  if(WEEKDAY==='Sun'||process.env.FORCE_DIGEST==='1')tasks.push('digest');
  /* Bedarf: je Bereich sollen rund 20 offene KI-Tipps bereitstehen; was fehlt, wird heute nachgeliefert (3 bis 10) */
  const openTips={};((u.ai||{}).tips||[]).forEach(t=>{if(t&&!(u.items||{})[t.k])openTips[t.m]=(openTips[t.m]||0)+1});
  const need={};MEDIA.forEach(m=>{const has=items.some(x=>x.m===m&&['like','love','watch'].includes(x.status));need[m]=has?Math.max(3,Math.min(10,20-(openTips[m]||0))):0});
  p+='\n# Bedarf heute (Anzahl neuer Tipps je Bereich)\n'+MEDIA.filter(m=>need[m]).map(m=>LBL[m]+': '+need[m]).join(' · ')+'\n';
  p+='\n# Aufgaben heute\n'+tasks.join(', ')+'\n';
  if(conc&&conc.bands.length)p+='Konzerte: Wohnort '+conc.city+' ('+conc.plz+'), Umkreis '+conc.radius+' km. Bands heute: '+conc.bands.join(' · ')+'\n';
  fs.writeFileSync(dir+'/profile.md',p);
  writeJ(dir+'/user.json',{uid,items:u.items||{},settings:st,ai:{hist,gtx:Object.keys((u.ai||{}).gtx||{})},provNames:pv,tasks,conc,week:WEEK,need});
  index.push({uid,dir,likes:pos.length});
  console.log('::notice title=Profil::'+dir+': '+pos.length+' positive Bewertungen, '+p.length+' Zeichen');
}
writeJ('work/index.json',index);
if(!index.length)console.log('Keine Konten mit genug Bewertungen.');
