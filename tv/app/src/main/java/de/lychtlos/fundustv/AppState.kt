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
import de.lychtlos.fundustv.data.Title
import de.lychtlos.fundustv.data.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Login : Screen
    data class Detail(val title: Title) : Screen
}

data class Shelf(val id: String, val label: String, val items: List<Title>)

class AppState(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    var user by mutableStateOf(Auth.user)
    var fundus by mutableStateOf(FundusData())
    var shelves by mutableStateOf<List<Shelf>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var allProviders by mutableStateOf<List<Provider>>(emptyList())
    private var provVersion by mutableStateOf(0)

    /** Gewählte Dienste: mit Konto aus Fundus, sonst lokal auf dem Gerät. */
    val providers: List<Int>
        get() = if (provVersion >= 0 && user != null) fundus.providers
        else prefs.getString("providers", "8,9,337")!!.split(",").mapNotNull { it.trim().toIntOrNull() }

    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex); return true
    }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }

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
            } catch (e: Exception) {
                error = e.message ?: "Fehler beim Laden"
            } finally {
                loading = false
            }
        }
    }

    /**
     * Prüft leicht, ob sich das Fundus-Konto geändert hat (z. B. in der Web-App),
     * und übernimmt die Änderung ohne die TMDB-Reihen neu zu laden.
     */
    fun checkRemote() {
        if (user == null || loading || checking) return
        checking = true
        scope.launch {
            try {
                if (Fundus.changedRemotely()) applyRemote()
            } catch (_: Exception) {
                // still: nächster Versuch beim nächsten Intervall
            } finally {
                checking = false
            }
        }
    }

    private suspend fun applyRemote() {
        val before = fundus
        fundus = Fundus.load()
        Tmdb.userKey = fundus.tmdbKey
        if (before.providers != fundus.providers || before.includeFree != fundus.includeFree) {
            shelves = buildShelves()
            return
        }
        val rated = ratedKeys()
        val rest = shelves.filter { it.id != "watch" && it.id != "tips" }
            .map { s -> s.copy(items = s.items.filter { it.k !in rated }) }
            .filter { it.items.isNotEmpty() }
        shelves = fundusShelves() + rest
    }

    private fun ratedKeys(): Set<String> =
        fundus.items.filterValues { it["status"] in setOf("like", "love", "dislike", "hide") }.keys

    private fun fundusShelves(): List<Shelf> = listOfNotNull(
        fundus.watchlist().takeIf { it.isNotEmpty() }?.let { Shelf("watch", "Deine Merkliste", it) },
        fundus.tips.takeIf { it.isNotEmpty() }?.let { Shelf("tips", "Tipps von Claude", it) },
    )

    private suspend fun buildShelves(): List<Shelf> {
        val prov = providers
        val free = fundus.includeFree
        val names = allProviders.associateBy({ it.id }, { it.name })
        val out = fundusShelves().toMutableList()

        val yearAgo = java.time.LocalDate.now().minusMonths(10).toString()
        val jobs = mutableListOf<Pair<String, suspend () -> List<Title>>>()
        jobs += "Beliebte Filme in Deinen Diensten" to { Tmdb.discover("movie", prov, free) }
        jobs += "Beliebte Serien in Deinen Diensten" to { Tmdb.discover("tv", prov, free) }
        jobs += "Neue Filme in Deinen Diensten" to {
            Tmdb.discover("movie", prov, free, minVotes = 40, extra = mapOf("primary_release_date.gte" to yearAgo))
        }
        jobs += "Neue Serien und Staffeln" to {
            Tmdb.discover("tv", prov, free, minVotes = 15, extra = mapOf("air_date.gte" to yearAgo))
        }
        prov.take(6).forEach { id ->
            val n = names[id] ?: "Dienst $id"
            jobs += "Top bei $n" to {
                val a = Tmdb.discover("movie", listOf(id), free)
                val b = Tmdb.discover("tv", listOf(id), free)
                a.zip(b).flatMap { listOf(it.first, it.second) } + (a.drop(b.size) + b.drop(a.size))
            }
        }
        jobs += "Im Trend diese Woche" to { Tmdb.trending() }

        val known = ratedKeys()
        val results = scopeAsync(jobs.map { it.second })
        jobs.forEachIndexed { i, (label, _) ->
            val list = results[i].filter { it.k !in known }.distinctBy { it.k }
            if (list.isNotEmpty()) out += Shelf("s$i", label, list)
        }
        return out
    }

    private suspend fun scopeAsync(fns: List<suspend () -> List<Title>>): List<List<Title>> =
        kotlinx.coroutines.coroutineScope {
            fns.map { f -> async { runCatching { f() }.getOrDefault(emptyList()) } }.awaitAll()
        }

    fun setStatus(t: Title, status: String?) {
        if (user == null) { say("Dafür bitte unter Einstellungen mit Fundus verbinden"); return }
        scope.launch {
            try {
                Fundus.setStatus(t, status)
                fundus = Fundus.data
                say(
                    when (status) {
                        "watch" -> "Auf der Merkliste"
                        "like" -> "Gefällt Dir – fließt in die Vorschläge ein"
                        "love" -> "Lieblingstitel"
                        "dislike" -> "Gefällt Dir nicht – ähnliche Titel kommen seltener"
                        "hide" -> "Wird nicht mehr vorgeschlagen"
                        else -> "Entfernt"
                    }
                )
                // Merkliste/Tipps-Reihen sofort aktualisieren
                shelves = shelves.mapNotNull { s ->
                    when (s.id) {
                        "watch" -> null
                        "tips" -> s.copy(items = fundus.tips)
                        else -> if (status in setOf("like", "love", "dislike", "hide")) s.copy(items = s.items.filter { it.k != t.k }) else s
                    }
                }.let { rest ->
                    val wl = fundus.watchlist()
                    if (wl.isNotEmpty()) listOf(Shelf("watch", "Deine Merkliste", wl)) + rest else rest
                }.filter { it.items.isNotEmpty() }
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

    fun logout() {
        Auth.logout(); user = null; fundus = FundusData(); refresh()
    }
}
