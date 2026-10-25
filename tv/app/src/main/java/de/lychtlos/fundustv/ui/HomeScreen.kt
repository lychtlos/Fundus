@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.Screen
import de.lychtlos.fundustv.Shelf
import de.lychtlos.fundustv.data.Details
import de.lychtlos.fundustv.data.Title
import de.lychtlos.fundustv.data.Tmdb
import kotlinx.coroutines.delay

/** Zustand einer Seite, der beim Wechsel zur Detailseite erhalten bleibt. */
class HomeMemory {
    val column = LazyListState()
    val rows = HashMap<String, LazyListState>()
    var focusKey: String? = null
    var hero: Title? = null
}

fun medColor(m: String) = if (m == "film") C.film else C.serie
fun medLabel(m: String) = if (m == "film") "Film" else "Serie"

/** Gruppe von Reihen mit großer, farbiger Überschrift (Serien / Filme). */
data class Group(val m: String?, val title: String?, val shelves: List<Shelf>)

/** Startseite: Bereichswahl wie im Menü der Smartphone-App, Serien und Filme klar getrennt. */
@Composable
fun DiscoverScreen(app: AppState, mem: HomeMemory) {
    val groups = when (app.med) {
        "alle" -> listOf("serie", "film").map { m ->
            // pro Bereich die wichtigsten Reihen
            val s = app.shelves.filter { it.m == m }
            Group(m, if (m == "serie") "Serien" else "Filme", (s.filter { !it.id.startsWith("cat-") }.take(3) + s.filter { it.id.startsWith("cat-") }.take(1)))
        }
        else -> listOf(Group(app.med, null, app.shelves.filter { it.m == app.med }))
    }.filter { it.shelves.isNotEmpty() }

    Browse(
        app = app, mem = mem, groups = groups,
        header = {
            Row(Modifier.padding(start = 8.dp, top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("alle" to "Alles", "serie" to "Serien", "film" to "Filme").forEach { (id, l) ->
                    FilterChip(selected = app.med == id, onClick = { app.med = id; mem.focusKey = null }) {
                        Text(l, fontSize = 15.sp)
                    }
                }
            }
        },
        empty = when {
            app.loading -> "Lädt …"
            app.error != null -> app.error!!
            else -> "Nichts gefunden. Dienste in den Einstellungen wählen."
        },
    )
}

/** Merkliste: Serien und Filme getrennt, Reihenfolge von Claude. */
@Composable
fun WatchScreen(app: AppState, mem: HomeMemory) {
    val wl = app.fundus.watchlist()
    val hasOrder = app.fundus.order.isNotEmpty()
    val groups = listOf("serie", "film").map { m ->
        Group(m, if (m == "serie") "Serien" else "Filme", listOfNotNull(
            wl.filter { it.m == m }.takeIf { it.isNotEmpty() }?.let { Shelf("wl-$m", "Gemerkt", it, m, if (hasOrder) "Reihenfolge von Claude: Bestes zuerst" else null) },
        ))
    }.filter { it.shelves.isNotEmpty() }
    Browse(
        app = app, mem = mem, groups = groups,
        header = { PageTitle("Merkliste") },
        empty = if (app.user == null) "Erst mit Fundus verbinden" else "Die Merkliste ist leer.",
    )
}

@Composable
fun PageTitle(t: String, sub: String? = null) {
    Column(Modifier.padding(start = 8.dp, top = 26.dp)) {
        Text(t, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
        if (sub != null) Text(sub, fontSize = 15.sp, color = C.ink2)
    }
}

/** Gemeinsames Gerüst: Kopf, Infobereich des fokussierten Titels, Reihen nach Bereichen. */
@Composable
fun Browse(app: AppState, mem: HomeMemory, groups: List<Group>, header: @Composable () -> Unit, empty: String) {
    var hero by remember { mutableStateOf(mem.hero) }
    var heroDetails by remember { mutableStateOf<Details?>(null) }
    val firstFocus = remember { FocusRequester() }
    val all = groups.flatMap { it.shelves }

    LaunchedEffect(hero?.k) {
        val h = hero ?: return@LaunchedEffect
        heroDetails = null
        delay(300)
        heroDetails = runCatching { Tmdb.details(h) }.getOrNull()
    }
    val restore = mem.focusKey?.takeIf { fk -> all.any { s -> s.items.any { s.id + "|" + it.k == fk } } }
    LaunchedEffect(all.isNotEmpty(), restore, groups.firstOrNull()?.shelves?.firstOrNull()?.id) {
        if (all.isNotEmpty() && restore == null) { delay(120); runCatching { firstFocus.requestFocus() } }
    }

    Box(Modifier.fillMaxSize()) {
        val backdrop = heroDetails?.title?.backdrop ?: hero?.backdrop
        if (backdrop != null) {
            AsyncImage(
                model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(0.66f).height(400.dp).align(Alignment.TopEnd),
            )
            Box(Modifier.fillMaxWidth(0.66f).height(400.dp).align(Alignment.TopEnd).background(Brush.horizontalGradient(listOf(C.bg, Color.Transparent), endX = 500f)))
            Box(Modifier.fillMaxWidth().height(400.dp).background(Brush.verticalGradient(listOf(Color.Transparent, C.bg), startY = 150f)))
        }
        Column(Modifier.fillMaxSize().padding(start = 40.dp)) {
            header()
            Hero(hero, heroDetails)
            if (all.isEmpty()) Message(empty)
            else LazyColumn(
                state = mem.column,
                contentPadding = PaddingValues(bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                groups.forEach { g ->
                    if (g.title != null && g.m != null) item(key = "h-" + g.m) { GroupHeader(g.title, medColor(g.m)) }
                    g.shelves.forEach { s ->
                        item(key = s.id) {
                            ShelfRow(
                                shelf = s,
                                state = mem.rows.getOrPut(s.id) { LazyListState() },
                                onOpen = { t -> mem.focusKey = s.id + "|" + t.k; app.go(Screen.Detail(t)) },
                                onFocused = { t -> hero = t; mem.hero = t },
                                restoreKey = restore,
                                firstFocus = if (s == all.first()) firstFocus else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, color: Color) {
    Row(Modifier.padding(start = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).height(30.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

/** Infobereich oben: zeigt zum fokussierten Titel, warum er vorgeschlagen wird. */
@Composable
private fun Hero(t: Title?, d: Details?) {
    Column(Modifier.fillMaxWidth().height(210.dp).padding(start = 8.dp, top = 16.dp, end = 48.dp)) {
        if (t == null) return@Column
        Text(medLabel(t.m).uppercase(), fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = medColor(t.m))
        Text(
            t.title, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = C.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(720.dp),
        )
        Spacer(Modifier.height(4.dp))
        val meta = listOfNotNull(
            t.year.ifEmpty { null },
            d?.runtime?.ifEmpty { null },
            d?.genres?.take(3)?.joinToString(", ")?.ifEmpty { null },
            t.rating?.let { "★ " + String.format(java.util.Locale.GERMANY, "%.1f", it) },
        ).joinToString("  ·  ")
        Text(meta, fontSize = 15.sp, color = C.ink2)
        Spacer(Modifier.height(8.dp))
        if (!t.why.isNullOrEmpty()) {
            Text((if (t.ai) "✦ " else "↳ ") + t.why, fontSize = 17.sp, color = C.accent, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.width(680.dp))
            Spacer(Modifier.height(6.dp))
        }
        Text(
            d?.overview ?: "", fontSize = 15.sp, color = C.ink, maxLines = if (t.why != null) 2 else 4,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(680.dp).fillMaxHeight(),
        )
    }
}
