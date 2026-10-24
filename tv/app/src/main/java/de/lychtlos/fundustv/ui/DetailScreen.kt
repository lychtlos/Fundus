@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.Screen
import de.lychtlos.fundustv.data.Availability
import de.lychtlos.fundustv.data.Details
import de.lychtlos.fundustv.data.Launcher
import de.lychtlos.fundustv.data.Provider
import de.lychtlos.fundustv.data.Title
import de.lychtlos.fundustv.data.Tmdb
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(app: AppState, start: Title) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember(start.k) { mutableStateOf<Details?>(null) }
    var av by remember(start.k) { mutableStateOf<Availability?>(null) }
    var similar by remember(start.k) { mutableStateOf<List<Title>>(emptyList()) }
    var err by remember(start.k) { mutableStateOf<String?>(null) }
    val firstBtn = remember(start.k) { FocusRequester() }

    LaunchedEffect(start.k) {
        launch { d = runCatching { Tmdb.details(start) }.onFailure { err = it.message }.getOrNull() }
        launch { av = runCatching { Tmdb.availability(start) }.getOrDefault(Availability()) }
        launch { similar = runCatching { Tmdb.recommendations(start) }.getOrDefault(emptyList()) }
    }

    val t = d?.title ?: start
    val mine = app.providers.toSet()
    // Abo-Dienste zuerst die eigenen, dann übrige Abo-/Gratis-Angebote
    val watchable: List<Provider> = av?.let { a ->
        (a.flat.filter { it.id in mine } + a.free.filter { it.id in mine } + a.flat.filter { it.id !in mine } + a.free.filter { it.id !in mine })
            .distinctBy { it.id }
    } ?: emptyList()

    LaunchedEffect(av) {
        if (av != null) runCatching { firstBtn.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        val backdrop = t.backdrop
        if (backdrop != null) {
            AsyncImage(
                model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(0.75f).fillMaxSize().align(Alignment.TopEnd),
            )
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.bg, C.bg.copy(alpha = 0.85f), Color.Transparent), endX = 1300f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, C.bg), startY = 500f)))
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 56.dp, top = 48.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(Modifier.width(760.dp)) {
                    Text(if (t.m == "film") "FILM" else "SERIE", fontSize = 13.sp, letterSpacing = 2.sp, color = if (t.m == "film") C.film else C.serie, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(t.title, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    val meta = listOfNotNull(
                        t.year.ifEmpty { null },
                        d?.runtime?.ifEmpty { null },
                        d?.genres?.take(3)?.joinToString(", ")?.ifEmpty { null },
                        t.rating?.let { "★ " + String.format(java.util.Locale.GERMANY, "%.1f", it) + " (TMDB)" },
                    ).joinToString("  ·  ")
                    Text(meta, fontSize = 16.sp, color = C.ink2)
                    val status = app.fundus.status(t.k)
                    statusLabel(status)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 14.sp, color = C.mark, fontWeight = FontWeight.SemiBold)
                    }
                    if (start.why != null) {
                        Spacer(Modifier.height(12.dp))
                        Text("✦ " + start.why, fontSize = 17.sp, color = C.accent)
                    }
                    if (!d?.tagline.isNullOrEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(d!!.tagline, fontSize = 17.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(d?.overview?.ifEmpty { "Keine Beschreibung vorhanden." } ?: err ?: "", fontSize = 16.sp, color = C.ink, maxLines = 6, overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
                    if (!d?.cast.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Mit " + d!!.cast.joinToString(", "), fontSize = 14.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Ansehen-Knöpfe
            item {
                Column {
                    Text(
                        when {
                            av == null -> "Prüfe Verfügbarkeit …"
                            watchable.isEmpty() -> "Derzeit in keinem Abo-Dienst in Deutschland."
                            else -> "Ansehen bei"
                        },
                        fontSize = 15.sp, color = C.ink2, modifier = Modifier.padding(bottom = 10.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 56.dp, top = 4.dp, bottom = 4.dp)) {
                        items(watchable, key = { it.id }) { p ->
                            val own = p.id in mine
                            val installed = remember(p.id) { Launcher.isInstalled(ctx, p) }
                            val mod = if (p == watchable.first()) Modifier.focusRequester(firstBtn) else Modifier
                            val content: @Composable () -> Unit = {
                                if (p.logo != null) {
                                    AsyncImage(p.logo, null, Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(p.name + if (!installed) " (nicht installiert)" else "", fontSize = 15.sp)
                            }
                            val onClick: () -> Unit = {
                                scope.launch { Launcher.open(ctx, p, t)?.let { app.say(it) } }
                            }
                            if (own) Button(onClick = onClick, modifier = mod) { content() }
                            else OutlinedButton(onClick = onClick, modifier = mod) { content() }
                        }
                    }
                    val rent = av?.rent.orEmpty().filter { r -> watchable.none { it.id == r.id } }
                    if (rent.isNotEmpty()) {
                        Text(
                            "Leihen oder kaufen: " + rent.take(5).joinToString(", ") { it.name },
                            fontSize = 14.sp, color = C.ink2, modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }

            // Fundus-Aktionen
            item {
                val status = app.fundus.status(t.k)
                val watch = status == "watch"
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(
                        if (watch) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (watch) "Auf der Merkliste" else "Merken", watch,
                        if (av != null && watchable.isEmpty()) Modifier.focusRequester(firstBtn) else Modifier,
                    ) {
                        app.setStatus(t, if (watch) null else "watch")
                    }
                    ActionButton(Icons.Filled.ThumbUp, "Gefällt mir", status == "like") { app.setStatus(t, if (status == "like") null else "like") }
                    ActionButton(Icons.Filled.Favorite, "Liebling", status == "love") { app.setStatus(t, if (status == "love") null else "love") }
                    ActionButton(Icons.Filled.ThumbDown, "Gefällt nicht", status == "dislike") { app.setStatus(t, if (status == "dislike") null else "dislike") }
                    ActionButton(Icons.Filled.VisibilityOff, "Kein Interesse", status == "hide") { app.setStatus(t, if (status == "hide") null else "hide") }
                }
            }

            if (similar.isNotEmpty()) {
                item {
                    Column {
                        Text("Ähnliche Titel", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.ink, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(end = 56.dp, top = 10.dp, bottom = 10.dp)) {
                            items(similar, key = { it.k }) { s ->
                                PosterCard(s, onClick = { app.go(Screen.Detail(s)) }, width = 130.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(s: String?) = when (s) {
    "watch" -> "Auf Deiner Merkliste"
    "like" -> "Gefällt Dir"
    "love" -> "Einer Deiner Lieblinge"
    "dislike" -> "Gefällt Dir nicht"
    "hide" -> "Kein Interesse"
    else -> null
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = if (active) ButtonDefaults.colors(containerColor = C.accent.copy(alpha = 0.25f), contentColor = C.accent) else ButtonDefaults.colors(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp)
    }
}
