package de.lychtlos.fundustv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate

/**
 * Neuerscheinungen wie in der Web-App, beschränkt auf Serien und Filme:
 * neue Folgen/Staffeln (verfolgt, Merkliste, Gefällt mir), Kino- und Digitalstart der gemerkten Filme.
 * Höchstens 12 Wochen zurück; gleiche Folge aus zwei Quellen nur einmal.
 */
object Releases {
    private fun ep(s: Int, e: Int) = "S" + s.toString().padStart(2, '0') + "E" + e.toString().padStart(2, '0')
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    suspend fun load(f: FundusData): List<Release> = coroutineScope {
        val from = LocalDate.now().minusWeeks(12)
        val until = LocalDate.now().plusYears(1)
        val gate = Semaphore(6)
        val series = f.items.values.filter { it["m"] == "serie" && (it["status"] in setOf("watch", "like", "love") || f.followed(it["k"] as? String ?: "")) }
        val films = f.items.values.filter { it["m"] == "film" && it["status"] == "watch" }
        val jobs = series.map { o ->
            async {
                val t = FundusData.itemToTitle(o)
                val raw = o["id"]?.toString() ?: ""
                gate.withPermit {
                    runCatching {
                        if (t != null) {
                            val fol = f.followed(t.k)
                            Tmdb.episodes(t.id).map { (d, se, name) ->
                                Release(t.k + ":" + ep(se.first, se.second), t, d, if (se.second == 1) "Neue Staffel" else "Neue Folge", ep(se.first, se.second) + if (name.isNotEmpty()) " · $name" else "", fol)
                            }
                        } else if (raw.startsWith("tvm")) tvmaze(o, raw.drop(3), f) else emptyList()
                    }.getOrDefault(emptyList())
                }
            }
        } + films.map { o ->
            async {
                val t = FundusData.itemToTitle(o) ?: return@async emptyList<Release>()
                gate.withPermit {
                    runCatching { Tmdb.movieDates(t.id).map { (d, l) -> Release(t.k + ":" + l, t, d, l, if (l == "Kinostart") "Im Kino" else "Zum Leihen/Kaufen oder im Abo", false) } }.getOrDefault(emptyList())
                }
            }
        }
        jobs.awaitAll().flatten()
            .filter { r -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { !it.isBefore(from) && !it.isAfter(until) } ?: false }
            // gleiche Folge (z. B. TMDB und TVmaze) nur einmal, verfolgte zuerst
            .sortedByDescending { it.followed }
            .distinctBy { norm(it.title.title) + "|" + (Regex("S\\d+E\\d+").find(it.text)?.value ?: it.label) + "|" + it.date }
            .sortedWith(compareBy<Release> { !it.upcoming }.thenBy { if (it.upcoming) it.date else "" }.thenByDescending { it.date })
    }

    private suspend fun tvmaze(o: Map<String, Any?>, id: String, f: FundusData): List<Release> {
        val d = Http.get("https://api.tvmaze.com/shows/$id?embed[]=nextepisode&embed[]=previousepisode")
        val em = d.optJSONObject("_embedded") ?: return emptyList()
        val k = o["k"] as? String ?: return emptyList()
        val t = Title(
            m = "serie", id = 0, title = o["title"] as? String ?: d.optString("name"),
            year = (o["year"] as? String) ?: "", poster = (o["img"] as? String),
        )
        return listOfNotNull(em.optJSONObject("previousepisode"), em.optJSONObject("nextepisode")).mapNotNull { e ->
            val date = e.str("airdate") ?: return@mapNotNull null
            val s = e.optInt("season"); val n = e.optInt("number")
            Release("$k:" + ep(s, n), t, date, if (n == 1) "Neue Staffel" else "Neue Folge", ep(s, n) + (e.str("name")?.let { " · $it" } ?: ""), f.followed(k))
        }
    }
}
