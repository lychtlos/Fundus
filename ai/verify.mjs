/* Schritt 2 (von der KI aufgerufen): prüft Kandidaten gegen die echten Datenbanken.
   Aufruf: node verify.mjs u1   – liest work/u1/candidates.json: [{"m":"film","title":"…","hint":"2016"}]
   Ausgabe je Kandidat: OK (mit Schlüssel k) oder Grund der Ablehnung. Ergebnis auch in work/u1/checked.json. */
import {lookup,passRules,knownSets,norm,readJ,writeJ,MEDIA,sleep,isPopular} from './lib.mjs';
const id=process.argv[2];
if(!/^u\d+$/.test(id||'')){console.log('Aufruf: node verify.mjs u1');process.exit(2)}
const dir='work/'+id,user=readJ(dir+'/user.json',null);
if(!user){console.log('Unbekanntes Konto '+id);process.exit(2)}
const cands=readJ(dir+'/candidates.json',null);
if(!Array.isArray(cands)){console.log('work/'+id+'/candidates.json fehlt oder ist kein JSON-Array');process.exit(2)}
const checked=readJ(dir+'/checked.json',{});
const {keys,names,hist}=knownSets(user);
const histK=new Set((user.ai.hist||[]).map(h=>h.k));
const mine=new Set((user.provNames||[]).map(n=>n.toLowerCase()));
const out=[];
for(const c of cands){
  const m=String(c.m||'').toLowerCase(),t=String(c.title||'').trim();
  if(!MEDIA.includes(m)||!t){out.push('✗ '+JSON.stringify(c)+' – m muss film|serie|musik|buch|podcast|game sein, title nötig');continue}
  const tag=m+' · '+t;
  if(names.has(m+'|'+norm(t))){out.push('✗ '+tag+' – BEKANNT (schon in den Listen)');continue}
  let r;try{r=await lookup(m,t,c.hint||'')}catch(e){out.push('? '+tag+' – Dienst nicht erreichbar ('+e.message+'), später erneut');continue}
  if(!r){out.push('✗ '+tag+' – NICHT GEFUNDEN (Titel prüfen, ggf. Originaltitel oder Jahr/Autor als hint)');continue}
  const it=r.item;let st='ok',why='';
  if(keys.has(it.k)||names.has(m+'|'+norm(it.title)))st='known',why='BEKANNT (schon in den Listen)';
  else if(histK.has(it.k))st='hist',why='SCHON VORGESCHLAGEN';
  else if(!passRules(user,it))st='rule',why='VERLETZT REGEL des Nutzers';
  let info=[it.year,it.m==='buch'?it.authorName:'',(it.genres||[]).join('/')].filter(Boolean).join(', ');
  if(r.extra.prov){const pn=[...new Set(r.extra.prov.map(p=>p.name))];const hit=pn.filter(n=>[...mine].some(x=>n.toLowerCase().startsWith(x)));
    r.extra.mine=hit;info+=' | Streaming DE: '+(pn.length?pn.slice(0,4).join(', '):'keins')+(mine.size?(hit.length?' → IM ABO':' → nicht im Abo'):'')}
  if(r.extra.plats)info+=' | '+r.extra.plats.slice(0,5).join(', ');
  if(isPopular(it,r.extra))info+=' | SEHR BEKANNT';
  if(m==='buch'&&!r.extra.deutsch)info+=' | keine deutsche Ausgabe gefunden';
  checked[it.k]={st,item:it,extra:r.extra,asked:t};
  out.push((st==='ok'?'✓ ':'✗ ')+tag+' → '+it.k+' „'+it.title+'“ ('+info+')'+(why?' – '+why:''));
  await sleep(150);
}
writeJ(dir+'/checked.json',checked);
console.log(out.join('\n'));
console.log('\n'+Object.values(checked).filter(x=>x.st==='ok').length+' geprüfte Treffer verfügbar. Auswahl in work/'+id+'/tips.json schreiben: [{"k":"film:123","why":"…"}]. Höchstens ein Drittel darf SEHR BEKANNT sein.');
