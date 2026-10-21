/* Wunsch-Suche: Ergebnis prüfen, in Firestore (Feld wish) schreiben, Push senden.
   Tageslimit je Konto: WISH_LIMIT (Standard 15), Zähler in artifacts/fundus/wishstate/{uid}. */
import fs from 'node:fs';
import {readJ,knownSets,norm,TODAY} from './lib.mjs';
const LIMIT=+process.env.WISH_LIMIT||15;
const index=readJ('work/index.json',[]);const err=process.env.AI_ERROR||'';
const note=(l,m)=>console.log((process.env.GITHUB_ACTIONS?'::'+l+' title=Wunsch::':'['+l+'] ')+m);
let db=null,admin=null;
if(process.env.FIREBASE_SA&&process.env.DRY_RUN!=='1'){admin=(await import('firebase-admin')).default;admin.initializeApp({credential:admin.credential.cert(JSON.parse(process.env.FIREBASE_SA))});db=admin.firestore()}
const mode=process.argv[2]||'save';
for(const {uid,dir} of index){
  const w=readJ(dir+'/wish.json',{});
  if(mode==='quota'){ /* vor dem KI-Lauf: Limit prüfen */
    if(!db)continue;const ref=db.doc('artifacts/fundus/wishstate/'+uid);const s=(await ref.get()).data()||{};
    const n=s.day===TODAY?s.n||0:0;
    if(n>=LIMIT){await db.doc('artifacts/fundus/users/'+uid).update({wish:{id:w.id,q:w.q,ts:Date.now(),status:'err',err:'Tageslimit von '+LIMIT+' Wünschen erreicht'},writer:'fundus-ki'});fs.writeFileSync('work/STOP','1');note('warning','Tageslimit erreicht');continue}
    await ref.set({day:TODAY,n:n+1});
    await db.doc('artifacts/fundus/users/'+uid).update({wish:{id:w.id,q:w.q,ts:Date.now(),status:'running'},writer:'fundus-ki'});
    continue;
  }
  const user=readJ(dir+'/user.json',{});const checked=readJ(dir+'/checked.json',{});const out=readJ(dir+'/wish_out.json',{});
  const {keys,names}=knownSets(user);const seen=new Set();const tips=[];
  for(const t of Array.isArray(out.tips)?out.tips:[]){
    const c=checked[t&&t.k];if(!c||c.st!=='ok'||seen.has(t.k))continue;const it=c.item;
    if(keys.has(it.k)||names.has(it.m+'|'+norm(it.title)))continue;
    const why=String(t.why||'').replace(/\s+/g,' ').trim().slice(0,180);if(!why)continue;
    seen.add(it.k);tips.push({...it,why,...(c.extra&&c.extra.mine&&c.extra.mine.length?{prov:c.extra.mine[0]}:{})});if(tips.length>=10)break;
  }
  const res=tips.length?{id:w.id,q:w.q,ts:Date.now(),status:'done',intro:String(out.intro||'').slice(0,160),tips}
    :{id:w.id,q:w.q,ts:Date.now(),status:'err',err:String(out.err||err||'Keine passenden Titel gefunden').slice(0,200)};
  note(tips.length?'notice':'error','„'+w.q+'“ → '+(tips.length?tips.map(t=>t.title).join(' | '):res.err));
  if(!db){fs.writeFileSync(dir+'/wish_result.json',JSON.stringify(res,null,1));continue}
  const ref=db.doc('artifacts/fundus/users/'+uid);await ref.update({wish:res,writer:'fundus-ki'});
  /* Push an alle aktiven Geräte */
  if(process.env.VAPID_PRIVATE){
    const {default:webpush}=await import('web-push');
    webpush.setVapidDetails('mailto:fundus@lychtlos.github.io',process.env.VAPID_PUBLIC,process.env.VAPID_PRIVATE);
    const u=(await ref.get()).data()||{};
    const body=tips.length?tips.slice(0,3).map(t=>t.title).join(', ')+(tips.length>3?' und '+(tips.length-3)+' weitere':''):res.err;
    for(const s of Object.values(u.subs||{}).filter(s=>!s.off&&s.endpoint&&s.keys))
      try{await webpush.sendNotification({endpoint:s.endpoint,keys:s.keys},JSON.stringify({title:'✦ Dein Wunsch: '+String(w.q).slice(0,50),body,url:'./#suchen',tag:'fundus-wish'}),{TTL:3600})}catch(e){}
  }
}
