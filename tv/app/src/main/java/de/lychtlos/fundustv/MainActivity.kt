package de.lychtlos.fundustv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import de.lychtlos.fundustv.data.Auth
import de.lychtlos.fundustv.ui.C
import de.lychtlos.fundustv.ui.DetailScreen
import de.lychtlos.fundustv.ui.FundusTheme
import de.lychtlos.fundustv.ui.LoginScreen
import de.lychtlos.fundustv.ui.Splash
import de.lychtlos.fundustv.ui.MainScreen
import de.lychtlos.fundustv.ui.Memories
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Auth.init(applicationContext)
        // Bilder weich einblenden
        de.lychtlos.fundustv.data.Tmdb.dir = java.io.File(cacheDir, "tmdb")
        // Bilder: großzügiger RAM-Speicher (30 %) und 300 MB auf dem Gerät, weich einblenden
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(applicationContext)
                .crossfade(220)
                .memoryCache { coil.memory.MemoryCache.Builder(applicationContext).maxSizePercent(0.30).build() }
                .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(300L * 1024 * 1024).build() }
                .respectCacheHeaders(false)
                .build()
        )
        app = AppState(applicationContext)
        val mems = Memories()

        // solange die App sichtbar ist: alle 90 s prüfen, ob sich das Fundus-Konto geändert hat
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    delay(90_000)
                    app.checkRemote()
                }
            }
        }

        setContent {
            FundusTheme {
                BackHandler(enabled = app.stack.size > 1) { app.back() }
                // Zurück auf einer Unterseite des Menüs führt zu „Entdecken“
                BackHandler(enabled = app.stack.size == 1 && app.tab != de.lychtlos.fundustv.Tab.Discover) { app.open(de.lychtlos.fundustv.Tab.Discover) }
                Box(Modifier.fillMaxSize().background(C.bg)) {
                    // weicher Übergang zwischen den Seiten
                    androidx.compose.animation.Crossfade(targetState = app.screen, animationSpec = androidx.compose.animation.core.tween(240), label = "screen") { s ->
                        when (s) {
                            Screen.Main -> MainScreen(app, mems)
                            Screen.Login -> LoginScreen(app)
                            is Screen.Detail -> androidx.compose.runtime.key(s.title.k, app.stack.size) { DetailScreen(app, s.title) }
                        }
                    }
                    var splash by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
                    if (splash) Splash(ready = app.ready) { splash = false }
                    val msg = app.toast
                    LaunchedEffect(msg) { if (msg != null) { delay(3500); app.toast = null } }
                    AnimatedVisibility(
                        visible = msg != null, enter = fadeIn(), exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
                    ) {
                        Text(
                            msg ?: "",
                            color = C.ink, fontSize = 16.sp,
                            modifier = Modifier
                                .background(C.surface2, RoundedCornerShape(10.dp))
                                .padding(horizontal = 22.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        de.lychtlos.fundustv.data.Helper.note?.let { app.say(it); de.lychtlos.fundustv.data.Helper.note = null }
        // erster Start oder lange Pause: alles neu laden; sonst nur prüfen, ob sich das Konto geändert hat
        if (System.currentTimeMillis() - app.lastFull > 30 * 60 * 1000L) app.refresh()
        else app.checkRemote()
    }
}
