/* Schritt 3: ausgewählte Tipps (work/uN/tips.json) gegen checked.json abgleichen und in Firestore schreiben.
   Nur geprüfte Schlüssel kommen durch, Listen und Regeln werden erneut geprüft.
   Fehlen Tipps, bleiben die alten stehen und es wird ein Fehlerhinweis gesetzt (Exit-Code 1). */
import fs from 'node:fs';
import {readJ,passRules,knownSets,norm,isPopular} from './lib.mjs';
const MAX=30,PER_M=5,HIST=200;
const index=readJ('work/index.json',[]);
const err=process.env.AI_ERROR||'';
const model=process.env.AI_MODEL||'';
let db=null,admin=null;
if(process.env.FIREBASE_SA&&process.env.DRY_RUN!=='1'){admin=(await import('firebase-admin')).default;admin.initializeApp({credential:admin.credential.cert(JSON.parse(process.env.FIREBASE_SA))});db=admin.firestore()}
let fail=0;
for(const {uid,dir} of index){
  const user=readJ(dir+'/user.json',{});const checked=readJ(dir+'/checked.json',{});
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
  /* Mainstream-Anteil nachträglich auf ein Drittel begrenzen */
  while(tips.filter(t=>isPopular(t,checked[t.k].extra||{})).length>Math.ceil(tips.length/3)){const i=tips.map(t=>isPopular(t,checked[t.k].extra||{})).lastIndexOf(true);tips.splice(i,1)}
  const now=Date.now();
  let ai;
  if(tips.length>=5){
    const hist=[...tips.map(x=>({k:x.k,t:x.title})),...(user.ai.hist||[])].slice(0,HIST);
    ai={at:now,model,tips,hist,err:''};
    console.log('::notice title=KI-Tipps::'+dir+': '+tips.length+' Tipps → '+tips.map(x=>x.m+':'+x.title).join(' | '));
  }else{
    fail++;const msg=err||(raw?'Zu wenige gültige Tipps ('+tips.length+')':'KI-Lauf ohne Ergebnis');
    console.log('::error title=KI-Tipps::'+dir+': '+msg+' (geprüft: '+Object.keys(checked).length+', Auswahl: '+(Array.isArray(raw)?raw.length:'keine')+')');
    ai={errAt:now,err:msg};
  }
  if(db){
    const ref=db.doc('artifacts/fundus/users/'+uid);
    const cur=(await ref.get()).data()||{};
    await ref.update({ai:{...(cur.ai||{}),...ai},writer:'fundus-ki'});
  }else fs.writeFileSync(dir+'/result.json',JSON.stringify(ai,null,1));
}
if(!index.length)console.log('Nichts zu schreiben.');
process.exit(fail?1:0);
