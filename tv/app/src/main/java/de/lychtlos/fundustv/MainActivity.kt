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
import androidx.tv.material3.Text
import de.lychtlos.fundustv.data.Auth
import de.lychtlos.fundustv.ui.C
import de.lychtlos.fundustv.ui.DetailScreen
import de.lychtlos.fundustv.ui.FundusTheme
import de.lychtlos.fundustv.ui.HomeMemory
import de.lychtlos.fundustv.ui.HomeScreen
import de.lychtlos.fundustv.ui.LoginScreen
import de.lychtlos.fundustv.ui.SearchMemory
import de.lychtlos.fundustv.ui.SearchScreen
import de.lychtlos.fundustv.ui.SettingsScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState
    private var lastLoad = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Auth.init(applicationContext)
        app = AppState(applicationContext)
        val homeMem = HomeMemory()
        val searchMem = SearchMemory()

        setContent {
            FundusTheme {
                BackHandler(enabled = app.stack.size > 1) { app.back() }
                Box(Modifier.fillMaxSize().background(C.bg)) {
                    when (val s = app.screen) {
                        Screen.Home -> HomeScreen(app, homeMem)
                        Screen.Search -> SearchScreen(app, searchMem)
                        Screen.Settings -> SettingsScreen(app)
                        Screen.Login -> LoginScreen(app)
                        is Screen.Detail -> androidx.compose.runtime.key(s.title.k, app.stack.size) { DetailScreen(app, s.title) }
                    }
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
        // nach Rückkehr aus Netflix & Co. oder nach längerer Pause neu laden (Änderungen aus der Web-App)
        if (System.currentTimeMillis() - lastLoad > 10 * 60 * 1000L) {
            lastLoad = System.currentTimeMillis()
            app.refresh()
        }
    }
}
