package de.lychtlos.fundustv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HttpError(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(300)}")

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /* Der Firebase-Schlüssel der Web-App ist evtl. auf die Website beschränkt.
       Mit diesem Referer gelten Aufrufe der TV-App als Aufrufe von dort. */
    private const val REFERER = "https://lychtlos.github.io/"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun Request.Builder.common(url: String): Request.Builder {
        header("Accept", "application/json")
        if (url.contains("googleapis.com")) header("Referer", REFERER)
        return this
    }

    private suspend fun exec(req: Request): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw HttpError(r.code, body)
            body
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = Request.Builder().url(url).common(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return parse(exec(b.build()))
    }

    /** JSON im Hintergrund zerlegen, nicht auf dem Hauptthread (sonst ruckelt die Oberfläche) */
    private suspend fun parse(body: String): JSONObject = withContext(Dispatchers.Default) { JSONObject(body) }

    suspend fun postForm(url: String, form: Map<String, String>): JSONObject {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        return JSONObject(exec(Request.Builder().url(url).common(url).post(fb.build()).build()))
    }

    suspend fun postJson(url: String, json: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = Request.Builder().url(url).common(url).post(json.toString().toRequestBody(JSON_TYPE))
        headers.forEach { (k, v) -> b.header(k, v) }
        return JSONObject(exec(b.build()))
    }

    suspend fun patchJson(url: String, json: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = Request.Builder().url(url).common(url).patch(json.toString().toRequestBody(JSON_TYPE))
        headers.forEach { (k, v) -> b.header(k, v) }
        return JSONObject(exec(b.build()))
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
