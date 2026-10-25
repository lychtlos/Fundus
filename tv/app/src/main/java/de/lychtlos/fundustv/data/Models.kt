package de.lychtlos.fundustv.data

/** Ein Film oder eine Serie. Schlüssel k wie in Fundus: "film:123" bzw. "serie:456". */
data class Title(
    val m: String,              // "film" oder "serie"
    val id: Int,
    val title: String,
    val year: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val why: String? = null,    // Begründung (KI-Tipp, Merkliste-Reihenfolge, „Weil Dir … gefällt“)
    val gids: List<Int> = emptyList(),
    val votes: Int = 0,
    val ai: Boolean = false,    // von Claude ausgesucht
) {
    val k: String get() = "$m:$id"
    val type: String get() = if (m == "film") "movie" else "tv"
}

data class Provider(val id: Int, val name: String, val logo: String?)

data class Availability(
    val flat: List<Provider> = emptyList(),
    val free: List<Provider> = emptyList(),
    val rent: List<Provider> = emptyList(),
)

data class Details(
    val title: Title,
    val overview: String,
    val runtime: String,
    val genres: List<String>,
    val tagline: String,
    val cast: List<String>,
    val people: List<Person> = emptyList(),
    val trailers: List<Pair<String, String>> = emptyList(),   // (Name, YouTube-Schlüssel)
    val cert: String = "",                                     // FSK
    val status: String = "",
    val seasons: Int = 0,
    val episodes: Int = 0,
    val makers: List<String> = emptyList(),                    // Regie bzw. Idee
    val makersLabel: String = "",
    val nextEpisode: String = "",
    val networks: List<String> = emptyList(),
)

data class Person(val name: String, val role: String, val photo: String?)

/** Ausschnitt aus dem Fundus-Konto (artifacts/fundus/users/{uid}). */
data class FundusData(
    val items: Map<String, Map<String, Any?>> = emptyMap(),
    val providers: List<Int> = emptyList(),
    val includeFree: Boolean = true,
    val tmdbKey: String = "",
    val tips: List<Title> = emptyList(),
    val order: Map<String, Pair<Int, String?>> = emptyMap(),
    val rules: Map<String, Rules> = emptyMap(),
) {
    fun rule(m: String) = rules[m] ?: Rules()

    /** wie passRules() in der Web-App */
    fun passes(t: Title): Boolean {
        val r = rule(t.m)
        val y = t.year.take(4).toIntOrNull()
        if (r.minYear > 0 && y != null && y < r.minYear) return false
        if (r.minR > 0 && t.rating != null && t.votes >= 20 && t.rating < r.minR) return false
        if (r.exGid.isNotEmpty() && t.gids.any { it in r.exGid }) return false
        return true
    }

    fun status(k: String): String? = items[k]?.get("status") as? String
    fun followed(k: String): Boolean = items[k]?.get("fol") == true || items[k]?.get("status") == "follow"

    /** Verfolgte Serien, neueste zuerst. */
    fun followList(): List<Title> = items.values
        .filter { it["m"] == "serie" && (it["fol"] == true || it["status"] == "follow") }
        .sortedByDescending { (it["ts"] as? Number)?.toLong() ?: 0L }
        .mapNotNull { itemToTitle(it) }

    /** Merkliste (nur Filme und Serien), sortiert wie in Fundus: Claude-Reihenfolge, sonst neueste zuerst. */
    fun watchlist(): List<Title> = items.values
        .filter { it["status"] == "watch" && (it["m"] == "film" || it["m"] == "serie") }
        .mapNotNull { itemToTitle(it) }
        .sortedWith(compareBy<Title> { order[it.k]?.first ?: 999 }.thenByDescending { (items[it.k]?.get("ts") as? Number)?.toLong() ?: 0L })
        .map { t -> order[t.k]?.second?.let { t.copy(why = it) } ?: t }

    companion object {
        fun itemToTitle(o: Map<String, Any?>): Title? {
            val m = o["m"] as? String ?: return null
            val id = when (val v = o["id"]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            } ?: return null
            val img = (o["img"] as? String)
            return Title(
                m = m, id = id,
                title = o["title"] as? String ?: "?",
                year = (o["year"] as? String) ?: "",
                poster = img,
                rating = (o["rating"] as? Number)?.toDouble(),
                genres = (o["genres"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                why = o["why"] as? String,
                gids = (o["gids"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
                votes = (o["votes"] as? Number)?.toInt() ?: 0,
            )
        }
    }
}

data class Rules(val minYear: Int = 0, val minR: Double = 0.0, val exGid: List<Int> = emptyList())

/** Eintrag unter „Neuerscheinungen“. date: JJJJ-MM-TT */
data class Release(
    val id: String,
    val title: Title,
    val date: String,
    val label: String,
    val text: String,
    val followed: Boolean,
) {
    val days: Long get() = runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), java.time.LocalDate.parse(date)) }.getOrDefault(0L)
    val upcoming: Boolean get() = days > 0
}
