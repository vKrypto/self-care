package com.forma.datasync.sync

import com.forma.datasync.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ApiException(val status: Int, message: String) : Exception(message)

/** No redirects: bearer tokens and credentials must stay on the configured server. */
class NativeApi(private val serverUrl: String, private val token: String? = null) {
    suspend fun request(path: String, payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(serverUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (payload == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw ApiException(code, when (code) {
                401 -> "Session expired. Sign in again to resume syncing."
                403 -> "The server denied this request. Check your account permissions."
                413 -> "This data batch is larger than the server accepts."
                429 -> "The server is busy. Sync will retry later."
                in 300..399 -> "The server redirected this request. Check the server URL."
                in 500..599 -> "The server is unavailable. Sync will retry later."
                else -> "The server rejected the request (HTTP $code)."
            })
            // Limit response bodies; collection records are uploaded, not downloaded.
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    text.append(buffer, 0, count)
                    check(text.length <= 1_048_576) { "The server response is too large." }
                }
                text.toString()
            }
            if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun normalizeServerUrl(value: String): String {
            val uri = try { URI(value.trim()) } catch (_: Exception) {
                throw IllegalArgumentException("Enter a valid server URL, including https://.")
            }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
                "Enter a server URL without a path, credentials, query parameters, or a fragment."
            }
            require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
                "Use HTTPS for the server URL. HTTP is available in debug builds only."
            }
            return uri.toASCIIString().trimEnd('/')
        }
    }
}
