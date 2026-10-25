package de.lychtlos.fundustv.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Aufträge an den Fundus-Helfer (Bedienungshilfe, siehe HelperService).
 * Liegen im selben Prozess, daher reicht ein gemeinsames Objekt.
 */
object Helper {
    /** Titel-Link nach der Profilauswahl noch einmal an die App schicken. */
    class Resend(val pkg: String, val intent: Intent, val at: Long = System.currentTimeMillis()) {
        @Volatile var sawProfile = false
    }

    /** Suchbegriff in eine App (TiviMate) eintragen. */
    class Search(val pkg: String, val query: String, val at: Long = System.currentTimeMillis()) {
        @Volatile var clicks = 0
        @Volatile var last = 0L
    }

    @Volatile var resend: Resend? = null
    @Volatile var search: Search? = null
    /** Rückmeldung des Helfers für die Oberfläche (z. B. „Suche eingetragen“) */
    @Volatile var note: String? = null

    const val TIVIMATE = "ar.tvplayer.tv"

    fun enabled(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx.packageName, "de.lychtlos.fundustv.HelperService")
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun openSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
