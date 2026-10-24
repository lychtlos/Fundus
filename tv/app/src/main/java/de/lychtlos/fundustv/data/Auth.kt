package de.lychtlos.fundustv.data

import android.content.Context
import android.content.SharedPreferences
import de.lychtlos.fundustv.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Anmeldung ohne Tastatur: Google-Gerätelogin (Code am Handy eingeben),
 * danach Firebase-Anmeldung per REST im Firebase-Projekt der Fundus-Web-App.
 * Ergebnis: dieselbe Nutzer-ID wie im Browser, also dieselben Fundus-Daten.
 */
object Auth {
    const val FB_API_KEY = "AIzaSyBKUL_iThk5jhZr7npM5HJjQ-FBopBnO7Q"
    const val FB_PROJECT = "wanderlust-8b3ba"

    data class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val interval: Int, val expiresAt: Long)
    data class User(val uid: String, val name: String, val email: String)

    private lateinit var prefs: SharedPreferences
    private val lock = Mutex()
    private var idToken: String? = null
    private var idTokenExp = 0L

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
    }

    val configured: Boolean get() = BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty() && BuildConfig.GOOGLE_CLIENT_SECRET.isNotEmpty()

    val user: User?
        get() {
            val uid = prefs.getString("uid", null) ?: return null
            if (prefs.getString("refresh", null) == null) return null
            return User(uid, prefs.getString("name", "") ?: "", prefs.getString("email", "") ?: "")
        }

    fun logout() {
        prefs.edit().clear().apply()
        idToken = null; idTokenExp = 0
    }

    suspend fun startDeviceLogin(): DeviceCode {
        val r = Http.postForm(
            "https://oauth2.googleapis.com/device/code",
            mapOf("client_id" to BuildConfig.GOOGLE_CLIENT_ID, "scope" to "openid email profile"),
        )
        return DeviceCode(
            userCode = r.getString("user_code"),
            url = r.optString("verification_url", "https://www.google.com/device"),
            deviceCode = r.getString("device_code"),
            interval = r.optInt("interval", 5).coerceAtLeast(3),
            expiresAt = System.currentTimeMillis() + r.optInt("expires_in", 1800) * 1000L,
        )
    }

    /** Wartet, bis der Code am Handy bestätigt wurde. Wirft bei Ablehnung oder Ablauf. */
    suspend fun awaitDeviceLogin(dc: DeviceCode): User {
        var wait = dc.interval * 1000L
        while (System.currentTimeMillis() < dc.expiresAt) {
            delay(wait)
            try {
                val r = Http.postForm(
                    "https://oauth2.googleapis.com/token",
                    mapOf(
                        "client_id" to BuildConfig.GOOGLE_CLIENT_ID,
                        "client_secret" to BuildConfig.GOOGLE_CLIENT_SECRET,
                        "device_code" to dc.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
                val googleIdToken = r.optString("id_token")
                if (googleIdToken.isEmpty()) throw Exception("Google hat kein ID-Token geliefert")
                return firebaseSignIn(googleIdToken)
            } catch (e: HttpError) {
                val err = runCatching { JSONObject(e.body).optString("error") }.getOrDefault("")
                when (err) {
                    "authorization_pending" -> {}
                    "slow_down" -> wait += 2000
                    "access_denied" -> throw Exception("Anmeldung am Handy abgelehnt")
                    "expired_token" -> throw Exception("Code abgelaufen")
                    else -> throw Exception("Google-Anmeldung: ${err.ifEmpty { e.message }}")
                }
            }
        }
        throw Exception("Code abgelaufen")
    }

    private suspend fun firebaseSignIn(googleIdToken: String): User {
        val body = JSONObject()
            .put("postBody", "id_token=$googleIdToken&providerId=google.com")
            .put("requestUri", "http://localhost")
            .put("returnSecureToken", true)
            .put("returnIdpCredential", true)
        val r = try {
            Http.postJson("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$FB_API_KEY", body)
        } catch (e: HttpError) {
            throw Exception("Firebase-Anmeldung fehlgeschlagen: " + firebaseMsg(e))
        }
        idToken = r.getString("idToken")
        idTokenExp = System.currentTimeMillis() + (r.optString("expiresIn", "3600").toLongOrNull() ?: 3600) * 1000 - 60_000
        prefs.edit()
            .putString("uid", r.getString("localId"))
            .putString("refresh", r.getString("refreshToken"))
            .putString("name", r.optString("displayName", r.optString("fullName", "")))
            .putString("email", r.optString("email", ""))
            .apply()
        return user!!
    }

    /** Gültiges Firebase-ID-Token (wird bei Bedarf erneuert). */
    suspend fun token(): String {
        return lock.withLock { tokenLocked() }
    }

    private suspend fun tokenLocked(): String {
        idToken?.let { if (System.currentTimeMillis() < idTokenExp) return it }
        val refresh = prefs.getString("refresh", null) ?: throw Exception("Nicht angemeldet")
        val r = try {
            Http.postForm(
                "https://securetoken.googleapis.com/v1/token?key=$FB_API_KEY",
                mapOf("grant_type" to "refresh_token", "refresh_token" to refresh),
            )
        } catch (e: HttpError) {
            if (e.code == 400) logout()
            throw Exception("Sitzung abgelaufen, bitte neu anmelden")
        }
        val t = r.getString("id_token")
        idToken = t
        idTokenExp = System.currentTimeMillis() + (r.optString("expires_in", "3600").toLongOrNull() ?: 3600) * 1000 - 60_000
        prefs.edit().putString("refresh", r.getString("refresh_token")).apply()
        return t
    }

    fun firebaseMsg(e: HttpError): String =
        runCatching { JSONObject(e.body).getJSONObject("error").optString("message") }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: "Fehler ${e.code}"
}
