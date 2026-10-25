package de.lychtlos.fundustv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Hält Fundus im Speicher, während eine Streaming-App läuft (Google TV beendet Hintergrund-Apps sonst schnell).
 * Läuft als Vordergrunddienst ohne sichtbare Wirkung und beendet sich nach 3 Stunden ohne Nutzung selbst.
 */
class KeepAlive : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val stop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Fundus bereit halten", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CH).setContentTitle("Fundus").setContentText("Bleibt für die Rückkehr bereit").setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        h.removeCallbacks(stop)
        h.postDelayed(stop, 3 * 3600_000L)
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "keepalive"
        /** muss aufgerufen werden, solange Fundus sichtbar ist (Android erlaubt den Start nur dann) */
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, KeepAlive::class.java)) }
        }
    }
}
