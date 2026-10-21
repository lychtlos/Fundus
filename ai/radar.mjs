/* Release-Radar (von der KI aufgerufen): prüft neue Alben/Bücher aus dem Umfeld.
   Aufruf: node radar.mjs u1 – liest work/u1/radar.json: [{"m":"musik","artist":"…","title":"…","why":"…"}]
   Gültig: Veröffentlichung zwischen 45 Tagen zurück und 60 Tagen voraus, Künstler/Autor noch nicht in den Listen. */
import {j,norm,readJ,writeJ,dd,sleep} from './lib.mjs';
const GB=process.env.GB_KEY||'AIzaSyDhZdIY3AfJDAcTcx1fSMoKfbiDW7QPBpc';
const id=process.argv[2];if(!/^u\d+$/.test(id||'')){console.log('Aufruf: node radar.mjs u1');process.exit(2)}
const dir='work/'+id,user=readJ(dir+'/user.json',null),list=readJ(dir+'/radar.json',null);
if(!user||!Array.isArray(list)){console.log(dir+'/radar.json fehlt oder ist kein JSON-Array');process.exit(2)}
const items=Object.values(user.items||{});
const knownArtist=new Set(items.filter(x=>x.m==='musik').map(x=>norm(x.title)));
const knownAuthor=new Set(items.filter(x=>x.m==='buch'&&x.authorName).map(x=>norm(x.authorName)));
const out=[],ok=readJ(dir+'/radar_checked.json',{});
const win=d=>d&&dd(d)>=-45&&dd(d)<=60;
for(const r of list){
  const m=r.m,a=String(r.artist||'').trim(),t=String(r.title||'').trim();const tag=m+' · '+a+' – '+t;
  if(!a||!t||!['musik','buch'].includes(m)){out.push('✗ '+tag+' – m (musik|buch), artist und title nötig');continue}
  if((m==='musik'?knownArtist:knownAuthor).has(norm(a))){out.push('✗ '+tag+' – Künstler/Autor schon in den Listen (läuft über die normale Prüfung)');continue}
  try{
    if(m==='musik'){
      const d=await j('https://api.deezer.com/search/album?q='+encodeURIComponent('artist:"'+a+'" album:"'+t+'"')+'&limit=10');
      let al=(d.data||[]).find(x=>norm(x.artist&&x.artist.name)===norm(a)&&(norm(x.title).includes(norm(t))||norm(t).includes(norm(x.title))));
      if(!al){const d2=await j('https://api.deezer.com/search/album?q='+encodeURIComponent(a+' '+t)+'&limit=10');al=(d2.data||[]).find(x=>norm(x.artist&&x.artist.name)===norm(a))}
      if(!al){out.push('✗ '+tag+' – bei Deezer nicht gefunden');continue}
      const full=await j('https://api.deezer.com/album/'+al.id);
      if(!win(full.release_date)){out.push('✗ '+tag+' – Erscheinungsdatum '+full.release_date+' liegt außerhalb des Zeitraums');continue}
      const rid='alb'+al.id;ok[rid]={rid,m,artist:full.artist.name,title:full.title,date:full.release_date,why:String(r.why||'').slice(0,140),k:'musik:'+full.artist.id,id:full.artist.id,img:full.cover_big||full.cover_medium||null,link:full.link||null};
      out.push('✓ '+tag+' → '+full.title+' ('+full.release_date+', '+(full.record_type||'album')+', '+full.nb_tracks+' Titel)');
    }else{
      const d=await j('https://www.googleapis.com/books/v1/volumes?q='+encodeURIComponent('intitle:'+t+' inauthor:'+a)+'&langRestrict=de&country=DE&maxResults=10&orderBy=newest&key='+GB);
      const v=(d.items||[]).find(x=>(x.volumeInfo.authors||[]).some(n=>norm(n)===norm(a)||norm(n).includes(norm(a))));
      if(!v){out.push('✗ '+tag+' – bei Google Books (deutsch) nicht gefunden');continue}
      const i=v.volumeInfo,date=(i.publishedDate||'').length===10?i.publishedDate:'';
      if(!win(date)){out.push('✗ '+tag+' – Datum '+(i.publishedDate||'unbekannt')+' außerhalb des Zeitraums oder ungenau');continue}
      let img=i.imageLinks&&(i.imageLinks.thumbnail||i.imageLinks.smallThumbnail);if(img)img=img.replace('http://','https://');
      const rid='gb'+v.id;ok[rid]={rid,m,artist:(i.authors||[a])[0],title:i.title,date,why:String(r.why||'').slice(0,140),k:'buch:g:'+v.id,id:v.id,img:img||null,link:i.infoLink||null};
      out.push('✓ '+tag+' → '+i.title+' ('+date+')');
    }
  }catch(e){out.push('? '+tag+' – Dienst nicht erreichbar ('+e.message+')')}
  await sleep(200);
}
writeJ(dir+'/radar_checked.json',ok);
console.log(out.join('\n')+'\n\n'+Object.keys(ok).length+' gültige Radar-Treffer gespeichert (werden automatisch übernommen).');
