/* Game-Texte (von der KI aufgerufen): holt englische IGDB-Beschreibungen für Games aus
   tips.json, Merkliste und „Gefällt mir“, die noch keine deutsche Fassung haben.
   Aufruf: node gametext.mjs u1 – schreibt work/u1/gtx_en.json und gibt die Texte aus. */
import {j,readJ,writeJ,IGDB_URL} from './lib.mjs';
const id=process.argv[2];if(!/^u\d+$/.test(id||'')){console.log('Aufruf: node gametext.mjs u1');process.exit(2)}
const dir='work/'+id,user=readJ(dir+'/user.json',null);if(!user){console.log('Unbekanntes Konto');process.exit(2)}
const have=new Set(user.ai.gtx||[]);
const keys=new Set();
(readJ(dir+'/tips.json',[])||[]).forEach(t=>t&&String(t.k).startsWith('game:')&&keys.add(t.k));
Object.values(user.items||{}).filter(x=>x.m==='game'&&['watch','like','love'].includes(x.status)).forEach(x=>keys.add(x.k));
const ids=[...keys].filter(k=>!have.has(k)).map(k=>k.slice(5)).filter(x=>/^\d+$/.test(x)).slice(0,25);
if(!ids.length){console.log('Keine Games ohne deutsche Beschreibung.');writeJ(dir+'/gtx_en.json',{});process.exit(0)}
const d=await j(IGDB_URL+'/games',{body:'fields name,summary; where id = ('+ids.join(',')+'); limit 50;'});
const en={};(d||[]).filter(g=>g.summary).forEach(g=>en['game:'+g.id]={name:g.name,summary:g.summary.slice(0,1500)});
writeJ(dir+'/gtx_en.json',en);
console.log(Object.entries(en).map(([k,v])=>k+' | '+v.name+'\n'+v.summary).join('\n\n'));
console.log('\nÜbersetzungen nach work/'+id+'/gtx_de.json schreiben: {"game:123":"…"}');
