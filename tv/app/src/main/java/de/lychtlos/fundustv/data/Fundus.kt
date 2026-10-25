package de.lychtlos.fundustv.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lesen und Schreiben des Fundus-Kontos über die Firestore-REST-API.
 * Dokument: artifacts/fundus/users/{uid}. Geschrieben werden nur einzelne Felder
 * (items.<k>, del.<k>, settings.providers), mit Zeitstempel ts wie in der Web-App,
 * damit deren Zusammenführung („neuester gewinnt“) greift.
 */
object Fundus {
    private const val WRITER = "tv"
    private fun docUrl(uid: String) =
        "https://firestore.googleapis.com/v1/projects/${Auth.FB_PROJECT}/databases/(default)/documents/artifacts/fundus/users/$uid"

    @Volatile var data: FundusData = FundusData()
        private set

    /** Änderungszeit des Dokuments beim letzten Laden bzw. eigenen Schreiben. */
    @Volatile private var updateTime: String? = null

    /**
     * Leichte Prüfung: lädt nur ein winziges Feld und vergleicht die Änderungszeit des Dokuments.
     * @return true, wenn sich das Konto seit dem letzten Laden geändert hat.
     */
    suspend fun changedRemotely(): Boolean {
        val u = Auth.user ?: return false
        val url = Uri.parse(docUrl(u.uid)).buildUpon().appendQueryParameter("mask.fieldPaths", "writer").build().toString()
        val doc = try {
            Http.get(url, mapOf("Authorization" to "Bearer ${Auth.token()}"))
        } catch (e: HttpError) {
            if (e.code == 404) return false
            throw Exception("Fundus-Daten: " + Auth.firebaseMsg(e))
        }
        val t = doc.optString("updateTime")
        return t.isNotEmpty() && t != updateTime
    }

    suspend fun load(): FundusData {
        val u = Auth.user ?: return FundusData().also { data = it }
        val doc = try {
            Http.get(docUrl(u.uid), mapOf("Authorization" to "Bearer ${Auth.token()}"))
        } catch (e: HttpError) {
            if (e.code == 404) return FundusData().also { data = it }
            throw Exception("Fundus-Daten: " + Auth.firebaseMsg(e))
        }
        updateTime = doc.optString("updateTime").ifEmpty { null }
        @Suppress("UNCHECKED_CAST")
        val f = decodeFields(doc.optJSONObject("fields"))
        val items = (f["items"] as? Map<String, Any?>).orEmpty()
            .mapNotNull { (k, v) -> (v as? Map<String, Any?>)?.let { k to it } }.toMap()
        val settings = (f["settings"] as? Map<String, Any?>).orEmpty()
        val ai = (f["ai"] as? Map<String, Any?>).orEmpty()
        val tips = (ai["tips"] as? List<*>).orEmpty().mapNotNull { t ->
            val m = (t as? Map<String, Any?>) ?: return@mapNotNull null
            if (m["m"] != "film" && m["m"] != "serie") return@mapNotNull null
            if (items.containsKey(m["k"] as? String ?: "")) return@mapNotNull null
            FundusData.itemToTitle(m)
        }
        val order = (ai["order"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) ->
            val o = v as? Map<String, Any?> ?: return@mapNotNull null
            k to (((o["r"] as? Number)?.toInt() ?: 999) to (o["why"] as? String))
        }.toMap()
        data = FundusData(
            items = items,
            providers = (settings["providers"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() },
            includeFree = settings["includeFree"] as? Boolean ?: true,
            tmdbKey = settings["tmdbKey"] as? String ?: "",
            tips = tips,
            order = order,
        )
        return data
    }

    /** Status setzen wie setStatus() in Fundus: "watch", "like", "love", "dislike", "hide" oder null (entfernen). */
    suspend fun setStatus(t: Title, status: String?) {
        val u = Auth.user ?: throw Exception("Nicht angemeldet")
        val now = System.currentTimeMillis()
        val prev = data.items[t.k]
        val fol = prev?.get("fol") == true
        val remove = status == null && !fol
        val item = LinkedHashMap<String, Any?>()
        if (!remove) {
            prev?.let { item.putAll(it) }
            item["k"] = t.k; item["m"] = t.m; item["id"] = t.id; item["title"] = t.title
            if (t.year.isNotEmpty()) { item["year"] = t.year; item["sub"] = t.year }
            t.poster?.let { item["img"] = it.replace("/w500/", "/w342/") }
            if (t.genres.isNotEmpty()) item["genres"] = t.genres
            t.rating?.let { item["rating"] = it }
            item["link"] = "https://www.themoviedb.org/${t.type}/${t.id}"
            if (status != null) item["status"] = status else item.remove("status")
            item["ts"] = now
        }
        val fields = JSONObject()
            .put("writer", encode(WRITER))
            .put("updatedAt", encode(now))
        val itemsF = JSONObject()
        if (!remove) itemsF.put(t.k, encode(item))
        fields.put("items", JSONObject().put("mapValue", JSONObject().put("fields", itemsF)))
        val delF = JSONObject()
        if (remove) delF.put(t.k, encode(now))
        fields.put("del", JSONObject().put("mapValue", JSONObject().put("fields", delF)))
        patch(u.uid, fields, listOf("writer", "updatedAt", "items.${q(t.k)}", "del.${q(t.k)}"))
        // lokal nachziehen
        val items = data.items.toMutableMap()
        if (remove) items.remove(t.k) else items[t.k] = item
        data = data.copy(items = items, tips = data.tips.filter { it.k != t.k })
    }

    /** Verfolgen (Flag fol) unabhängig vom Status, wie setFol() in Fundus. */
    suspend fun setFol(t: Title, on: Boolean) {
        val u = Auth.user ?: throw Exception("Nicht angemeldet")
        val now = System.currentTimeMillis()
        val prev = data.items[t.k]
        val remove = !on && prev?.get("status") == null
        val item = LinkedHashMap<String, Any?>()
        if (!remove) {
            prev?.let { item.putAll(it) }
            item["k"] = t.k; item["m"] = t.m; item["id"] = t.id; item["title"] = t.title
            if (t.year.isNotEmpty() && item["year"] == null) { item["year"] = t.year; item["sub"] = t.year }
            if (item["img"] == null) t.poster?.let { item["img"] = it.replace("/w500/", "/w342/") }
            if (on) item["fol"] = true else item.remove("fol")
            item["ts"] = now
        }
        val fields = JSONObject().put("writer", encode(WRITER)).put("updatedAt", encode(now))
        val itemsF = JSONObject(); if (!remove) itemsF.put(t.k, encode(item))
        fields.put("items", JSONObject().put("mapValue", JSONObject().put("fields", itemsF)))
        val delF = JSONObject(); if (remove) delF.put(t.k, encode(now))
        fields.put("del", JSONObject().put("mapValue", JSONObject().put("fields", delF)))
        patch(u.uid, fields, listOf("writer", "updatedAt", "items.${q(t.k)}", "del.${q(t.k)}"))
        val items = data.items.toMutableMap()
        if (remove) items.remove(t.k) else items[t.k] = item
        data = data.copy(items = items)
    }

    suspend fun setProviders(ids: List<Int>) {
        val u = Auth.user ?: throw Exception("Nicht angemeldet")
        val now = System.currentTimeMillis()
        val fields = JSONObject()
            .put("writer", encode(WRITER))
            .put("updatedAt", encode(now))
            .put("metaTs", encode(now))
            .put("settings", JSONObject().put("mapValue", JSONObject().put("fields", JSONObject().put("providers", encode(ids)))))
        patch(u.uid, fields, listOf("writer", "updatedAt", "metaTs", "settings.providers"))
        data = data.copy(providers = ids)
    }

    private suspend fun patch(uid: String, fields: JSONObject, mask: List<String>) {
        val b = Uri.parse(docUrl(uid)).buildUpon()
        mask.forEach { b.appendQueryParameter("updateMask.fieldPaths", it) }
        try {
            val r = Http.patchJson(b.build().toString(), JSONObject().put("fields", fields), mapOf("Authorization" to "Bearer ${Auth.token()}"))
            // eigene Änderung nicht als fremde erkennen
            r.optString("updateTime").ifEmpty { null }?.let { updateTime = it }
        } catch (e: HttpError) {
            throw Exception("Speichern fehlgeschlagen: " + Auth.firebaseMsg(e))
        }
    }

    /** Feldnamen mit Sonderzeichen (z. B. "film:123") müssen in Backticks stehen. */
    private fun q(name: String) = "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`"

    /* ---------- Firestore-Werte ⇄ Kotlin ---------- */
    fun decodeFields(f: JSONObject?): Map<String, Any?> {
        if (f == null) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        f.keys().forEach { k -> out[k] = decode(f.optJSONObject(k)) }
        return out
    }

    private fun decode(v: JSONObject?): Any? {
        if (v == null) return null
        return when {
            v.has("stringValue") -> v.getString("stringValue")
            v.has("integerValue") -> v.getString("integerValue").toLongOrNull()
            v.has("doubleValue") -> v.getDouble("doubleValue")
            v.has("booleanValue") -> v.getBoolean("booleanValue")
            v.has("timestampValue") -> v.getString("timestampValue")
            v.has("mapValue") -> decodeFields(v.getJSONObject("mapValue").optJSONObject("fields"))
            v.has("arrayValue") -> v.getJSONObject("arrayValue").optJSONArray("values")?.let { a ->
                (0 until a.length()).map { decode(a.optJSONObject(it)) }
            } ?: emptyList<Any?>()
            else -> null
        }
    }

    private fun encode(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("nullValue", JSONObject.NULL)
        is String -> JSONObject().put("stringValue", v)
        is Boolean -> JSONObject().put("booleanValue", v)
        is Int, is Long -> JSONObject().put("integerValue", v.toString())
        is Double -> if (v % 1.0 == 0.0 && Math.abs(v) < 1e15) JSONObject().put("integerValue", v.toLong().toString()) else JSONObject().put("doubleValue", v)
        is Float -> JSONObject().put("doubleValue", v.toDouble())
        is Number -> JSONObject().put("doubleValue", v.toDouble())
        is Map<*, *> -> {
            val f = JSONObject()
            v.forEach { (k, x) -> f.put(k.toString(), encode(x)) }
            JSONObject().put("mapValue", JSONObject().put("fields", f))
        }
        is List<*> -> {
            val a = JSONArray()
            v.forEach { a.put(encode(it)) }
            JSONObject().put("arrayValue", JSONObject().put("values", a))
        }
        else -> JSONObject().put("stringValue", v.toString())
    }
}
