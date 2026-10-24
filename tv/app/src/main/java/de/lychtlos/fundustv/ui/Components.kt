@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import de.lychtlos.fundustv.Shelf
import de.lychtlos.fundustv.data.Title

object C {
    val bg = Color(0xFF0E1412)
    val surface = Color(0xFF16201C)
    val surface2 = Color(0xFF1F2B27)
    val ink = Color(0xFFE4EBE8)
    val ink2 = Color(0xFFA1B0AA)
    val line = Color(0xFF2C3A35)
    val accent = Color(0xFF4EC1A7)
    val accentInk = Color(0xFF05201A)
    val mark = Color(0xFFE6AE45)
    val bad = Color(0xFFEE7B6C)
    val film = Color(0xFF7F9CE0)
    val serie = Color(0xFFB98ADB)
}

@Composable
fun FundusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = C.accentInk,
            secondary = C.mark,
            background = C.bg,
            onBackground = C.ink,
            surface = C.surface,
            onSurface = C.ink,
            surfaceVariant = C.surface2,
            onSurfaceVariant = C.ink2,
            border = C.line,
            error = C.bad,
        ),
        content = content,
    )
}

/** Poster-Kachel. Fokus vergrößert die Kachel und zeigt einen hellen Rahmen. */
@Composable
fun PosterCard(
    t: Title,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    badge: String? = null,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            border = CardDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, C.ink), shape = RoundedCornerShape(10.dp)),
            ),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            colors = CardDefaults.colors(containerColor = C.surface2),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (t.poster != null) {
                    AsyncImage(
                        model = t.poster,
                        contentDescription = t.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(if (t.m == "film") C.film else C.serie, C.surface2)))
                            .padding(10.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(t.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 4)
                    }
                }
                if (badge != null) {
                    Text(
                        badge,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = C.accentInk,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(C.accent)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            t.title,
            fontSize = 13.sp,
            color = C.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ShelfRow(
    shelf: Shelf,
    state: LazyListState,
    onOpen: (Title) -> Unit,
    onFocused: (Title) -> Unit,
    restoreKey: String?,
    firstFocus: FocusRequester?,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            shelf.label,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = C.ink,
            modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
        )
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(shelf.items, key = { _, t -> t.k }) { i, t ->
                val key = shelf.id + "|" + t.k
                val fr = remember(key) { FocusRequester() }
                val req = when {
                    restoreKey == key -> fr
                    restoreKey == null && i == 0 && firstFocus != null -> firstFocus
                    else -> null
                }
                if (restoreKey == key) {
                    LaunchedEffect(key) { runCatching { fr.requestFocus() } }
                }
                PosterCard(
                    t = t,
                    onClick = { onOpen(t) },
                    focusRequester = req,
                    onFocused = { onFocused(t) },
                    badge = if (shelf.id == "tips") "✦" else null,
                )
            }
        }
    }
}

@Composable
fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = C.ink2, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}
