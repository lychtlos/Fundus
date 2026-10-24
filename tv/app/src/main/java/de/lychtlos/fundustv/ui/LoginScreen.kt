@file:OptIn(ExperimentalTvMaterial3Api::class)

package de.lychtlos.fundustv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import de.lychtlos.fundustv.AppState
import de.lychtlos.fundustv.data.Auth

@Composable
fun LoginScreen(app: AppState) {
    var code by remember { mutableStateOf<Auth.DeviceCode?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val retry = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        err = null; code = null
        try {
            val dc = Auth.startDeviceLogin()
            code = dc
            app.user = Auth.awaitDeviceLogin(dc)
            app.say("Mit Fundus verbunden")
            app.refresh()
            app.home()
        } catch (e: Exception) {
            err = e.message ?: "Anmeldung fehlgeschlagen"
        }
    }
    LaunchedEffect(err) { if (err != null) runCatching { retry.requestFocus() } }

    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            val c = code
            if (c != null) {
                val qr = remember(c.url) { qrBitmap(c.url) }
                Image(qr, contentDescription = "QR-Code", modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)))
            }
            Column(Modifier.width(560.dp)) {
                Text("Mit Fundus verbinden", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                Spacer(Modifier.height(16.dp))
                when {
                    err != null -> {
                        Text(err!!, color = C.bad, fontSize = 17.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { attempt++ }, modifier = Modifier.focusRequester(retry)) { Text("Erneut versuchen") }
                    }
                    c == null -> Text("Code wird angefordert …", color = C.ink2, fontSize = 17.sp)
                    else -> {
                        Text("1. Am Handy den QR-Code scannen oder ${c.url.removePrefix("https://")} öffnen.", color = C.ink, fontSize = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("2. Diesen Code eingeben:", color = C.ink, fontSize = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            c.userCode, fontSize = 46.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = C.accent,
                            modifier = Modifier.background(C.surface, RoundedCornerShape(10.dp)).padding(horizontal = 22.dp, vertical = 8.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("3. Mit demselben Google-Konto anmelden wie in Fundus.", color = C.ink, fontSize = 18.sp)
                        Spacer(Modifier.height(18.dp))
                        Text("Wartet auf Bestätigung …", color = C.ink2, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int = 520): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
