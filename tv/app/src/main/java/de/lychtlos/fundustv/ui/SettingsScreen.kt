@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.BuildConfig
import de.lychtlos.fundustv.Screen
import de.lychtlos.fundustv.data.Auth
import de.lychtlos.fundustv.data.Launcher

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: AppState) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val mine = app.providers.toSet()
    // eigene Dienste zuerst, dann installierte, dann die wichtigsten übrigen
    val list = remember(app.allProviders, mine) {
        app.allProviders.sortedWith(
            compareByDescending<de.lychtlos.fundustv.data.Provider> { it.id in mine }
                .thenByDescending { Launcher.isInstalled(ctx, it) }
        ).take(40)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(C.bg),
        contentPadding = PaddingValues(56.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Einstellungen", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = C.ink) }

        item { Section("Konto") }
        item {
            val u = app.user
            Column {
                if (u != null) {
                    Text(u.name.ifEmpty { u.email }, color = C.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (u.name.isNotEmpty() && u.email.isNotEmpty()) Text(u.email, color = C.ink2, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { app.refresh(); app.say("Lädt neu") }, modifier = Modifier.focusRequester(first)) { Text("Synchronisieren") }
                        OutlinedButton(onClick = { app.logout() }) { Text("Abmelden") }
                    }
                } else {
                    Text("Nicht verbunden", color = C.ink2, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    if (Auth.configured) {
                        Button(onClick = { app.go(Screen.Login) }, modifier = Modifier.focusRequester(first)) { Text("Mit Google anmelden") }
                    } else {
                        Text(
                            "Anmeldung nicht eingerichtet (tv/README.md)",
                            color = C.mark, fontSize = 14.sp,
                        )
                    }
                }
            }
        }

        item { Section("Streamingdienste") }
        item {
            if (list.isEmpty()) Text("Lädt …", color = C.ink2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEachIndexed { i, p ->
                    val sel = p.id in mine
                    val check: (@Composable () -> Unit)? = if (sel) {
                        { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                    } else null
                    FilterChip(
                        selected = sel,
                        onClick = { app.toggleProvider(p.id) },
                        modifier = if (app.user == null && !Auth.configured && i == 0) Modifier.focusRequester(first) else Modifier,
                        leadingIcon = check,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.logo != null) {
                                AsyncImage(p.logo, null, Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(p.name + if (Launcher.isInstalled(ctx, p)) "  · installiert" else "", fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        item { Section("Helfer") }
        item {
            val on = remember(app.toast) { de.lychtlos.fundustv.data.Helper.enabled(ctx) }
            Column {
                Text(if (on) "Eingeschaltet" else "Aus", color = if (on) C.accent else C.ink2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Reicht den Titel nach der Profilauswahl nach, sucht ohne direkten Link in der App und trägt die Suche in TiviMate ein.", color = C.ink2, fontSize = 14.sp)
                if (!on) Text("Einschalten: Bedienungshilfen → Fundus-Helfer → An", color = C.ink2, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { de.lychtlos.fundustv.data.Helper.openSettings(ctx) }) { Text(if (on) "Bedienungshilfen öffnen" else "Helfer einschalten") }
            }
        }

        item { Section("Info") }
        item {
            Text(
                "Fundus TV ${BuildConfig.VERSION_NAME} · Daten: TMDB, JustWatch. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                color = C.ink2, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.accent, modifier = Modifier.padding(top = 14.dp))
}
