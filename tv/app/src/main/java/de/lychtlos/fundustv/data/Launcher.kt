package de.lychtlos.fundustv.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject

/**
 * Öffnet einen Titel in der App des Streamingdienstes.
 * Reihenfolge: Link zum Titel (JustWatch) in der Dienst-App → Dienst-App starten.
 */
object Launcher {
    /* sichere Paketnamen der Android-TV-Apps; alle anderen per Name der installierten App */
    private val KNOWN = mapOf(
        8 to "com.netflix.ninja", 1796 to "com.netflix.ninja",
        9 to "com.amazon.amazonvideo.livingroom", 119 to "com.amazon.amazonvideo.livingroom", 10 to "com.amazon.amazonvideo.livingroom",
        337 to "com.disney.disneyplus",
        350 to "com.apple.atve.androidtv.appletv", 2 to "com.apple.atve.androidtv.appletv",
        192 to "com.google.android.youtube.tv", 188 to "com.google.android.youtube.tv",
    )

    data class App(val pkg: String, val label: String)

    private var appsCache: List<App>? = null

    fun tvApps(ctx: Context): List<App> = appsCache ?: run {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val list = pm.queryIntentActivities(i, 0).map {
            App(it.activityInfo.packageName, it.loadLabel(pm).toString())
        }.distinctBy { it.pkg }
        appsCache = list
        list
    }

    fun norm(s: String) = s.lowercase().replace("+", "plus").replace(Regex("[^a-z0-9]"), "")

    private fun installed(ctx: Context, pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    /** Paketname der Dienst-App, falls installiert. */
    fun packageFor(ctx: Context, p: Provider): String? {
        val name = p.name
        // „… Amazon Channel“ läuft in Prime Video, „… Apple TV Channel“ in Apple TV
        val target = when {
            name.contains("Amazon Channel", true) -> "com.amazon.amazonvideo.livingroom"
            name.contains("Apple TV Channel", true) -> "com.apple.atve.androidtv.appletv"
            else -> KNOWN[p.id]
        }
        if (target != null && installed(ctx, target)) return target
        val n = norm(name.replace(Regex("(?i)(with ads|basic|standard|premium|amazon channel)"), ""))
        if (n.length < 3) return null
        return tvApps(ctx).firstOrNull { a ->
            val l = norm(a.label)
            l.length >= 3 && (l == n || n.startsWith(l) || l.startsWith(n))
        }?.pkg
    }

    fun isInstalled(ctx: Context, p: Provider) = packageFor(ctx, p) != null

    /* ---------- JustWatch: direkter Link zum Titel beim Dienst ---------- */
    private val jwCache = HashMap<String, List<Pair<Provider, String>>>()

    private val JW_QUERY = """
query GetSuggestedTitles(${'$'}country: Country!, ${'$'}language: Language!, ${'$'}first: Int!, ${'$'}filter: TitleFilter) {
  popularTitles(country: ${'$'}country, first: ${'$'}first, filter: ${'$'}filter) {
    edges { node { objectType
      ... on MovieOrShow {
        content(country: ${'$'}country, language: ${'$'}language) { title originalReleaseYear externalIds { tmdbId } }
        offers(country: ${'$'}country, platform: WEB) { monetizationType standardWebURL package { packageId clearName } }
      }
    } }
  }
}"""

    private suspend fun jwOffers(t: Title): List<Pair<Provider, String>> {
        jwCache[t.k]?.let { return it }
        val vars = JSONObject().put("country", "DE").put("language", "de").put("first", 8)
            .put("filter", JSONObject().put("searchQuery", t.title))
        val r = Http.postJson("https://apis.justwatch.com/graphql", JSONObject().put("query", JW_QUERY).put("variables", vars))
        val edges = r.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges")?.objects() ?: emptyList()
        val wantType = if (t.m == "film") "MOVIE" else "SHOW"
        val node = edges.map { it.optJSONObject("node") ?: JSONObject() }.firstOrNull { n ->
            n.optString("objectType") == wantType &&
                n.optJSONObject("content")?.optJSONObject("externalIds")?.optString("tmdbId") == t.id.toString()
        } ?: return emptyList<Pair<Provider, String>>().also { jwCache[t.k] = it }
        val order = listOf("FLATRATE", "FREE", "ADS", "RENT", "BUY")
        val out = node.optJSONArray("offers")?.objects().orEmpty()
            .sortedBy { order.indexOf(it.optString("monetizationType")).let { i -> if (i < 0) 9 else i } }
            .mapNotNull { o ->
                val url = o.str("standardWebURL") ?: return@mapNotNull null
                val pk = o.optJSONObject("package") ?: return@mapNotNull null
                Provider(pk.optInt("packageId"), pk.optString("clearName"), null) to url
            }
        jwCache[t.k] = out
        return out
    }

    private fun tryStart(ctx: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    private fun launchApp(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return tryStart(ctx, i)
    }

    fun tivimateInstalled(ctx: Context) = installed(ctx, Helper.TIVIMATE)

    /** TiviMate starten; mit eingeschaltetem Helfer wird der Titel dort ins Suchfeld eingetragen. */
    fun tivimate(ctx: Context, t: Title): String? {
        if (!launchApp(ctx, Helper.TIVIMATE)) return "TiviMate ist nicht installiert"
        if (Helper.enabled(ctx)) { Helper.search = Helper.Search(Helper.TIVIMATE, t.title); return null }
        return "Helfer aus: in TiviMate selbst nach „${t.title}“ suchen"
    }

    /** @return Hinweistext für die Oberfläche oder null bei Erfolg ohne Hinweis. */
    suspend fun open(ctx: Context, p: Provider, t: Title): String? {
        val pkg = packageFor(ctx, p)
        val url = try {
            jwOffers(t).firstOrNull { (jp, _) -> jp.id == p.id || norm(jp.name) == norm(p.name) }?.second
        } catch (e: Exception) { null }

        if (url != null && pkg != null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
            if (pkg == "com.netflix.ninja") i.putExtra("source", "30")
            if (tryStart(ctx, i)) {
                if (Helper.enabled(ctx)) { Helper.resend = Helper.Resend(pkg, Intent(i)); return null }
                return "Profilauswahl? Danach zurück und nochmal drücken"
            }
        }
        if (pkg != null && launchApp(ctx, pkg)) {
            // kein direkter Link: der Helfer trägt den Titel in die Suche der App ein
            if (Helper.enabled(ctx)) { Helper.search = Helper.Search(pkg, t.title); return null }
            return "${t.title}: In ${p.name} selbst suchen (kein direkter Link)"
        }
        return "${p.name} ist auf diesem Gerät nicht installiert."
    }
}
