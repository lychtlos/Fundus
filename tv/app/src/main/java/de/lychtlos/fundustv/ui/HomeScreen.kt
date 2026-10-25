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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.Screen
import de.lychtlos.fundustv.data.Details
import de.lychtlos.fundustv.data.Title
import de.lychtlos.fundustv.data.Tmdb
import kotlinx.coroutines.delay

/** Zustand der Startseite, der beim Wechsel zur Detailseite erhalten bleibt. */
class HomeMemory {
    val column = LazyListState()
    val rows = HashMap<String, LazyListState>()
    var focusKey: String? = null
    var hero: Title? = null
}

@Composable
fun HomeScreen(app: AppState, mem: HomeMemory) {
    var hero by remember { mutableStateOf(mem.hero) }
    var heroDetails by remember { mutableStateOf<Details?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(hero?.k) {
        val h = hero ?: return@LaunchedEffect
        heroDetails = null
        delay(300)
        heroDetails = runCatching { Tmdb.details(h) }.getOrNull()
    }
    // gemerkten Fokus nur nutzen, wenn der Titel noch in der Reihe steht
    val restore = mem.focusKey?.takeIf { fk -> app.shelves.any { s -> s.items.any { s.id + "|" + it.k == fk } } }
    LaunchedEffect(app.shelves.isNotEmpty(), restore) {
        if (app.shelves.isNotEmpty() && restore == null) {
            delay(100); runCatching { firstFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        // Hintergrundbild des fokussierten Titels
        val backdrop = heroDetails?.title?.backdrop ?: hero?.backdrop
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(0.72f).height(420.dp).align(Alignment.TopEnd),
            )
            Box(
                Modifier.fillMaxWidth(0.72f).height(420.dp).align(Alignment.TopEnd)
                    .background(Brush.horizontalGradient(listOf(C.bg, Color.Transparent), endX = 500f))
            )
            Box(
                Modifier.fillMaxWidth().height(420.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, C.bg), startY = 150f))
            )
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(app)
            Hero(hero, heroDetails)
            when {
                app.shelves.isEmpty() && app.loading -> Message("Lädt …")
                app.shelves.isEmpty() && app.error != null -> Message(app.error!!)
                app.shelves.isEmpty() -> Message("Nichts gefunden. Dienste in den Einstellungen wählen.")
                else -> LazyColumn(
                    state = mem.column,
                    contentPadding = PaddingValues(bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(app.shelves, key = { it.id }) { s ->
                        ShelfRow(
                            shelf = s,
                            state = mem.rows.getOrPut(s.id) { LazyListState() },
                            onOpen = { t ->
                                mem.focusKey = s.id + "|" + t.k
                                app.go(Screen.Detail(t))
                            },
                            onFocused = { t -> hero = t; mem.hero = t },
                            restoreKey = restore,
                            firstFocus = if (s == app.shelves.first()) firstFocus else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(app: AppState) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 40.dp, top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Brand(26)
        Spacer(Modifier.weight(1f))
        app.user?.let { Text(it.name.ifEmpty { it.email }, fontSize = 13.sp, color = C.ink2, modifier = Modifier.padding(end = 12.dp)) }
        NavIcon(Icons.Filled.Search, "Suchen") { app.go(Screen.Search) }
        NavIcon(Icons.Filled.Refresh, "Neu laden") { app.refresh() }
        NavIcon(Icons.Filled.Settings, "Einstellungen") { app.go(Screen.Settings) }
    }
}

@Composable
fun NavIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(start = 6.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Hero(t: Title?, d: Details?) {
    Column(
        Modifier.fillMaxWidth().height(230.dp).padding(start = 48.dp, top = 18.dp, end = 48.dp),
    ) {
        if (t == null) return@Column
        Text(
            t.title, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = C.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(720.dp),
        )
        Spacer(Modifier.height(6.dp))
        val meta = listOfNotNull(
            if (t.m == "film") "Film" else "Serie",
            t.year.ifEmpty { null },
            d?.runtime?.ifEmpty { null },
            d?.genres?.take(3)?.joinToString(", ")?.ifEmpty { null },
            t.rating?.let { "★ " + String.format(java.util.Locale.GERMANY, "%.1f", it) },
        ).joinToString("  ·  ")
        Text(meta, fontSize = 15.sp, color = C.ink2)
        Spacer(Modifier.height(10.dp))
        if (t.why != null) {
            Text("✦ " + t.why, fontSize = 16.sp, color = C.accent, maxLines = 2, modifier = Modifier.width(680.dp))
            Spacer(Modifier.height(6.dp))
        }
        Text(
            d?.overview ?: "", fontSize = 15.sp, color = C.ink, maxLines = if (t.why != null) 2 else 4,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(680.dp).fillMaxHeight(),
        )
    }
}
