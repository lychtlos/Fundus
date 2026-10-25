package de.lychtlos.fundustv

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.lychtlos.fundustv.data.Auth
import de.lychtlos.fundustv.data.Fundus
import de.lychtlos.fundustv.data.FundusData
import de.lychtlos.fundustv.data.Provider
import de.lychtlos.fundustv.data.Release
import de.lychtlos.fundustv.data.Releases
import de.lychtlos.fundustv.data.Title
import de.lychtlos.fundustv.data.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** Vollbild-Seiten über dem Hauptbildschirm (mit Menü links). */
sealed interface Screen {
    data object Main : Screen
    data object Login : Screen
    data class Detail(val title: Title) : Screen
}

/** Menüpunkte wie in der Smartphone-App. */
enum class Tab(val label: String) {
    Discover("Entdecken"), Search("Suchen"), Watch("Merkliste"), News("Neuerscheinungen"), Follow("Verfolgt"), Settings("Einstellungen")
}

/** Eine Reihe auf dem Bildschirm. m = "serie"/"film" (für die Trennung nach Bereich), hint = kurze Erklärung, warum diese Titel. */
data class Shelf(val id: String, val label: String, val items: List<Title>, val m: String? = null, val hint: String? = null)

class AppState(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val stack = mutableStateListOf<Screen>(Screen.Main)
    val screen: Screen get() = stack.last()
    var tab by mutableStateOf(Tab.Discover)
    /** Bereich auf „Entdecken“: "alle", "serie" oder "film" */
    var med by mutableStateOf("alle")

    var user by mutableStateOf(Auth.user)
    var fundus by mutableStateOf(FundusData())
    var shelves by mutableStateOf<List<Shelf>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var allProviders by mutableStateOf<List<Provider>>(emptyList())
    private var provVersion by mutableStateOf(0)

    var releases by mutableStateOf<List<Release>?>(null)
    var releasesLoading by mutableStateOf(false)
    private var releasesAt = 0L

    /** erster Ladevorgang fertig (Splash darf verschwinden) */
    var ready by mutableStateOf(false)

    /** Gewählte Dienste: mit Konto aus Fundus, sonst lokal auf dem Gerät. */
    val providers: List<Int>
        get() = if (provVersion >= 0 && user != null) fundus.providers
        else prefs.getString("providers", "8,9,337")!!.split(",").mapNotNull { it.trim().toIntOrNull() }

    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex); return true
    }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex); tab = Tab.Discover }
    fun open(t: Tab) {
        tab = t
        if (t == Tab.News || t == Tab.Follow) loadReleases()
    }

    fun say(msg: String) { toast = msg }

    /** Zeitpunkt des letzten vollständigen Ladens (Fundus + alle TMDB-Reihen). */
    var lastFull = 0L
        private set
    private var checking = false

    fun refresh() {
        lastFull = System.currentTimeMillis()
        scope.launch {
            loading = true; error = null
            try {
                user = Auth.user
                if (user != null) {
                    fundus = Fundus.load()
                    Tmdb.userKey = fundus.tmdbKey
                } else fundus = FundusData()
                if (allProviders.isEmpty()) allProviders = runCatching { Tmdb.providerList() }.getOrDefault(emptyList())
                shelves = buildShelves()
                saveShelves()
                releasesAt = 0L
                if (tab == Tab.News || tab == Tab.Follow) loadReleases()
            } catch (e: Exception) {
                error = e.message ?: "Laden fehlgeschlagen"
            } finally {
                loading = false
                ready = true
            }
        }
    }

    fun loadReleases(force: Boolean = false) {
        if (releasesLoading) return
        if (!force && releases != null && System.currentTimeMillis() - releasesAt < 3 * 3600_000L) return
        scope.launch {
            releasesLoading = true
            try {
                releases = Releases.load(fundus)
                releasesAt = System.currentTimeMillis()
            } catch (_: Exception) {
                if (releases == null) releases = emptyList()
            } finally {
                releasesLoading = false
            }
        }
    }

    /**
     * Prüft leicht, ob sich das Fundus-Konto geändert hat (z. B. in der Web-App),
     * und übernimmt die Änderung.
     */
    fun checkRemote() {
        if (user == null || loading || checking) return
        checking = true
        scope.launch {
            try {
                if (Fundus.changedRemotely()) {
                    fundus = Fundus.load()
                    Tmdb.userKey = fundus.tmdbKey
                    shelves = buildShelves()
                    saveShelves()
                    releasesAt = 0L
                }
            } catch (_: Exception) {
                // still: nächster Versuch beim nächsten Intervall
            } finally {
                checking = false
            }
        }
    }

    /** alles, was schon in den Listen steht (egal welcher Status) */
    private fun knownKeys(): Set<String> = fundus.items.keys

    /**
     * Reihen für „Entdecken“, getrennt nach Serien und Filmen, jeweils mit Begründung:
     * 1. Tipps von Claude, 2. aus Deinen Bewertungen (TMDB-Empfehlungen zu Lieblingen und „Gefällt mir“),
     * 3. „Weil Dir … gefällt“ je Lieblingstitel, 4. neu/beliebt bei Deinen Diensten.
     */
    private suspend fun buildShelves(): List<Shelf> {
        val prov = providers
        val free = fundus.includeFree
        val names = allProviders.associateBy({ it.id }, { it.name })
        val known = knownKeys()
        val yearAgo = java.time.LocalDate.now().minusMonths(10).toString()
        // Serien und Filme gleichzeitig laden

        return kotlinx.coroutines.coroutineScope { listOf("serie", "film").map { m -> async { val out = mutableListOf<Shelf>()
            val type = if (m == "film") "movie" else "tv"
            val ok = { t: Title -> t.k !in known && fundus.passes(t) }

            // 1. Claude
            val tips = fundus.tips.filter { it.m == m }
            if (tips.isNotEmpty()) out += Shelf("tips-$m", "Für Dich", tips, m, "von Claude ausgesucht")
            val shown = tips.map { it.k }.toMutableSet()

            // 2./3. aus Bewertungen
            val seeds = fundus.items.values
                .filter { it["m"] == m && (it["status"] == "love" || it["status"] == "like" || (it["status"] == null && it["fol"] == true)) }
                .sortedWith(compareByDescending<Map<String, Any?>> { it["status"] == "love" }.thenByDescending { (it["ts"] as? Number)?.toLong() ?: 0L })
                .mapNotNull { FundusData.itemToTitle(it)?.let { t -> t to (fundus.items[t.k]?.get("status") == "love") } }
                .take(10)
            val recs = scopeAsync(seeds.map { (s, _) -> suspend { Tmdb.recommendations(s) } })
            val score = HashMap<String, Double>(); val why = HashMap<String, MutableList<String>>(); val byK = HashMap<String, Title>()
            seeds.forEachIndexed { i, (s, love) ->
                val w = if (love) 2.0 else 1.0 - 0.5 * i / maxOf(1, seeds.size - 1)
                recs[i].filter(ok).forEachIndexed { pos, r ->
                    score[r.k] = (score[r.k] ?: 0.0) + w * (1 - pos / 40.0) * ((r.rating ?: 6.0) / 7.5)
                    why.getOrPut(r.k) { mutableListOf() }.add(s.title)
                    byK[r.k] = r
                }
            }
            fun reason(k: String): String {
                val w = why[k].orEmpty().distinct()
                return when (w.size) {
                    0 -> ""
                    1 -> "Weil Dir „${w[0]}“ gefällt"
                    else -> "Weil Dir „${w[0]}“ und „${w[1]}“ gefallen"
                }
            }
            val mix = score.entries.sortedByDescending { it.value }.map { it.key }.filter { it !in shown }.take(24)
                .mapNotNull { k -> byK[k]?.copy(why = reason(k)) }
            if (mix.isNotEmpty()) { out += Shelf("mix-$m", "Aus Deinen Bewertungen", mix, m, "ähnlich zu dem, was Dir gefällt"); shown += mix.take(12).map { it.k } }
            seeds.take(4).forEachIndexed { i, (s, love) ->
                val l = recs[i].filter(ok).filter { it.k !in shown }.take(20).map { it.copy(why = "Weil Dir „${s.title}“ gefällt") }
                if (l.size >= 4) out += Shelf("seed-$m-${s.id}", "Weil Dir „${s.title}“ gefällt", l, m, if (love) "einer Deiner Lieblinge" else null)
            }

            // 4. Kataloge der eigenen Dienste
            val catalog = mutableListOf<Triple<String, String, suspend () -> List<Title>>>()
            catalog += Triple("Neu bei Deinen Diensten", "neu in Deinen Abos", suspend {
                if (m == "film") Tmdb.discover(type, prov, free, minVotes = 40, extra = mapOf("primary_release_date.gte" to yearAgo))
                else Tmdb.discover(type, prov, free, minVotes = 15, extra = mapOf("air_date.gte" to yearAgo))
            })
            catalog += Triple("Beliebt bei Deinen Diensten", "meistgesehen in Deinen Abos", suspend { Tmdb.discover(type, prov, free) })
            prov.take(4).forEach { id ->
                val n = names[id] ?: "Dienst $id"
                catalog += Triple("Top bei $n", "in Deinem Abo", suspend { Tmdb.discover(type, listOf(id), free) })
            }
            val res = scopeAsync(catalog.map { it.third })
            catalog.forEachIndexed { i, (label, hint, _) ->
                val l = res[i].filter(ok).distinctBy { it.k }.map { it.copy(why = hint.replaceFirstChar { c -> c.uppercase() }) }
                if (l.isNotEmpty()) out += Shelf("cat-$m-$i", label, l, m, hint)
            }
        out } }.awaitAll().flatten() }

    }

    private suspend fun scopeAsync(fns: List<suspend () -> List<Title>>): List<List<Title>> =
        kotlinx.coroutines.coroutineScope {
            fns.map { f -> async { runCatching { f() }.getOrDefault(emptyList()) } }.awaitAll()
        }

    /** Titel nach Bewertung aus allen Reihen nehmen (wie in der Web-App). */
    private fun dropFromShelves(k: String) {
        shelves = shelves.map { s -> s.copy(items = s.items.filter { it.k != k }) }.filter { it.items.isNotEmpty() }
    }

    fun setStatus(t: Title, status: String?) {
        if (user == null) { say("Erst mit Fundus verbinden"); return }
        scope.launch {
            try {
                Fundus.setStatus(t, status)
                fundus = Fundus.data
                say(
                    when (status) {
                        "watch" -> "Gemerkt"
                        "like" -> "Gefällt Dir"
                        "love" -> "Liebling"
                        "dislike" -> "Gefällt Dir nicht"
                        "hide" -> "Ausgeblendet"
                        else -> "Entfernt"
                    }
                )
                if (status != null) dropFromShelves(t.k)
                releasesAt = 0L
            } catch (e: Exception) {
                say(e.message ?: "Speichern fehlgeschlagen")
            }
        }
    }

    fun toggleFol(t: Title) {
        if (user == null) { say("Erst mit Fundus verbinden"); return }
        val on = !fundus.followed(t.k)
        scope.launch {
            try {
                Fundus.setFol(t, on)
                fundus = Fundus.data
                say(if (on) "Wird verfolgt" else "Nicht mehr verfolgt")
                if (on) dropFromShelves(t.k)
                releasesAt = 0L
            } catch (e: Exception) {
                say(e.message ?: "Speichern fehlgeschlagen")
            }
        }
    }

    fun toggleProvider(id: Int) {
        val cur = providers.toMutableList()
        if (id in cur) cur.remove(id) else cur.add(id)
        if (user != null) {
            scope.launch {
                try { Fundus.setProviders(cur); fundus = Fundus.data; refresh() } catch (e: Exception) { say(e.message ?: "Fehler") }
            }
        } else {
            prefs.edit().putString("providers", cur.joinToString(",")).apply()
            provVersion++
            refresh()
        }
    }

    /* ---------- letzte Startseite auf dem Gerät: beim nächsten Start sofort sichtbar, dann im Hintergrund aktualisiert ---------- */
    private val shelfFile = java.io.File(ctx.filesDir, "shelves.json")

    private fun saveShelves() {
        val uid = user?.uid ?: ""
        val snap = shelves
        scope.launch(Dispatchers.IO) {
            runCatching {
                val a = org.json.JSONArray()
                snap.forEach { s ->
                    val items = org.json.JSONArray()
                    s.items.take(30).forEach { t ->
                        items.put(org.json.JSONObject().put("m", t.m).put("id", t.id).put("title", t.title).put("year", t.year)
                            .put("poster", t.poster ?: "").put("backdrop", t.backdrop ?: "").put("rating", t.rating ?: 0.0)
                            .put("why", t.why ?: "").put("votes", t.votes).put("ai", t.ai)
                            .put("gids", org.json.JSONArray(t.gids)))
                    }
                    a.put(org.json.JSONObject().put("id", s.id).put("label", s.label).put("m", s.m ?: "").put("hint", s.hint ?: "").put("items", items))
                }
                shelfFile.writeText(org.json.JSONObject().put("uid", uid).put("shelves", a).toString())
            }
        }
    }

    private fun loadShelves() {
        runCatching {
            if (!shelfFile.exists()) return
            val o = org.json.JSONObject(shelfFile.readText())
            if (o.optString("uid") != (user?.uid ?: "")) return
            val a = o.getJSONArray("shelves")
            shelves = (0 until a.length()).map { i ->
                val s = a.getJSONObject(i); val it = s.getJSONArray("items")
                Shelf(
                    s.getString("id"), s.getString("label"),
                    (0 until it.length()).map { j ->
                        val t = it.getJSONObject(j)
                        Title(
                            m = t.getString("m"), id = t.getInt("id"), title = t.getString("title"), year = t.optString("year"),
                            poster = t.optString("poster").ifEmpty { null }, backdrop = t.optString("backdrop").ifEmpty { null },
                            rating = t.optDouble("rating").takeIf { r -> r > 0 }, why = t.optString("why").ifEmpty { null },
                            votes = t.optInt("votes"), ai = t.optBoolean("ai"),
                            gids = t.optJSONArray("gids")?.let { g -> (0 until g.length()).map { x -> g.optInt(x) } } ?: emptyList(),
                        )
                    },
                    s.optString("m").ifEmpty { null }, s.optString("hint").ifEmpty { null },
                )
            }
            if (shelves.isNotEmpty()) ready = true
        }
    }

    init { loadShelves() }

    fun logout() {
        Auth.logout(); user = null; fundus = FundusData(); releases = null; refresh()
    }
}
