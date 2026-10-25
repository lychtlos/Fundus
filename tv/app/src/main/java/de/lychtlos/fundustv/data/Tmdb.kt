package de.lychtlos.fundustv.data

import android.net.Uri
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** TMDB (This product uses the TMDB API but is not endorsed or certified by TMDB). */
object Tmdb {
    private const val DEFAULT_KEY = "c59452df2b5d2d720c1e7b4b1bafd42f" // derselbe wie in der Fundus-Web-App
    private const val IMG = "https://image.tmdb.org/t/p/"

    @Volatile var userKey: String = ""
    private val key get() = userKey.trim().ifEmpty { DEFAULT_KEY }

    /* Antworten bleiben im RAM (6 h) und auf dem Gerät (Ordner dir), damit Start und Wechsel schnell gehen. */
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private const val TTL = 6 * 3600 * 1000L
    @Volatile var dir: java.io.File? = null

    private fun file(url: String): java.io.File? = dir?.let {
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        java.io.File(it, h)
    }
    private suspend fun diskGet(url: String, maxAge: Long): JSONObject? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val f = file(url) ?: return@withContext null
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAge) null
        else runCatching { JSONObject(f.readText()) }.getOrNull()
    }
    private suspend fun diskPut(url: String, j: JSONObject) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        file(url)?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(j.toString()) } }
    }

    private suspend fun call(path: String, params: Map<String, String?> = emptyMap(), ttl: Long = TTL): JSONObject {
        val b = Uri.parse("https://api.themoviedb.org/3$path").buildUpon()
        if (!params.containsKey("language")) b.appendQueryParameter("language", "de-DE")
        params.forEach { (k, v) -> if (!v.isNullOrEmpty()) b.appendQueryParameter(k, v) }
        val headers = mutableMapOf<String, String>()
        if (key.length > 40) headers["Authorization"] = "Bearer $key" else b.appendQueryParameter("api_key", key)
        val url = b.build().toString()
        cache[url]?.let { (t, j) -> if (System.currentTimeMillis() - t < ttl) return j }
        diskGet(url, ttl)?.let { cache[url] = System.currentTimeMillis() to it; return it }
        val j = try { Http.get(url, headers) } catch (e: Exception) {
            // offline oder Fehler: lieber alte Daten als nichts
            diskGet(url, Long.MAX_VALUE) ?: throw e
        }
        cache[url] = System.currentTimeMillis() to j
        diskPut(url, j)
        return j
    }

    fun img(path: String?, size: String = "w500"): String? = path?.takeIf { it.isNotEmpty() }?.let { IMG + size + it }

    private fun parse(m: String, r: JSONObject): Title? {
        val id = r.optInt("id").takeIf { it > 0 } ?: return null
        val date = (if (m == "film") r.str("release_date") else r.str("first_air_date")) ?: ""
        return Title(
            m = m, id = id,
            title = (if (m == "film") r.str("title") else r.str("name")) ?: return null,
            year = date.take(4),
            poster = img(r.str("poster_path"), "w342"),
            backdrop = img(r.str("backdrop_path"), "w1280"),
            rating = r.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
            gids = r.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }
                ?: r.optJSONArray("genres")?.objects()?.map { it.optInt("id") } ?: emptyList(),
            votes = r.optInt("vote_count"),
        )
    }

    private fun mOf(type: String) = if (type == "movie") "film" else "serie"

    suspend fun discover(
        type: String,
        providers: List<Int>,
        includeFree: Boolean,
        sort: String = "popularity.desc",
        minVotes: Int = if (type == "movie") 150 else 60,
        extra: Map<String, String?> = emptyMap(),
    ): List<Title> {
        val p = mutableMapOf<String, String?>("sort_by" to sort, "vote_count.gte" to minVotes.toString())
        if (providers.isNotEmpty()) {
            p["with_watch_providers"] = providers.joinToString("|")
            p["watch_region"] = "DE"
            p["with_watch_monetization_types"] = if (includeFree) "flatrate|free|ads" else "flatrate"
        }
        p.putAll(extra)
        val d = call("/discover/$type", p)
        return d.optJSONArray("results")?.objects()?.mapNotNull { parse(mOf(type), it) } ?: emptyList()
    }

    suspend fun trending(): List<Title> {
        val d = call("/trending/all/week")
        return d.optJSONArray("results")?.objects()?.mapNotNull {
            when (it.optString("media_type")) {
                "movie" -> parse("film", it)
                "tv" -> parse("serie", it)
                else -> null
            }
        } ?: emptyList()
    }

    suspend fun search(q: String): List<Title> {
        val d = call("/search/multi", mapOf("query" to q, "include_adult" to "false"))
        return d.optJSONArray("results")?.objects()?.mapNotNull {
            when (it.optString("media_type")) {
                "movie" -> parse("film", it)
                "tv" -> parse("serie", it)
                else -> null
            }
        } ?: emptyList()
    }

    suspend fun recommendations(t: Title): List<Title> {
        val d = call("/${t.type}/${t.id}/recommendations")
        return d.optJSONArray("results")?.objects()?.mapNotNull { parse(t.m, it) } ?: emptyList()
    }

    suspend fun details(t: Title): Details {
        val d = call("/${t.type}/${t.id}", mapOf(
            "append_to_response" to if (t.m == "film") "credits,videos,release_dates" else "aggregate_credits,credits,videos,content_ratings",
            "include_video_language" to "de,en,null",
        ), ttl = 24 * 3600 * 1000L)
        val base = parse(t.m, d) ?: t
        val runtime = if (t.m == "film") {
            d.optInt("runtime").takeIf { it > 0 }?.let { "${it / 60} h ${it % 60} min" } ?: ""
        } else {
            val s = d.optInt("number_of_seasons")
            if (s > 0) "$s Staffel" + (if (s == 1) "" else "n") else ""
        }
        val genres = d.optJSONArray("genres")?.objects()?.mapNotNull { it.str("name") } ?: emptyList()
        val cast = d.optJSONObject("credits")?.optJSONArray("cast")?.objects()?.take(6)?.mapNotNull { it.str("name") } ?: emptyList()
        // Besetzung mit Foto und Rolle (Serien: über alle Staffeln)
        val agg = d.optJSONObject("aggregate_credits")?.optJSONArray("cast")?.objects()
        val people = (agg?.take(20)?.mapNotNull { p ->
            val n = p.str("name") ?: return@mapNotNull null
            val role = p.optJSONArray("roles")?.objects()?.firstOrNull()?.str("character") ?: ""
            Person(n, role, img(p.str("profile_path"), "w185"))
        } ?: d.optJSONObject("credits")?.optJSONArray("cast")?.objects()?.take(20)?.mapNotNull { p ->
            val n = p.str("name") ?: return@mapNotNull null
            Person(n, p.str("character") ?: "", img(p.str("profile_path"), "w185"))
        })?.distinctBy { it.name } ?: emptyList()
        val vids = d.optJSONObject("videos")?.optJSONArray("results")?.objects().orEmpty()
            .filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") }
            .sortedWith(compareBy({ it.optString("iso_639_1") != "de" }, { it.optString("type") != "Trailer" }))
            .mapNotNull { v -> v.str("key")?.let { (v.str("name") ?: "Trailer") to it } }
            .distinctBy { it.second }.take(3)
        val cert = if (t.m == "film") {
            d.optJSONObject("release_dates")?.optJSONArray("results")?.objects()?.firstOrNull { it.optString("iso_3166_1") == "DE" }
                ?.optJSONArray("release_dates")?.objects()?.mapNotNull { it.str("certification") }?.firstOrNull() ?: ""
        } else {
            d.optJSONObject("content_ratings")?.optJSONArray("results")?.objects()?.firstOrNull { it.optString("iso_3166_1") == "DE" }?.str("rating") ?: ""
        }
        val status = when (d.optString("status")) {
            "Returning Series" -> "Läuft"; "Ended" -> "Beendet"; "Canceled" -> "Abgesetzt"; "In Production" -> "In Produktion"
            "Released" -> ""; "Post Production" -> "Bald im Kino"; "Planned" -> "Geplant"; else -> ""
        }
        val makers = if (t.m == "film") d.optJSONObject("credits")?.optJSONArray("crew")?.objects()?.filter { it.optString("job") == "Director" }?.mapNotNull { it.str("name") }.orEmpty()
            else d.optJSONArray("created_by")?.objects()?.mapNotNull { it.str("name") }.orEmpty()
        val next = d.optJSONObject("next_episode_to_air")?.let { e ->
            val dt = e.str("air_date")?.split("-")?.let { p -> if (p.size == 3) p[2] + "." + p[1] + "." + p[0] else null } ?: return@let ""
            "S" + e.optInt("season_number").toString().padStart(2, '0') + "E" + e.optInt("episode_number").toString().padStart(2, '0') + " am " + dt
        } ?: ""
        return Details(
            title = base.copy(why = t.why, poster = base.poster ?: t.poster),
            overview = d.str("overview") ?: "",
            runtime = runtime,
            genres = genres,
            tagline = d.str("tagline") ?: "",
            cast = cast,
            people = people,
            trailers = vids,
            cert = cert,
            status = status,
            seasons = d.optInt("number_of_seasons"),
            episodes = d.optInt("number_of_episodes"),
            makers = makers.take(3),
            makersLabel = if (t.m == "film") "Regie" else "Idee",
            nextEpisode = next,
            networks = d.optJSONArray("networks")?.objects()?.mapNotNull { it.str("name") }.orEmpty().take(2),
        )
    }

    suspend fun availability(t: Title): Availability {
        val d = call("/${t.type}/${t.id}/watch/providers", mapOf("language" to ""))
        val de = d.optJSONObject("results")?.optJSONObject("DE") ?: return Availability()
        fun list(k: String) = de.optJSONArray(k)?.objects()?.map {
            Provider(it.optInt("provider_id"), it.optString("provider_name"), img(it.str("logo_path"), "w154"))
        } ?: emptyList()
        return Availability(
            flat = list("flatrate"),
            free = (list("free") + list("ads")).distinctBy { it.id },
            rent = (list("rent") + list("buy")).distinctBy { it.id },
        )
    }

    /** Letzte und nächste Folge einer Serie: (Datum, Staffel, Folge, Name) */
    suspend fun episodes(id: Int): List<Triple<String, Pair<Int, Int>, String>> {
        val d = call("/tv/$id")
        return listOf("last_episode_to_air", "next_episode_to_air").mapNotNull { k ->
            val e = d.optJSONObject(k) ?: return@mapNotNull null
            val date = e.str("air_date") ?: return@mapNotNull null
            Triple(date, e.optInt("season_number") to e.optInt("episode_number"), e.str("name") ?: "")
        }
    }

    /** Kino- und Digitalstart in Deutschland: (Datum, "Kinostart"/"Digital") */
    suspend fun movieDates(id: Int): List<Pair<String, String>> {
        val d = call("/movie/$id/release_dates", mapOf("language" to ""))
        val de = d.optJSONArray("results")?.objects()?.firstOrNull { it.optString("iso_3166_1") == "DE" } ?: return emptyList()
        return de.optJSONArray("release_dates")?.objects()?.mapNotNull { r ->
            val date = r.str("release_date")?.take(10) ?: return@mapNotNull null
            when (r.optInt("type")) { 3 -> date to "Kinostart"; 4 -> date to "Digital"; else -> null }
        }?.distinctBy { it.second } ?: emptyList()
    }

    /** Streamingdienste in Deutschland (Filme + Serien), nach Bedeutung sortiert. */
    suspend fun providerList(): List<Provider> {
        val out = LinkedHashMap<Int, Pair<Int, Provider>>()
        for (type in listOf("movie", "tv")) {
            val d = call("/watch/providers/$type", mapOf("watch_region" to "DE", "language" to "de-DE"))
            d.optJSONArray("results")?.objects()?.forEach {
                val id = it.optInt("provider_id")
                val prio = it.optJSONObject("display_priorities")?.optInt("DE", 999) ?: it.optInt("display_priority", 999)
                val p = Provider(id, it.optString("provider_name"), img(it.str("logo_path"), "w154"))
                val cur = out[id]
                if (cur == null || prio < cur.first) out[id] = prio to p
            }
        }
        return out.values.sortedBy { it.first }.map { it.second }
    }
}
