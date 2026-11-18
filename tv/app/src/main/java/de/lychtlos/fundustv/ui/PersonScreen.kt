@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.Screen
import de.lychtlos.fundustv.Shelf
import de.lychtlos.fundustv.data.Tmdb

/** Personenseite: weitere Serien und Filme einer Schauspielerin bzw. eines Schauspielers, getrennt. */
@Composable
fun PersonScreen(app: AppState, p: Screen.Person) {
    val mem = remember(p.id) { HomeMemory() }
    var info by remember(p.id) { mutableStateOf("") }
    var groups by remember(p.id) { mutableStateOf<List<Group>?>(null) }
    var err by remember(p.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(p.id) {
        try {
            val (i, tv, mv) = Tmdb.person(p.id)
            info = i
            groups = listOfNotNull(
                tv.takeIf { it.isNotEmpty() }?.let { Group("serie", "Serien", listOf(Shelf("p-tv", "Mit ${p.name}", it, "serie", "${it.size} Serien"))) },
                mv.takeIf { it.isNotEmpty() }?.let { Group("film", "Filme", listOf(Shelf("p-mv", "Mit ${p.name}", it, "film", "${it.size} Filme"))) },
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { err = e.message ?: "Laden fehlgeschlagen" }
    }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Browse(
            app = app, mem = mem, groups = groups.orEmpty(),
            header = {
                Row(Modifier.padding(start = 8.dp, top = 14.dp, end = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(C.surface2)) {
                        if (p.photo != null) AsyncImage(p.photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(p.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                        if (info.isNotEmpty()) Text(info.replace("\n", "  ·  "), fontSize = 13.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            empty = err ?: if (groups == null) "Lädt …" else "Keine weiteren Titel gefunden.",
        )
    }
}
