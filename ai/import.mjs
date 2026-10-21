/* Schritt 3: Ergebnisse der KI prüfen und in Firestore schreiben (Feld ai im Nutzer-Dokument).
   Nur geprüfte Daten kommen durch: Tipps aus checked.json, Radar aus radar_checked.json,
   Konzerte mit Datum, Link und Entfernung im Umkreis, Merkliste nur bekannte Einträge.
   Fehlen Tipps, bleiben die alten stehen und es wird ein Fehlerhinweis gesetzt (Exit-Code 1). */
import fs from 'node:fs';
import {readJ,passRules,knownSets,norm,isPopular,dd,km,geocode,WEEK} from './lib.mjs';
const MAX=30,PER_M=5,HIST=200;
const index=readJ('work/index.json',[]);
const err=process.env.AI_ERROR||'';
const model=process.env.AI_MODEL||'';
let db=null;
if(process.env.FIREBASE_SA&&process.env.DRY_RUN!=='1'){const admin=(await import('firebase-admin')).default;admin.initializeApp({credential:admin.credential.cert(JSON.parse(process.env.FIREBASE_SA))});db=admin.firestore()}
const note=(lvl,msg)=>console.log((process.env.GITHUB_ACTIONS?'::'+lvl+' title=KI-Tipps::':'['+lvl+'] ')+msg);
let fail=0;
for(const {uid,dir} of index){
  const user=readJ(dir+'/user.json',{});const checked=readJ(dir+'/checked.json',{});
  const ref=db&&db.doc('artifacts/fundus/users/'+uid);
  const cur=(ref?((await ref.get()).data()||{}).ai:readJ(dir+'/cur_ai.json',{}))||{};
  const now=Date.now();const ai={...cur};const log=[];

  /* 1. Tipps */
  const raw=readJ(dir+'/tips.json',null);
  const {keys,names}=knownSets(user);const seen=new Set();const tips=[];const per={};let pop=0;
  for(const t of Array.isArray(raw)?raw:[]){
    const c=checked[t&&t.k];if(!c||c.st!=='ok'||seen.has(t.k))continue;
    const it=c.item;if(keys.has(it.k)||names.has(it.m+'|'+norm(it.title))||!passRules(user,it))continue;
    const why=String(t.why||'').replace(/\s+/g,' ').trim().slice(0,180);if(!why)continue;
    if((per[it.m]||0)>=PER_M)continue;
    const isPop=isPopular(it,c.extra||{});if(isPop&&pop+1>Math.ceil(MAX/3))continue;
    if(isPop)pop++;per[it.m]=(per[it.m]||0)+1;
    seen.add(it.k);tips.push({...it,why,...(c.extra&&c.extra.mine&&c.extra.mine.length?{prov:c.extra.mine[0]}:{})});
    if(tips.length>=MAX)break;
  }
  while(tips.filter(t=>isPopular(t,checked[t.k].extra||{})).length>Math.ceil(tips.length/3)){const i=tips.map(t=>isPopular(t,checked[t.k].extra||{})).lastIndexOf(true);tips.splice(i,1)}
  if(tips.length>=5){
    ai.at=now;ai.model=model;ai.tips=tips;ai.err='';
    ai.hist=[...tips.map(x=>({k:x.k,t:x.title})),...(cur.hist||[])].slice(0,HIST);
    log.push(tips.length+' Tipps ('+Object.entries(per).map(([m,n])=>m+' '+n).join(', ')+')');
  }else{
    fail++;ai.errAt=now;ai.err=err||(raw?'Zu wenige gültige Tipps ('+tips.length+')':'KI-Lauf ohne Ergebnis');
    note('error',dir+': '+ai.err+' (geprüft: '+Object.keys(checked).length+', Auswahl: '+(Array.isArray(raw)?raw.length:'keine')+')');
  }

  /* 2. Konzerte (Websuche) */
  const job=user.conc;const cj=readJ(dir+'/concerts.json',null);
  if(job&&Array.isArray(cj)){
    const bands=new Map(job.bands.map(b=>[norm(b),b]));const home={lat:job.lat,lon:job.lon};const found=[];
    for(const c of cj.slice(0,60)){
      const a=bands.get(norm(c&&c.artist));const date=String(c&&c.date||'');
      if(!a||!/^\d{4}-\d{2}-\d{2}$/.test(date)||dd(date)<0||dd(date)>450||!/^https?:\/\//.test(c.url||'')||!c.city)continue;
      let g=null;try{g=await geocode(c.city,c.country||'')}catch(e){}
      if(!g)continue;const dist=km(home,g);if(dist>job.radius)continue;
      found.push({id:norm(a)+'-'+date+'-'+norm(c.city).slice(0,12),artist:a,date,city:String(c.city).slice(0,60),venue:String(c.venue||'').slice(0,80),url:c.url,km:dist,note:String(c.note||'').slice(0,60)});
    }
    const checkedB=new Set(job.bands.map(norm));
    const keep=(cur.concerts||[]).filter(c=>dd(c.date)>=0&&!checkedB.has(norm(c.artist)));
    const ids=new Set();ai.concerts=[...keep,...found].filter(c=>!ids.has(c.id)&&ids.add(c.id)).sort((a,b)=>a.date.localeCompare(b.date)).slice(0,80);
    ai.concOff=job.next;
    log.push('Konzerte: '+found.length+' neu bestätigt für '+job.bands.length+' Bands');
  }

  /* 3. Release-Radar */
  const rc=readJ(dir+'/radar_checked.json',null);
  if(rc){const ids=new Set();ai.radar=[...Object.values(rc),...(cur.radar||[])].filter(r=>dd(r.date)>=-45&&!ids.has(r.rid)&&ids.add(r.rid)).slice(0,40);log.push('Radar: '+Object.keys(rc).length)}

  /* 4. Merkliste-Reihenfolge */
  const wl=readJ(dir+'/watch.json',null),ord=readJ(dir+'/order.json',null);
  if(wl&&Array.isArray(ord)){const ok=new Set(wl.map(x=>x.k));const o={};let r=0;
    ord.forEach(x=>{if(x&&ok.has(x.k)&&!o[x.k])o[x.k]={r:r++,why:String(x.why||'').replace(/\s+/g,' ').trim().slice(0,140)}});
    if(r){ai.order=o;log.push('Merkliste sortiert: '+r)}}

  /* 5. Game-Texte */
  const en=readJ(dir+'/gtx_en.json',{}),de=readJ(dir+'/gtx_de.json',null);
  if(de&&typeof de==='object'){const g={...(cur.gtx||{})};let n=0;
    for(const [k,v] of Object.entries(de))if(en[k]&&typeof v==='string'&&v.trim().length>20){g[k]=v.trim().slice(0,1200);n++}
    const ks=Object.keys(g);if(ks.length>60)ks.slice(0,ks.length-60).forEach(k=>delete g[k]);
    ai.gtx=g;if(n)log.push('Game-Texte: '+n)}

  /* 6. Digest-Text (sonntags) */
  if(fs.existsSync(dir+'/digest.txt')){const t=fs.readFileSync(dir+'/digest.txt','utf8').replace(/\s+/g,' ').trim().slice(0,320);if(t.length>30){ai.digest={week:WEEK,text:t};log.push('Digest-Text')}}

  note('notice',dir+': '+log.join(' · ')+(tips.length?' → '+tips.map(x=>x.title).join(' | '):''));
  if(ref)await ref.update({ai,writer:'fundus-ki'});
  else fs.writeFileSync(dir+'/result.json',JSON.stringify(ai,null,1));
}
if(!index.length)console.log('Nichts zu schreiben.');
process.exit(fail?1:0);
