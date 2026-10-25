package de.lychtlos.fundustv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import de.lychtlos.fundustv.data.Helper

/**
 * Fundus-Helfer (Bedienungshilfe, muss in den Einstellungen eingeschaltet werden):
 * 1. Öffnet Fundus einen Titel in einer Streaming-App und zeigt diese zuerst die Profilauswahl,
 *    wird der Titel-Link nach der Auswahl noch einmal geschickt.
 * 2. Startet Fundus TiviMate mit einem Suchbegriff, sucht der Helfer dort das Suchfeld und trägt den Begriff ein.
 * Arbeitet nur, solange ein Auftrag von Fundus vorliegt (höchstens 2 Minuten bzw. 40 Sekunden).
 */
class HelperService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastScan = 0L

    private val PROFILE = Regex("(?i)(wer schaut|who.?s watching|profil (wählen|auswählen)|profile (wählen|auswählen)|choose (a |your )?profile|select (a |your )?profile|welches profil)")
    private val SEARCH = Regex("(?i)^(suche|suchen|search)$")

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        Helper.resend?.let { r ->
            if (now - r.at > 120_000) Helper.resend = null
            else if (pkg == r.pkg) resend(r, now)
        }
        Helper.search?.let { s ->
            if (now - s.at > 40_000) { Helper.search = null; Helper.note = "Suchfeld nicht gefunden"; Helper.log("Abbruch: kein Suchfeld nach 40 s") }
            else if (pkg == s.pkg) search(s, now)
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Helper.log("Helfer verbunden")
        de.lychtlos.fundustv.data.HelperServiceBridge.starter = { main.post { begin() } }
    }

    override fun onDestroy() {
        de.lychtlos.fundustv.data.HelperServiceBridge.starter = null
        super.onDestroy()
    }

    /** Auftrag sofort zeitgesteuert abarbeiten (manche Apps senden kaum Ereignisse) */
    private fun begin() {
        val s = Helper.search ?: return
        if (s.pkg == Helper.TIVIMATE) navigateTiviMate(s)
        else listOf(2500L, 4000L, 5500L, 7500L, 10000L).forEach { d -> main.postDelayed({ if (Helper.search === s) { s.last = 0; search(s, System.currentTimeMillis()) } }, d) }
    }

    private fun resend(r: Helper.Resend, now: Long) {
        if (now - lastScan < 350) return
        lastScan = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != r.pkg) return
        val text = collect(root)
        if (PROFILE.containsMatchIn(text)) { if (!r.sawProfile) Helper.log("Profilauswahl erkannt"); r.sawProfile = true; return }
        if (r.sawProfile) {
            Helper.resend = null
            Helper.log("Profil gewählt → Titel nachgereicht")
            // kurz warten, bis die App nach der Auswahl bereit ist
            main.postDelayed({ runCatching { startActivity(Intent(r.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, 1500)
        }
    }

    /**
     * TiviMate: Seitenmenü links öffnen, ganz nach oben (Suche), auswählen.
     * Tastendrücke per Bedienungshilfe gibt es ab Android 13 (Google TV Streamer: Android 14).
     */
    private fun navigateTiviMate(s: Helper.Search) {
        if (s.navPlanned) return
        if (Build.VERSION.SDK_INT < 33) { Helper.log("Android zu alt für Tastendrücke (ab 13)"); return }
        s.navPlanned = true
        fun key(k: Int, at: Long, label: String? = null) = main.postDelayed({
            if (Helper.search === s) {
                val ok = performGlobalAction(k)
                if (label != null) Helper.log(label + if (ok) "" else " FEHLGESCHLAGEN")
            }
        }, at)
        // TiviMate startet in der Senderliste: zweimal links bis ins Seitenmenü
        key(GLOBAL_ACTION_DPAD_LEFT, 3500L, "Taste links")
        key(GLOBAL_ACTION_DPAD_LEFT, 3900L, "Taste links")
        main.postDelayed({
            if (Helper.search !== s) return@postDelayed
            // „Suchen“ direkt antippen; klappt das nicht: ganz nach oben und OK
            val root = rootInActiveWindow
            val item = root?.let { r -> find(r) { n -> SEARCH.matches((n.text ?: n.contentDescription ?: "").toString().trim()) } }
            var c: AccessibilityNodeInfo? = item
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Helper.log("„Suchen“ im Menü gewählt")
            } else {
                Helper.log("„Suchen“ nicht direkt gefunden → nach oben + OK")
                repeat(8) { i -> key(GLOBAL_ACTION_DPAD_UP, 200L + i * 180L) }
                key(GLOBAL_ACTION_DPAD_CENTER, 200L + 8 * 180L + 250L, "Taste OK")
            }
        }, 4500L)
        // danach Suchfeld suchen, auch wenn die App keine Ereignisse schickt
        listOf(6500L, 7500L, 8800L, 10500L, 13000L).forEach { d ->
            main.postDelayed({ if (Helper.search === s) { s.last = 0; search(s, System.currentTimeMillis()) } }, d)
        }
    }

    private fun search(s: Helper.Search, now: Long) {
        if (now - s.last < 700) return
        s.last = now
        if (s.pkg == Helper.TIVIMATE) navigateTiviMate(s)
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != s.pkg) return
        val edit = find(root) { it.isEditable }
        if (edit != null) {
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s.query) }
            val ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
            if (ok && Build.VERSION.SDK_INT >= 30) edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            Helper.search = null
            Helper.log(if (ok) "Suchfeld gefunden, „${s.query}“ eingetragen" else "Suchfeld gefunden, Eintragen fehlgeschlagen")
            Helper.note = if (ok) "„${s.query}“ eingetragen" else "Suchfeld gefunden, Eintragen fehlgeschlagen"
            return
        }
        // TiviMate wird über das Seitenmenü angesteuert; bei anderen Apps nach „Suche“ suchen
        if (s.pkg == Helper.TIVIMATE && Build.VERSION.SDK_INT >= 33) return
        if (s.clicks >= 5) return
        val btn = find(root) { n ->
            val l = (n.text ?: n.contentDescription ?: "").toString().trim()
            SEARCH.matches(l) || (n.viewIdResourceName?.contains("search", true) == true && n.isVisibleToUser)
        } ?: return
        var c: AccessibilityNodeInfo? = btn
        while (c != null && !c.isClickable) c = c.parent
        val ok = (c ?: btn).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Helper.log("„Suche“ angetippt" + if (ok) "" else " (fehlgeschlagen)")
        s.clicks++
    }

    /** sichtbare Texte und Beschreibungen sammeln (begrenzt) */
    private fun collect(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder(); var n = 0
        fun walk(x: AccessibilityNodeInfo?, d: Int) {
            if (x == null || d > 25 || n > 400) return
            n++
            x.text?.let { sb.append(it).append(' ') }
            x.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until x.childCount) walk(x.getChild(i), d + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun find(x: AccessibilityNodeInfo?, d: Int = 0, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (x == null || d > 30) return null
        if (pred(x)) return x
        for (i in 0 until x.childCount) find(x.getChild(i), d + 1, pred)?.let { return it }
        return null
    }
}
