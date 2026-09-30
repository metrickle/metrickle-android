package com.metrickle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Key/value persistence. Called from the SDK's own thread, never the UI thread. */
public interface KeyValueStore {
    public fun get(key: String): String?
    public fun set(key: String, value: String)
    public fun remove(key: String)
}

/** `SharedPreferences("metrickle")`. Writes use `apply()` so they never block. */
internal class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("metrickle", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

/** In-memory store, for tests and previews. */
internal class MemoryStore : KeyValueStore {
    val data = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = data[key]
    override fun set(key: String, value: String) { data[key] = value }
    override fun remove(key: String) { data.remove(key) }
}

/** An HTTP response. `status` is 0 on a network error. */
public data class HttpResponse(val status: Int, val body: String? = null)

/** HTTP client used for batches, config and feedback. Replaceable in tests. */
public interface Transport {
    public suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

/** `HttpURLConnection` on `Dispatchers.IO`. No third-party HTTP dependency. */
internal object UrlConnectionTransport : Transport {
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    useCaches = false
                    for ((k, v) in headers) setRequestProperty(k, v)
                }
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }
                HttpResponse(status, text)
            } catch (_: IOException) {
                HttpResponse(0)
            } catch (_: RuntimeException) {
                HttpResponse(0)
            } finally {
                conn?.disconnect()
            }
        }
}
