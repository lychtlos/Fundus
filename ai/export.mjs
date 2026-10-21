/* Schritt 1: Konten aus Firestore lesen, je Konto ein Profil (profile.md) für die KI schreiben.
   Ohne FIREBASE_SA: LOCAL_USERS=datei.json ({uid:{...}}) für Tests. */
import fs from 'node:fs';
import {MEDIA,LBL,j,TMDB,writeJ} from './lib.mjs';

const MIN_LIKES=5;
async function loadUsers(){
  if(process.env.LOCAL_USERS)return Object.entries(JSON.parse(fs.readFileSync(process.env.LOCAL_USERS,'utf8')));
  const {default:admin}=await import('firebase-admin');
  admin.initializeApp({credential:admin.credential.cert(JSON.parse(process.env.FIREBASE_SA))});
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
  if(st.ai===false){console.log(uid.slice(0,6)+'…: KI-Tipps ausgeschaltet');continue}
  const items=Object.values(u.items||{});
  const pos=items.filter(x=>x.status==='like'||x.status==='love');
  if(pos.length<MIN_LIKES){console.log(uid.slice(0,6)+'…: zu wenig Bewertungen ('+pos.length+')');continue}
  const dir='work/u'+(++n);fs.mkdirSync(dir);
  const by=(m,s)=>items.filter(x=>x.m===m&&s.includes(x.status)).sort((a,b)=>(b.ts||0)-(a.ts||0));
  let p='# Profil\n\nBewertungen dieses Kontos. ♥ = Lieblingstitel (zählt doppelt), dann „Gefällt mir“ (neueste zuerst).\n';
  for(const m of MEDIA){
    const love=by(m,['love']),like=by(m,['like']).slice(0,40),bad=by(m,['dislike']).slice(0,25),watch=by(m,['watch']).slice(0,25),hide=by(m,['hide']).slice(0,30);
    if(!love.length&&!like.length&&!watch.length&&!bad.length)continue;
    p+='\n## '+LBL[m]+'\n';
    if(love.length)p+='♥ Lieblinge:\n'+love.map(line).join('\n')+'\n';
    if(like.length)p+='Gefällt mir:\n'+like.map(line).join('\n')+'\n';
    if(bad.length)p+='Gefällt NICHT (Ähnliches meiden):\n'+bad.map(line).join('\n')+'\n';
    if(watch.length)p+='Schon auf der Merkliste:\n'+watch.map(line).join('\n')+'\n';
    if(hide.length)p+='Kein Interesse: '+hide.map(x=>x.title).join(' · ')+'\n';
  }
  const R=st.rules||{};const rl=Object.entries(R).map(([m,r])=>[m,[r.minYear?'nicht vor '+r.minYear:'',r.minR?'Bewertung ab '+r.minR:'',(r.exG||[]).length?'keine Genres: '+r.exG.join(', '):''].filter(Boolean)]).filter(x=>x[1].length);
  p+='\n# Einstellungen\n';
  p+='Regeln: '+(rl.length?rl.map(([m,a])=>LBL[m]+' – '+a.join(', ')).join('; '):'keine')+'\n';
  const pv=await provNames(st.providers);
  p+='Streamingdienste: '+(pv.length?pv.join(', '):'keine angegeben')+(st.includeFree!==false?' (plus kostenlose Mediatheken)':'')+'\n';
  p+='Game-Plattformen: '+((st.platforms||[]).length?st.platforms.map(x=>PLAT[x]||x).join(', '):'alle')+'\n';
  const hist=((u.ai||{}).hist||[]);
  if(hist.length)p+='\n# Bereits als KI-Tipp gezeigt (nicht wiederholen)\n'+hist.map(h=>h.t).join(' · ')+'\n';
  fs.writeFileSync(dir+'/profile.md',p);
  writeJ(dir+'/user.json',{uid,items:u.items||{},settings:st,ai:{hist},provNames:pv});
  index.push({uid,dir,likes:pos.length});
  console.log(dir+': '+pos.length+' positive Bewertungen, '+p.length+' Zeichen Profil');
}
writeJ('work/index.json',index);
if(!index.length)console.log('Keine Konten mit genug Bewertungen.');
