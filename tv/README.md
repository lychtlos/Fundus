# Fundus TV

Android-TV-App (Google TV Streamer) zu Fundus: Kataloge der gewählten Streamingdienste in einer Oberfläche, Merkliste und Claude-Tipps aus dem Fundus-Konto, Öffnen der Titel direkt in Netflix, Prime Video, Disney+ usw.

Gebaut wird automatisch per GitHub Actions (`.github/workflows/tv.yml`) bei jeder Änderung unter `tv/`. Die fertige APK liegt immer unter:

https://github.com/lychtlos/Fundus/releases/download/tv-latest/fundus-tv.apk

## Installation auf dem Google TV Streamer

1. Einstellungen → System → Info → 7× auf „Android TV OS-Build“ klicken (Entwickleroptionen an).
2. Aus dem Play Store die App **Downloader** (AFTVnews) installieren.
3. Einstellungen → Apps → Sicherheit & Einschränkungen → Unbekannte Quellen → Downloader erlauben.
4. In Downloader die APK-Adresse oben eingeben, laden, installieren.

Updates: dieselbe Adresse erneut laden und installieren (die App ist immer mit demselben Schlüssel signiert, Daten bleiben erhalten).

## Anmeldung einrichten (einmalig)

Die TV-App nutzt den Google-Gerätelogin (Code am Handy eingeben). Dafür ein OAuth-Client im Firebase-Projekt der Web-App:

1. https://console.cloud.google.com/apis/credentials?project=wanderlust-8b3ba
2. „Anmeldedaten erstellen“ → „OAuth-Client-ID“ → Typ **„Fernseher und Geräte mit begrenzter Eingabe“** → Name „Fundus TV“.
3. Client-ID und Clientschlüssel als Repo-Secrets anlegen (GitHub → Settings → Secrets and variables → Actions):
   - `FUNDUS_TV_CLIENT_ID`
   - `FUNDUS_TV_CLIENT_SECRET`
4. Workflow „Fundus TV bauen“ manuell starten (Actions → Run workflow) und die neue APK installieren.

Die Daten liegen wie in der Web-App unter `artifacts/fundus/users/{uid}`; Firestore-Regeln müssen nicht geändert werden.

## Technik

- Kotlin, Jetpack Compose for TV, keine Firebase-SDK (Auth und Firestore über REST)
- TMDB für Katalog, Details und Verfügbarkeit in Deutschland
- JustWatch für direkte Links zum Titel beim Dienst; sonst wird die Dienst-App gestartet
- Schreibt nur einzelne Felder (`items.<k>`, `del.<k>`, `settings.providers`) mit Zeitstempel, damit die Zusammenführung der Web-App greift
- Signaturschlüssel `app/fundus-tv.keystore` liegt bewusst im Repo (privates Projekt), damit Updates sich über alte Versionen installieren lassen
