/* Fundus KI-Tipps: gemeinsame Funktionen für export, verify und import.
   Jede Prüfung baut den Eintrag im selben Format wie die App (Schlüssel k, Bild, Genres …). */
import fs from 'node:fs';
export const TMDB=process.env.TMDB_KEY||'c59452df2b5d2d720c1e7b4b1bafd42f';
export const IGDB_URL=process.env.IGDB_URL||'https://fundus-igdb.steffen-hinderks.workers.dev';
const TMDB_IMG='https://image.tmdb.org/t/p/';
export const MEDIA=['film','serie','musik','buch','podcast','game'];
export const LBL={film:'Film',serie:'Serie',musik:'Musik',buch:'Buch',podcast:'Podcast',game:'Game'};
export const sleep=ms=>new Promise(r=>setTimeout(r,ms));
export const norm=s=>String(s||'').normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/^(the|der|die|das) /,'').replace(/[^a-z0-9]/g,'');
export const readJ=(f,d)=>{try{return JSON.parse(fs.readFileSync(f,'utf8'))}catch(e){return d}};
export const writeJ=(f,o)=>fs.writeFileSync(f,JSON.stringify(o,null,1));
const G_DE={2:'Point & Click',4:'Prügelspiel',5:'Shooter',7:'Musikspiel',8:'Jump ’n’ Run',9:'Puzzle',10:'Rennspiel',11:'Echtzeitstrategie',12:'Rollenspiel',13:'Simulation',14:'Sport',15:'Strategie',16:'Rundenstrategie',24:'Taktik',25:'Hack & Slash',26:'Quiz',30:'Flipper',31:'Abenteuer',32:'Indie',33:'Arcade',34:'Visual Novel',35:'Karten- & Brettspiel',36:'MOBA'};
const T_DE={1:'Action',17:'Fantasy',18:'Science-Fiction',19:'Horror',20:'Thriller',21:'Survival',22:'Historisch',23:'Stealth',27:'Komödie',38:'Open World',39:'Krieg',43:'Mystery',33:'Sandbox',31:'Drama',44:'Romanze'};

let lastItunes=0;
export async function j(url,opt={}){
  if(opt.itunes){const w=3500-(Date.now()-lastItunes);if(w>0)await sleep(w);lastItunes=Date.now()}
  for(let i=0;i<3;i++){
    const r=await fetch(url,{method:opt.body?'POST':'GET',body:opt.body,headers:{'user-agent':'Fundus/1.0 (github.com/lychtlos/Fundus)',...(opt.body?{'content-type':'text/plain'}:{})}});
    if(r.status===429||(r.status===403&&opt.itunes)){await sleep(4000*(i+1));continue}
    if(!r.ok)throw new Error(r.status+' '+url.split('?')[0]);
    return r.json();
  }
  throw new Error('Limit '+url.split('?')[0]);
}
const tmdb=(p,q='')=>j('https://api.themoviedb.org/3'+p+'?api_key='+TMDB+'&language=de-DE'+q);
const gNames={};
async function tmdbGenres(type){if(!gNames[type]){const d=await tmdb('/genre/'+type+'/list');gNames[type]=Object.fromEntries((d.genres||[]).map(g=>[g.id,g.name]))}return gNames[type]}
const yearOf=h=>{const m=String(h||'').match(/\b(19|20)\d{2}\b/);return m?+m[0]:0};
const pickBest=(list,title,name,yr,yf)=>{
  const n=norm(title);
  const ns=r=>[].concat(name(r)).filter(Boolean).map(norm);
  const sc=r=>Math.max(...ns(r).map(x=>x===n?10:x&&(x.includes(n)||n.includes(x))?3:0),0)+(yr&&yf(r)?(Math.abs(yf(r)-yr)<=1?3:-4):0);
  return list.map((r,i)=>({r,s:sc(r)-i*0.3})).filter(x=>x.s>=3).sort((a,b)=>b.s-a.s)[0]?.r||null;
};

/* Liefert {item, extra} oder null. hint: Jahr, Autor oder Künstler (frei) */
export async function lookup(m,title,hint=''){
  const yr=yearOf(hint);
  if(m==='film'||m==='serie'){
    const type=m==='film'?'movie':'tv';
    const d=await tmdb('/search/'+type,'&query='+encodeURIComponent(title)+'&include_adult=false');
    const dk=r=>parseInt((r.release_date||r.first_air_date||'').slice(0,4))||0;
    const r=pickBest((d.results||[]).slice(0,10),title,x=>[x.title||x.name,x.original_title||x.original_name],yr,dk);
    if(!r)return null;
    const G=await tmdbGenres(type);const date=(r.release_date||r.first_air_date||'');
    const item={k:m+':'+r.id,m,id:r.id,title:r.title||r.name,year:date.slice(0,4),sub:date.slice(0,4),img:r.poster_path?TMDB_IMG+'w342'+r.poster_path:null,
      rating:r.vote_average||null,votes:r.vote_count||0,gids:r.genre_ids||[],genres:(r.genre_ids||[]).map(g=>G[g]).filter(Boolean).slice(0,3),link:'https://www.themoviedb.org/'+type+'/'+r.id};
    let prov=[];try{const p=await j('https://api.themoviedb.org/3/'+type+'/'+r.id+'/watch/providers?api_key='+TMDB);const de=(p.results&&p.results.DE)||{};
      prov=[...(de.flatrate||[]),...(de.free||[]),...(de.ads||[])].map(x=>({id:x.provider_id,name:x.provider_name}))}catch(e){}
    return {item,extra:{prov,orig:r.original_title||r.original_name}};
  }
  if(m==='musik'){
    const d=await j('https://api.deezer.com/search/artist?q='+encodeURIComponent(title)+'&limit=10');
    const r=pickBest(d.data||[],title,x=>x.name,0,()=>0);if(!r)return null;
    return {item:{k:'musik:'+r.id,m,id:r.id,title:r.name,sub:r.nb_fan?Math.round(r.nb_fan/1000).toLocaleString('de-DE')+' Tsd. Fans':'',img:r.picture_big||r.picture_medium||null,link:r.link||null,genres:[]},extra:{fans:r.nb_fan}};
  }
  if(m==='buch'){
    const au=hint&&!yr?hint:'';
    const f='key,title,author_name,author_key,first_publish_year,cover_i,subject,editions,editions.title,editions.language,editions.cover_i';
    const d=await j('https://openlibrary.org/search.json?title='+encodeURIComponent(title)+(au?'&author='+encodeURIComponent(au):'')+'&lang=de&limit=8&fields='+f);
    let docs=(d.docs||[]).filter(x=>(x.author_name||[]).length);
    if(!docs.length&&au){const d2=await j('https://openlibrary.org/search.json?q='+encodeURIComponent(title+' '+au)+'&lang=de&limit=8&fields='+f);docs=(d2.docs||[]).filter(x=>(x.author_name||[]).length)}
    const edT=x=>{const e=(x.editions&&x.editions.docs||[])[0];return e&&e.title||''};
    const r=pickBest(docs,title,x=>[x.title,edT(x)],0,()=>0)||(au&&docs[0]);if(!r)return null;
    const id=(r.key||'').replace('/works/','');const a=(r.author_name||[])[0]||'';
    const ed=(r.editions&&r.editions.docs||[])[0];const de=ed&&(ed.language||[]).includes('ger');
    const t=de&&ed.title?ed.title.replace(/\.\s*Roman\.?$|\s*:\s*Roman$|\.$/i,''):r.title;const cov=(de&&ed.cover_i)||r.cover_i;
    return {item:{k:'buch:'+id,m,src:'ol',id,title:t,orig:t!==r.title?r.title:undefined,sub:[a,r.first_publish_year].filter(Boolean).join(' · '),year:r.first_publish_year,authorName:a,
      img:cov?'https://covers.openlibrary.org/b/id/'+cov+'-M.jpg':null,authors:(r.author_key||[]).slice(0,2),subjects:(r.subject||[]).slice(0,4),genres:[],link:'https://openlibrary.org'+r.key,
      tk:String(t||'').toLowerCase().replace(/[^a-z0-9äöüß]/g,'').slice(0,40)+'|'+String(a).toLowerCase().trim().split(/\s+/).pop()},extra:{deutsch:!!de}};
  }
  if(m==='podcast'){
    const d=await j('https://itunes.apple.com/search?term='+encodeURIComponent(title)+'&entity=podcast&country=DE&limit=10',{itunes:true});
    const r=pickBest(d.results||[],title,x=>x.collectionName,0,()=>0);if(!r)return null;
    return {item:{k:'podcast:'+r.collectionId,m,id:r.collectionId,title:r.collectionName,sub:r.artistName||'',img:r.artworkUrl600||r.artworkUrl100||null,genres:r.primaryGenreName?[r.primaryGenreName]:[],link:r.collectionViewUrl||null,
      tk:String(r.collectionName||'').toLowerCase().replace(/[^a-z0-9äöüß]/g,'').slice(0,40)+'|'},extra:{}};
  }
  if(m==='game'){
    const q='search "'+String(title).replace(/"/g,'')+'"; fields name,slug,first_release_date,cover.image_id,genres,themes,platforms.abbreviation,total_rating,total_rating_count; where game_type = (0,8,9) & version_parent = null; limit 10;';
    const d=await j(IGDB_URL+'/games',{body:q});
    const yf=g=>g.first_release_date?new Date(g.first_release_date*1000).getUTCFullYear():0;
    const g=pickBest(d||[],title,x=>x.name,yr,yf);if(!g)return null;
    const ts=g.first_release_date?new Date(g.first_release_date*1000):null;const y=ts?String(ts.getUTCFullYear()):'';
    const plats=(g.platforms||[]).map(p=>p.abbreviation).filter(Boolean);
    const gn=(g.genres||[]).map(x=>G_DE[x]).filter(Boolean),tn=(g.themes||[]).map(x=>T_DE[x]).filter(Boolean);
    return {item:{k:'game:'+g.id,m,id:g.id,title:g.name,year:y||'TBA',sub:[y||'Termin offen',plats.slice(0,4).join(', ')].filter(Boolean).join(' · '),
      img:g.cover?'https://images.igdb.com/igdb/image/upload/t_cover_big/'+g.cover.image_id+'.jpg':null,rating:g.total_rating?g.total_rating/10:null,votes:g.total_rating_count||0,
      genres:[...gn,...tn].slice(0,3),gslugs:[...(g.genres||[]).map(x=>'g'+x),...(g.themes||[]).map(x=>'t'+x)],released:ts?ts.toISOString().slice(0,10):'',tba:!ts,link:'https://www.igdb.com/games/'+(g.slug||g.id)},extra:{plats}};
  }
  throw new Error('Unbekannter Bereich: '+m);
}

/* Regeln wie in der App (passRules) */
export function passRules(user,it){
  if(!['film','serie','buch','game'].includes(it.m))return true;
  const r={minYear:0,minR:0,exG:[],exGid:[],...(((user.settings||{}).rules||{})[it.m]||{})};
  const y=parseInt(it.year);if(r.minYear&&y&&y<r.minYear)return false;
  const hasR=it.rating&&(it.votes==null||it.votes>=20);if(r.minR&&hasR&&it.rating<r.minR)return false;
  if(r.exG.length&&(it.genres||[]).some(g=>r.exG.includes(g)))return false;
  if(r.exGid.length&&(it.gids||[]).some(g=>r.exGid.includes(g)))return false;
  return true;
}
/* bekannt = schon in Listen (egal welcher Status) oder kürzlich als KI-Tipp gezeigt */
export function knownSets(user){
  const items=Object.values(user.items||{});
  const keys=new Set(items.map(x=>x.k));
  const names=new Set(items.map(x=>x.m+'|'+norm(x.title)).concat(items.filter(x=>x.orig).map(x=>x.m+'|'+norm(x.orig))));
  const hist=new Set(((user.ai||{}).hist||[]));
  return {keys,names,hist};
}
