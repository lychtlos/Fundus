/* Diagnose: Bestand der KI-Tipps je Bereich und was davon in der App sichtbar sein müsste. Nur lesen. */
import admin from 'firebase-admin';
admin.initializeApp({credential:admin.credential.cert(JSON.parse(process.env.FIREBASE_SA))});
const s=await admin.firestore().collection('artifacts/fundus/users').get();
for(const d of s.docs){
  const u=d.data();const items=u.items||{};const tips=(u.ai||{}).tips||[];
  const per={};
  for(const t of tips){const p=per[t.m]||(per[t.m]={gesamt:0,offen:0,bewertet:0});p.gesamt++;if(items[t.k])p.bewertet++;else p.offen++}
  const st=u.settings||{};
  console.log('::notice title=KI-Bestand '+d.id.slice(0,6)+'::'+JSON.stringify(per)+' | onlyMine='+!!st.onlyMine+' | Dienste='+(st.providers||[]).length+' | Regeln='+JSON.stringify(st.rules||{})+' | Stand='+new Date((u.ai||{}).at||0).toISOString());
}
