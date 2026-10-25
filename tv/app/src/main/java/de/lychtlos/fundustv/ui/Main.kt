@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.Tab

/** Merker der einzelnen Seiten (Fokus, Scrollposition), bleiben beim Wechsel erhalten. */
class Memories {
    val discover = HomeMemory()
    val watch = HomeMemory()
    val search = SearchMemory()
}

/** Hauptbildschirm: Menü links (wie die Leiste der Smartphone-App), Inhalt rechts. */
@Composable
fun MainScreen(app: AppState, mem: Memories) {
    // Menü liegt über dem Inhalt: beim Aufklappen wird der Inhalt nicht neu angeordnet (flüssiger)
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Box(Modifier.fillMaxSize().padding(start = 84.dp)) {
            when (app.tab) {
                Tab.Discover -> DiscoverScreen(app, mem.discover)
                Tab.Search -> SearchScreen(app, mem.search)
                Tab.Watch -> WatchScreen(app, mem.watch)
                Tab.News -> NewsScreen(app)
                Tab.Follow -> FollowScreen(app)
                Tab.Settings -> SettingsScreen(app)
            }
        }
        Rail(app)
    }
}

private fun iconOf(t: Tab): ImageVector = when (t) {
    Tab.Discover -> Icons.Filled.Home
    Tab.Search -> Icons.Filled.Search
    Tab.Watch -> Icons.Filled.Bookmark
    Tab.News -> Icons.Filled.Notifications
    Tab.Follow -> Icons.Filled.PlayCircle
    Tab.Settings -> Icons.Filled.Settings
}

@Composable
private fun Rail(app: AppState) {
    var open by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (open) 250.dp else 84.dp, androidx.compose.animation.core.tween(160), label = "rail")
    Column(
        Modifier
            .width(w)
            .fillMaxHeight()
            .background(if (open) Brush.horizontalGradient(listOf(C.surface, C.surface, C.surface.copy(alpha = 0.92f))) else Brush.horizontalGradient(listOf(C.bg, C.bg)))
            .onFocusChanged { open = it.hasFocus }
            .padding(vertical = 28.dp, horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(48.dp).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (open) Brand(24) else Text("F.", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = C.accent)
        }
        Spacer(Modifier.height(10.dp))
        val main = listOf(Tab.Discover, Tab.Search, Tab.Watch, Tab.News, Tab.Follow)
        main.forEach { t -> RailItem(app, t, open, count(app, t)) }
        Spacer(Modifier.weight(1f))
        RailItem(app, Tab.Settings, open, null, label = app.user?.name?.ifEmpty { null } ?: Tab.Settings.label)
    }
}

private fun count(app: AppState, t: Tab): Int? = when (t) {
    Tab.Watch -> app.fundus.watchlist().size.takeIf { it > 0 }
    Tab.Follow -> app.fundus.followList().size.takeIf { it > 0 }
    else -> null
}

@Composable
private fun RailItem(app: AppState, t: Tab, open: Boolean, n: Int?, label: String = t.label) {
    val sel = app.tab == t
    Surface(
        onClick = { app.open(t) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (sel) C.accent.copy(alpha = 0.16f) else Color.Transparent,
            contentColor = if (sel) C.accent else C.ink2,
            focusedContainerColor = C.ink,
            focusedContentColor = C.bg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconOf(t), contentDescription = t.label, modifier = Modifier.size(24.dp))
            AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(16.dp))
                    Text(label, fontSize = 17.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    if (n != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(n.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
