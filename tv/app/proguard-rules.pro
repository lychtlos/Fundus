# Fundus TV – R8-Regeln
# Daten werden per org.json gelesen (keine Reflexion), Bibliotheken bringen eigene Regeln mit.
-keep class de.lychtlos.fundustv.HelperService { *; }
-keep class de.lychtlos.fundustv.KeepAlive { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
