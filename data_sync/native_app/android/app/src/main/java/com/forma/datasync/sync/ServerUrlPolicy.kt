package com.forma.datasync.sync

import java.net.URI
import java.util.Locale

/** Explicit LAN test capability; HTTPS builds never inherit the HTTP exception. */
object ServerUrlPolicy {
    private val lanHttpOrigin = Regex("^http://([0-9]+(?:\\.[0-9]+){3})(?::[0-9]+)?/?$", RegexOption.IGNORE_CASE)

    fun normalize(value: String, development: Boolean, allowLanHttp: Boolean): String {
        val input = value.trim()
        val uri = try { URI(input) } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid server URL, including https://.")
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")) {
            "Enter a server URL without a path, credentials, query parameters, or a fragment."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Enter a server port between 1 and 65535." }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val privateHttp = allowLanHttp && lanHttpOrigin.matchEntire(input)?.groupValues?.get(1)?.let {
            isCanonicalPrivateIpv4(it)
        } == true
        require(scheme == "https" || (scheme == "http" && (development || privateHttp))) {
            if (allowLanHttp) "HTTP requires a private LAN IPv4 address, such as http://192.168.1.10:8000. Otherwise use HTTPS."
            else "Use HTTPS for the server URL. HTTP is available in development builds only."
        }
        val port = uri.port.takeUnless { (scheme == "https" && it == 443) || (scheme == "http" && it == 80) } ?: -1
        return URI(scheme, null, uri.host.lowercase(Locale.ROOT), port, null, null, null).toASCIIString()
    }

    private fun isCanonicalPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
