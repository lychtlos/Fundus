@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.focus.onFocusChanged
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
    var tint by remember(start.k) { mutableStateOf<Color?>(null) }
    var full by remember(start.k) { mutableStateOf(false) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val toTop: (androidx.compose.ui.focus.FocusState) -> Unit = { f -> if (f.hasFocus && list.firstVisibleItemIndex + list.firstVisibleItemScrollOffset > 0) scope.launch { list.animateScrollToItem(0) } }

    LaunchedEffect(start.k) {
        launch { d = runCatching { Tmdb.details(start) }.onFailure { err = it.message }.getOrNull() }
        launch { av = runCatching { Tmdb.availability(start) }.getOrDefault(Availability()) }
        launch { similar = runCatching { Tmdb.recommendations(start) }.getOrDefault(emptyList()) }
        // Hintergrundfarbe aus dem Cover, wie in der Web-App
        launch { tint = start.poster?.let { coverColor(ctx, it) } }
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

    val glow by animateColorAsState(tint ?: C.bg, tween(600), label = "tint")
    Box(Modifier.fillMaxSize().background(C.bg).background(Brush.verticalGradient(listOf(lerp(C.bg, glow, 0.38f), C.bg)))) {
        val backdrop = t.backdrop
        if (backdrop != null) {
            AsyncImage(
                model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(0.75f).fillMaxSize().align(Alignment.TopEnd),
            )
            val base = lerp(C.bg, glow, 0.30f)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(base, base.copy(alpha = 0.85f), Color.Transparent), endX = 1300f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, C.bg), startY = 500f)))
        }

        // Google TV scrollt fokussierte Elemente standardmäßig weit nach oben (Handlung wäre abgeschnitten) – hier nur so weit wie nötig
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MinimalScroll) {
        LazyColumn(
            state = list,
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
                    (listOfNotNull(statusLabel(status), if (app.fundus.followed(t.k)) "Verfolgt" else null).joinToString(" · ").ifEmpty { null })?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 14.sp, color = C.mark, fontWeight = FontWeight.SemiBold)
                    }
                    if (start.why != null) {
                        Spacer(Modifier.height(12.dp))
                        Text((if (start.ai) "✦ " else "↳ ") + start.why, fontSize = 17.sp, color = C.accent)
                    }
                    if (!d?.tagline.isNullOrEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(d!!.tagline, fontSize = 17.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
                    }
                    // Infozeile: FSK, Status, Staffeln/Folgen, Regie bzw. Idee, Sender, nächste Folge
                    val dd = d
                    if (dd != null) {
                        val info = listOfNotNull(
                            dd.cert.ifEmpty { null }?.let { "FSK $it" },
                            dd.status.ifEmpty { null },
                            if (t.m == "serie" && dd.episodes > 0) "${dd.seasons} Staffel${if (dd.seasons == 1) "" else "n"}, ${dd.episodes} Folgen" else null,
                            dd.makers.takeIf { it.isNotEmpty() }?.let { dd.makersLabel + ": " + it.joinToString(", ") },
                            dd.networks.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        ).joinToString("  ·  ")
                        if (info.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(info, fontSize = 14.sp, color = C.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (dd.nextEpisode.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text("Nächste Folge: " + dd.nextEpisode, fontSize = 14.sp, color = C.accent, fontWeight = FontWeight.SemiBold) }
                    }
                    Spacer(Modifier.height(10.dp))
                    // Handlung: fokussierbar (damit man zurück nach oben kommt), OK zeigt den ganzen Text
                    androidx.tv.material3.Surface(
                        onClick = { full = !full },
                        modifier = Modifier.fillMaxWidth().onFocusChanged(toTop),
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = C.surface2.copy(alpha = 0.85f), contentColor = C.ink, focusedContentColor = C.ink),
                        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(d?.overview?.ifEmpty { "Keine Beschreibung." } ?: err ?: "", fontSize = 16.sp, color = C.ink, maxLines = if (full) 40 else 5, overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
                            if ((d?.overview?.length ?: 0) > 320) Text(if (full) "Weniger" else "Ganzen Text lesen (OK)", fontSize = 13.sp, color = C.ink2, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            // Ansehen-Knöpfe
            item {
                Column(Modifier.onFocusChanged(toTop)) {
                    Text(
                        when {
                            av == null -> "Verfügbarkeit …"
                            watchable.isEmpty() -> "In keinem Abo"
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
                                Text(p.name + if (!installed) " · nicht installiert" else "", fontSize = 15.sp)
                            }
                            val onClick: () -> Unit = {
                                scope.launch { Launcher.open(ctx, p, t)?.let { app.say(it) } }
                            }
                            if (own) Button(onClick = onClick, modifier = mod) { content() }
                            else OutlinedButton(onClick = onClick, modifier = mod) { content() }
                        }
                    }
                    if (Launcher.tivimateInstalled(ctx)) {
                        OutlinedButton(
                            onClick = { Launcher.tivimate(ctx, t)?.let { app.say(it) } },
                            modifier = Modifier.padding(top = 10.dp).let { if (av != null && watchable.isEmpty()) it.focusRequester(firstBtn) else it },
                        ) { Text("In TiviMate suchen", fontSize = 15.sp) }
                    }
                    val rent = av?.rent.orEmpty().filter { r -> watchable.none { it.id == r.id } }
                    if (rent.isNotEmpty()) {
                        Text(
                            "Leihen/Kaufen: " + rent.take(5).joinToString(", ") { it.name },
                            fontSize = 14.sp, color = C.ink2, modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }

            // Fundus-Aktionen
            item {
                val status = app.fundus.status(t.k)
                val watch = status == "watch"
                val fol = app.fundus.followed(t.k)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t.m == "serie") ActionButton(if (fol) Icons.Filled.PlayCircle else Icons.Outlined.PlayCircle, if (fol) "Verfolgt" else "Verfolgen", fol) { app.toggleFol(t) }
                    ActionButton(
                        if (watch) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (watch) "Gemerkt" else "Merken", watch,
                        if (av != null && watchable.isEmpty() && !Launcher.tivimateInstalled(ctx)) Modifier.focusRequester(firstBtn) else Modifier,
                    ) {
                        app.setStatus(t, if (watch) null else "watch")
                    }
                    ActionButton(Icons.Filled.ThumbUp, "Gefällt mir", status == "like") { app.setStatus(t, if (status == "like") null else "like") }
                    ActionButton(Icons.Filled.Favorite, "Liebling", status == "love") { app.setStatus(t, if (status == "love") null else "love") }
                    ActionButton(Icons.Filled.ThumbDown, "Gefällt nicht", status == "dislike") { app.setStatus(t, if (status == "dislike") null else "dislike") }
                    ActionButton(Icons.Filled.VisibilityOff, "Kein Interesse", status == "hide") { app.setStatus(t, if (status == "hide") null else "hide") }
                }
            }

            // Trailer
            val trailers = d?.trailers.orEmpty()
            if (trailers.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        trailers.forEach { (name, key) ->
                            OutlinedButton(onClick = { openTrailer(ctx, key)?.let { app.say(it) } }) {
                                Icon(Icons.Filled.PlayCircle, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (trailers.size == 1) "Trailer" else name.take(28), fontSize = 15.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Besetzung
            val people = d?.people.orEmpty()
            if (people.isNotEmpty()) {
                item {
                    Column {
                        Text("Besetzung", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.ink, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(end = 56.dp, top = 8.dp, bottom = 8.dp)) {
                            items(people, key = { it.name + it.role }) { p -> PersonCard(p) }
                        }
                    }
                }
            }

            if (similar.isNotEmpty()) {
                item {
                    Column {
                        Text("Ähnlich", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.ink, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
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
}

/** Scrollt nur so weit, dass das fokussierte Element sichtbar ist. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val MinimalScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0 && end <= containerSize -> 0f
            offset < 0 && end > containerSize -> 0f
            offset < 0 -> offset
            else -> minOf(end - containerSize, offset)
        }
    }
}

/** Trailer in der YouTube-App des Fernsehers öffnen. */
private fun openTrailer(ctx: android.content.Context, key: String): String? {
    val u = android.net.Uri.parse("https://www.youtube.com/watch?v=$key")
    for (pkg in listOf("com.google.android.youtube.tv", "com.google.android.youtube", null)) {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, u).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        if (runCatching { ctx.startActivity(i) }.isSuccess) return null
    }
    return "YouTube ist nicht installiert"
}

@Composable
private fun PersonCard(p: de.lychtlos.fundustv.data.Person) {
    androidx.tv.material3.Surface(
        onClick = {},
        modifier = Modifier.width(120.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = C.surface2, contentColor = C.ink, focusedContentColor = C.ink),
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.surface2), contentAlignment = Alignment.Center) {
                if (p.photo != null) AsyncImage(p.photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(p.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), fontSize = 22.sp, color = C.ink2, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(p.name, fontSize = 13.sp, color = C.ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (p.role.isNotEmpty()) Text(p.role, fontSize = 12.sp, color = C.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private fun statusLabel(s: String?) = when (s) {
    "watch" -> "Gemerkt"
    "like" -> "Gefällt Dir"
    "love" -> "♥ Liebling"
    "dislike" -> "Gefällt nicht"
    "hide" -> "Ausgeblendet"
    else -> null
}

/** Mittlere, kräftige Farbe des Covers (für den Hintergrund der Detailseite). */
private suspend fun coverColor(ctx: android.content.Context, url: String): Color? = runCatching {
    val req = coil.request.ImageRequest.Builder(ctx).data(url).size(36, 54).allowHardware(false).build()
    val bmp = (coil.Coil.imageLoader(ctx).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
        val p = bmp.getPixel(x, y)
        val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
        val mx = maxOf(pr, pg, pb); val mn = minOf(pr, pg, pb)
        val w = 1 + (mx - mn) / 16        // farbige Pixel zählen mehr als graue
        r += pr * w; g += pg * w; b += pb * w; n += w
    }
    if (n == 0L) null else Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}.getOrNull()

@Composable
private fun ActionButton(icon: ImageVector, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // kurzes „Pop“ beim Aktivieren, wie das Herz in der Web-App
    val pop = remember { Animatable(1f) }
    var was by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !was) { pop.snapTo(1.4f); pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f)) }
        was = active
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = if (active) ButtonDefaults.colors(containerColor = C.accent.copy(alpha = 0.25f), contentColor = C.accent) else ButtonDefaults.colors(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp)
    }
}
