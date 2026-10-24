@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.Screen
import de.lychtlos.fundustv.data.Title
import de.lychtlos.fundustv.data.Tmdb
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(app: AppState, mem: SearchMemory) {
    var q by rememberSaveable { mutableStateOf(mem.query) }
    var results by remember { mutableStateOf(mem.results) }
    var busy by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val fm = LocalFocusManager.current

    LaunchedEffect(Unit) { if (mem.results.isEmpty()) runCatching { field.requestFocus() } }
    LaunchedEffect(q) {
        mem.query = q
        if (q.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(450)
        busy = true
        results = runCatching { Tmdb.search(q.trim()) }.getOrDefault(emptyList())
        mem.results = results
        busy = false
    }

    Column(Modifier.fillMaxSize().background(C.bg).padding(top = 32.dp)) {
        Text("Suchen", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, modifier = Modifier.padding(start = 56.dp, bottom = 14.dp))
        Box(
            Modifier
                .padding(horizontal = 56.dp)
                .fillMaxWidth(0.6f)
                .border(2.dp, if (focused) C.accent else C.line, RoundedCornerShape(10.dp))
                .background(C.surface, RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            if (q.isEmpty()) Text("Film oder Serie …", color = C.ink2, fontSize = 20.sp)
            BasicTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                textStyle = TextStyle(color = C.ink, fontSize = 20.sp),
                cursorBrush = SolidColor(C.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { fm.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { focused = it.isFocused },
            )
        }
        when {
            busy && results.isEmpty() -> Message("Suche …")
            q.trim().length >= 2 && results.isEmpty() && !busy -> Message("Nichts gefunden.")
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 24.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(results, key = { it.k }) { t ->
                PosterCard(t, onClick = { app.go(Screen.Detail(t)) }, badge = if (t.m == "serie") "Serie" else null)
            }
        }
    }
}

class SearchMemory {
    var query: String = ""
    var results: List<Title> = emptyList()
}
