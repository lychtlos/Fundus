@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.Screen
import de.lychtlos.fundustv.data.Release
import de.lychtlos.fundustv.data.Title

/** Neuerscheinungen wie in der Smartphone-App: Kommend, Erschienen (12 Wochen), verfolgte Serien grün umrandet. */
@Composable
fun NewsScreen(app: AppState) {
    val list = app.releases
    val first = remember { FocusRequester() }
    LaunchedEffect(list != null) { if (!list.isNullOrEmpty()) runCatching { first.requestFocus() } }
    val up = list.orEmpty().filter { it.upcoming }
    val past = list.orEmpty().filter { !it.upcoming }
    val nf = app.fundus.followList().size
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 40.dp),
        contentPadding = PaddingValues(end = 56.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PageTitle("Neuerscheinungen", if (nf > 0) "Grün umrandet: verfolgte Serien" else "Neue Folgen und Filmstarts zu Deinen Listen") }
        when {
            app.user == null -> item { Message("Erst mit Fundus verbinden") }
            list == null || (app.releasesLoading && list.isEmpty()) -> item { Message("Prüfe …") }
            list.isEmpty() -> item { Message("Nichts Neues zu Deinen Einträgen.") }
        }
        if (up.isNotEmpty()) item { Sub("Kommend") }
        items(up, key = { it.id }) { r -> ReleaseRow(app, r, if (r == list?.first()) first else null) }
        if (past.isNotEmpty()) item { Sub("Erschienen · letzte 12 Wochen") }
        items(past, key = { it.id }) { r -> ReleaseRow(app, r, if (r == list?.first()) first else null) }
    }
}

/** Übersicht der verfolgten Serien mit nächster bzw. letzter Folge. */
@Composable
fun FollowScreen(app: AppState) {
    val fol = app.fundus.followList()
    val rel = app.releases.orEmpty()
    val first = remember { FocusRequester() }
    LaunchedEffect(fol.isNotEmpty()) { if (fol.isNotEmpty()) runCatching { first.requestFocus() } }
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 40.dp),
        contentPadding = PaddingValues(end = 56.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PageTitle("Verfolgt", "Neue Folgen kommen per Push") }
        if (fol.isEmpty()) item { Message(if (app.user == null) "Erst mit Fundus verbinden" else "Noch keine Serie verfolgt. Auf der Detailseite „Verfolgen“ wählen.") }
        items(fol, key = { it.k }) { t ->
            val mine = rel.filter { it.title.title == t.title || it.title.k == t.k }
            val next = mine.filter { it.upcoming }.minByOrNull { it.date }
            val last = mine.filter { !it.upcoming }.maxByOrNull { it.date }
            val text = when {
                next != null -> "Nächste: " + dateDe(next.date) + " · " + next.text
                last != null -> "Zuletzt: " + dateDe(last.date) + " · " + last.text
                app.releasesLoading -> "Prüfe …"
                else -> "Kein Termin bekannt"
            }
            FollowRow(t, text, if (t == fol.first()) first else null, highlight = next != null) { app.go(Screen.Detail(t)) }
        }
    }
}

private fun dateDe(d: String) = d.split("-").let { if (it.size == 3) it[2] + "." + it[1] + "." + it[0] else d }

@Composable
private fun Sub(t: String) {
    Text(t.uppercase(), fontSize = 13.sp, letterSpacing = 1.5.sp, color = C.ink2, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp))
}

@Composable
private fun ReleaseRow(app: AppState, r: Release, fr: FocusRequester?) {
    val d = r.date.split("-")
    val rel = when {
        r.days == 1L -> "morgen"
        r.days > 1 -> "in ${r.days} Tagen"
        r.days == 0L -> "heute"
        else -> null
    }
    Surface(
        onClick = { if (r.title.id > 0) app.go(Screen.Detail(r.title)) else app.say("Keine Details verfügbar") },
        modifier = Modifier.fillMaxWidth().let { if (fr != null) it.focusRequester(fr) else it },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (r.followed) C.accent.copy(alpha = 0.08f) else C.surface, focusedContainerColor = C.surface2, contentColor = C.ink, focusedContentColor = C.ink),
        border = ClickableSurfaceDefaults.border(
            border = if (r.followed) Border(BorderStroke(2.dp, C.accent), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, if (r.followed) C.accent else C.ink), shape = RoundedCornerShape(14.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(r.title, 56)
            Column(Modifier.width(110.dp).padding(start = 18.dp)) {
                Text(if (d.size == 3) d[2] + "." + d[1] + "." else r.date, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                if (d.size == 3) Text(d[0], fontSize = 13.sp, color = C.ink2)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(medLabel(r.title.m).uppercase(), fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = medColor(r.title.m))
                    if (r.followed) {
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Filled.PlayCircle, null, tint = C.accent, modifier = Modifier.size(14.dp))
                        Text(" VERFOLGT", fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = C.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(r.label.uppercase() + (rel?.let { "  ·  " + it.uppercase() } ?: ""), fontSize = 12.sp, letterSpacing = 1.sp, color = C.ink2, fontWeight = FontWeight.SemiBold)
                }
                Text(r.title.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.text, fontSize = 15.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FollowRow(t: Title, text: String, fr: FocusRequester?, highlight: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().let { if (fr != null) it.focusRequester(fr) else it },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = C.surface, focusedContainerColor = C.surface2, contentColor = C.ink, focusedContentColor = C.ink),
        border = ClickableSurfaceDefaults.border(
            border = if (highlight) Border(BorderStroke(2.dp, C.accent), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, C.ink), shape = RoundedCornerShape(14.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        androidx.compose.foundation.layout.Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(t, 56)
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text(t.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, fontSize = 15.sp, color = if (highlight) C.accent else C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Poster(t: Title, w: Int) {
    Box(Modifier.width(w.dp).height((w * 1.5).dp).clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(listOf(medColor(t.m), C.surface2)))) {
        if (t.poster != null) AsyncImage(model = t.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
