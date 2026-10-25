package de.lychtlos.fundustv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Markenzeichen wie in der Web-App: „Fundus“ mit farbigem Punkt. */
@Composable
fun Brand(size: Int = 26) {
    Row {
        Text("Fundus", fontSize = size.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = C.ink, letterSpacing = (-0.04 * size).sp)
        Text(".", fontSize = size.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = C.accent)
    }
}

private val RING = listOf(
    Icons.Filled.Movie to Color(0xFF7F9CE0),
    Icons.Filled.Tv to Color(0xFFB98ADB),
    Icons.Filled.MusicNote to Color(0xFFE58A74),
    Icons.AutoMirrored.Filled.MenuBook to Color(0xFFD2AE4E),
    Icons.Filled.Mic to Color(0xFF5CC4CC),
    Icons.Filled.SportsEsports to Color(0xFF9BCB5E),
)

/**
 * Startbildschirm wie in der Web-App: sechs Medien-Symbole im Kreis um das Logo,
 * langsam drehend, darunter ein Ladebalken. Verschwindet weich, sobald [ready] und mindestens 1,4 s vergangen sind.
 */
@Composable
fun Splash(ready: Boolean, onGone: () -> Unit) {
    val fade = remember { Animatable(1f) }
    val pops = remember { RING.map { Animatable(0f) } }
    val logo = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        pops.forEachIndexed { i, a -> launch { delay(80L * i); a.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) } }
        launch { delay(550); logo.animateTo(1f, tween(600)) }
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        delay(1400)
        fade.animateTo(0f, tween(450))
        onGone()
    }
    // Sicherheitsnetz: nie länger als 4 s
    LaunchedEffect(Unit) { delay(4000); if (fade.value > 0f) { fade.animateTo(0f, tween(450)); onGone() } }

    val inf = rememberInfiniteTransition(label = "splash")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "spin")
    val bar by inf.animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "bar")

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Brush.radialGradient(listOf(Color(0xFF16302A), C.bg), radius = 1400f)),
        contentAlignment = Alignment.Center,
    ) {
        val r = 150f
        RING.forEachIndexed { i, (icon, col) ->
            val a = Math.toRadians((i * 60.0) + spin - 90.0)
            val p = pops[i].value
            Box(
                Modifier
                    .offset { IntOffset((cos(a) * r * density).roundToInt(), (sin(a) * r * density).roundToInt()) }
                    .graphicsLayer { scaleX = p; scaleY = p; alpha = p }
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(col.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = col, modifier = Modifier.size(32.dp))
            }
        }
        Box(Modifier.graphicsLayer { alpha = logo.value; translationY = (1f - logo.value) * 16f }) { Brand(46) }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).width(96.dp).height(4.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier.width(38.dp).height(4.dp)
                    .graphicsLayer { translationX = bar * 96f * density }
                    .clip(RoundedCornerShape(2.dp)).background(C.accent),
            )
        }
    }
}
