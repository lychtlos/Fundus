@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.Screen
import de.lychtlos.fundustv.data.Title
import de.lychtlos.fundustv.data.Tmdb
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Genre mit den TMDB-IDs für Filme bzw. Serien (null = gibt es dort nicht). */
data class Genre(val name: String, val movie: Int?, val tv: Int?)

private val GENRES = listOf(
    Genre("Action", 28, 10759), Genre("Abenteuer", 12, 10759), Genre("Animation", 16, 16), Genre("Komödie", 35, 35),
    Genre("Krimi", 80, 80), Genre("Drama", 18, 18), Genre("Thriller", 53, null), Genre("Mystery", 9648, 9648),
    Genre("Horror", 27, null), Genre("Science-Fiction", 878, 10765), Genre("Fantasy", 14, 10765), Genre("Dokumentation", 99, 99),
    Genre("Familie", 10751, 10751), Genre("Liebe", 10749, null), Genre("Historie", 36, null), Genre("Krieg", 10752, 10768),
    Genre("Western", 37, 37), Genre("Musik", 10402, null), Genre("Reality", null, 10764), Genre("Kinder", null, 10762),
)
/** Zeiträume: (Name, von, bis) */
private val YEARS = listOf(
    Triple("Alle Jahre", null, null), Triple("Ab 2020", "2020-01-01", null), Triple("2010er", "2010-01-01", "2019-12-31"),
    Triple("2000er", "2000-01-01", "2009-12-31"), Triple("1990er", "1990-01-01", "1999-12-31"), Triple("Älter", null, "1989-12-31"),
)
private val RATINGS = listOf(0.0 to "Jede Bewertung", 6.0 to "★ ab 6", 7.0 to "★ ab 7", 8.0 to "★ ab 8")
private val SORTS = listOf("popularity" to "Beliebt", "new" to "Neueste", "rating" to "Beste Bewertung")

/** Zustand der Suche, bleibt beim Wechsel zur Detailseite erhalten. */
class SearchMemory {
    var query: String = ""
    var kind by mutableStateOf("alle")          // alle | serie | film
    var genre by mutableStateOf<Genre?>(null)
    var years by mutableIntStateOf(0)
    var rating by mutableStateOf(0.0)
    var sort by mutableStateOf("popularity")
    var mine by mutableStateOf(false)
    var results: List<Title> = emptyList()
    var page = 1
    var lastKey = ""
    val grid = LazyGridState()
}

/**
 * Suche wie in einem Mediathek-Katalog: ohne Suchbegriff gleich Titel nach Filtern (unabhängig von den eigenen Vorschlägen),
 * mit Suchbegriff die Treffer, ebenfalls gefiltert. Art, Genre, Zeitraum, Bewertung, Sortierung, nur eigene Dienste.
 */
@Composable
fun SearchScreen(app: AppState, mem: SearchMemory) {
    var q by remember { mutableStateOf(mem.query) }
    var results by remember { mutableStateOf(mem.results) }
    var busy by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(true) }
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    val prov = app.providers
    val free = app.fundus.includeFree

    suspend fun load(page: Int): List<Title> = coroutineScope {
        val y = YEARS[mem.years]
        val types = when (mem.kind) { "film" -> listOf("movie"); "serie" -> listOf("tv"); else -> listOf("tv", "movie") }
        if (q.trim().length >= 2) {
            // Suchbegriff: Treffer, dann Filter anwenden
            val gids = mem.genre?.let { g -> listOfNotNull(g.movie, g.tv) }.orEmpty()
            Tmdb.search(q.trim(), page).filter { t ->
                (mem.kind == "alle" || t.m == mem.kind) &&
                    (gids.isEmpty() || t.gids.any { it in gids }) &&
                    (mem.rating <= 0 || (t.rating ?: 0.0) >= mem.rating) &&
                    (y.second == null || t.year >= y.second!!.take(4)) && (y.third == null || (t.year.isNotEmpty() && t.year <= y.third!!.take(4)))
            }
        } else {
            val lists = types.map { type ->
                async {
                    val g = mem.genre?.let { if (type == "movie") it.movie else it.tv }
                    if (mem.genre != null && g == null) return@async emptyList<Title>()
                    val dateKey = if (type == "movie") "primary_release_date" else "first_air_date"
                    val sort = when (mem.sort) { "new" -> "$dateKey.desc"; "rating" -> "vote_average.desc"; else -> "popularity.desc" }
                    val minVotes = when (mem.sort) { "rating" -> if (type == "movie") 400 else 150; else -> if (type == "movie") 80 else 30 }
                    val extra = mutableMapOf<String, String?>("page" to page.toString())
                    g?.let { extra["with_genres"] = it.toString() }
                    y.second?.let { extra["$dateKey.gte"] = it }
                    (y.third ?: if (mem.sort == "new") java.time.LocalDate.now().toString() else null)?.let { extra["$dateKey.lte"] = it }
                    if (mem.rating > 0) extra["vote_average.gte"] = mem.rating.toString()
                    runCatching { Tmdb.discover(type, if (mem.mine) prov else emptyList(), free, sort = sort, minVotes = minVotes, extra = extra) }.getOrDefault(emptyList())
                }
            }.map { it.await() }
            // Serien und Filme abwechselnd
            if (lists.size == 2) {
                val (a, b) = lists
                (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }
            } else lists.first()
        }
    }

    val key = listOf(q.trim(), mem.kind, mem.genre?.name, mem.years, mem.rating, mem.sort, mem.mine).joinToString("|")
    LaunchedEffect(key) {
        mem.query = q
        if (q.isNotEmpty() && q.trim().length < 2) return@LaunchedEffect
        if (results.isNotEmpty() && key == mem.lastKey) return@LaunchedEffect
        delay(if (q.isNotEmpty()) 450 else 150)
        busy = true
        val r = runCatching { load(1) }.getOrDefault(emptyList())
        results = r.distinctBy { it.k }; mem.results = results; mem.page = 1; more = r.size >= 10
        mem.lastKey = key
        busy = false
        runCatching { mem.grid.scrollToItem(0) }
    }
    LaunchedEffect(Unit) { if (mem.results.isEmpty() && q.isEmpty()) runCatching { field.requestFocus() } }

    LazyVerticalGrid(
        state = mem.grid,
        columns = GridCells.Adaptive(130.dp),
        contentPadding = PaddingValues(start = 40.dp, end = 48.dp, top = 26.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(it.maxLineSpan) }
        item(span = full) {
            Box(
                Modifier.fillMaxWidth(0.7f)
                    .border(2.dp, if (focused) C.accent else C.line, RoundedCornerShape(10.dp))
                    .background(C.surface, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                if (q.isEmpty()) Text("Titel suchen … oder unten filtern", color = C.ink2, fontSize = 20.sp)
                BasicTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    textStyle = TextStyle(color = C.ink, fontSize = 20.sp), cursorBrush = SolidColor(C.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { fm.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { focused = it.isFocused },
                )
            }
        }
        item(span = full) {
            ChipRow(listOf("alle" to "Filme & Serien", "serie" to "Serien", "film" to "Filme"), mem.kind, { mem.kind = it }) {
                ChipToggle("Nur meine Dienste", mem.mine) { mem.mine = !mem.mine }
            }
        }
        item(span = full) {
            ChipRow(listOf<Pair<Genre?, String>>(null to "Alle Genres") + GENRES.filter { g -> when (mem.kind) { "film" -> g.movie != null; "serie" -> g.tv != null; else -> true } }.map { it to it.name }, mem.genre, { mem.genre = it })
        }
        item(span = full) {
            ChipRow(YEARS.mapIndexed { i, y -> i to y.first } + RATINGS.map { (it.first + 100).toInt() to it.second } + SORTS.mapIndexed { i, s -> (200 + i) to s.second },
                null, { v -> when { v < 100 -> mem.years = v; v < 200 -> mem.rating = RATINGS.first { it.first.toInt() + 100 == v }.first; else -> mem.sort = SORTS[v - 200].first } },
                selected = { v -> when { v < 100 -> mem.years == v; v < 200 -> (mem.rating.toInt() + 100) == v; else -> SORTS[v - 200].first == mem.sort } },
                gapAfter = setOf(YEARS.lastIndex, 100 + RATINGS.last().first.toInt()))
        }
        item(span = full) {
            Text(
                when {
                    busy && results.isEmpty() -> "Suche …"
                    results.isEmpty() -> "Nichts gefunden. Filter lockern."
                    q.trim().length >= 2 -> "Treffer für „${q.trim()}“"
                    else -> listOfNotNull(mem.genre?.name, YEARS[mem.years].first.takeIf { mem.years > 0 }, RATINGS.first { it.first == mem.rating }.second.takeIf { mem.rating > 0 }).joinToString(" · ").ifEmpty { "Beliebt" } +
                        " – " + SORTS.first { it.first == mem.sort }.second
                },
                fontSize = 15.sp, color = C.ink2, modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(results, key = { it.k }) { t ->
            PosterCard(t, onClick = { app.go(Screen.Detail(t)) }, width = 130.dp, badge = if (t.m == "serie" && mem.kind == "alle") "Serie" else null)
        }
        if (results.isNotEmpty() && more) item(span = full) {
            var loading by remember { mutableStateOf(false) }
            OutlinedButton(onClick = {
                if (loading) return@OutlinedButton
                loading = true
                app.scope.launch {
                    val next = mem.page + 1
                    val add = runCatching { load(next) }.getOrDefault(emptyList())
                    mem.page = next
                    results = (results + add).distinctBy { it.k }; mem.results = results; more = add.size >= 10; loading = false
                }
            }) { Text(if (loading) "Lädt …" else "Mehr laden", fontSize = 15.sp) }
        }
    }
}

/** Reihe aus Auswahl-Chips (eine Auswahl aktiv), optional mit Zusatz am Ende. */
@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    current: T?,
    onPick: (T) -> Unit,
    selected: ((T) -> Boolean)? = null,
    gapAfter: Set<T> = emptySet(),
    extra: (@Composable () -> Unit)? = null,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(options, key = { it.second }) { (v, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = selected?.invoke(v) ?: (v == current), onClick = { onPick(v) }) { Text(label, fontSize = 14.sp) }
                if (v in gapAfter) Box(Modifier.width(18.dp))
            }
        }
        if (extra != null) item { Row { Box(Modifier.width(18.dp)); extra() } }
    }
}

@Composable
private fun ChipToggle(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(selected = on, onClick = onClick) { Text(label, fontSize = 14.sp) }
}
