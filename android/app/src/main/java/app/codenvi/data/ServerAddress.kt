package app.codenvi.data

import java.net.URI

/** Cleans up the host name typed into the Connect screen. Mirrors windows/src/Codenvi.Desktop/Core/ServerAddress.cs. */
object ServerAddress {
    /**
     * Accepts `codenvi.example.com`, `codenvi.example.com:8443` and `https://codenvi.example.com/` and returns
     * `host[:port]`, or null when the text is not a usable host name.
     */
    fun normalize(input: String?): String? {
        var s = (input ?: "").trim()
        if (s.startsWith("https://", ignoreCase = true)) s = s.substring(8)
        else if (s.startsWith("http://", ignoreCase = true)) return null // the apps only speak HTTPS
        s = s.trimEnd('/')
        if (s.isEmpty() || s.any { it == '/' || it == ' ' || it == '@' || it == '?' || it == '#' }) return null
        val uri = runCatching { URI("https://$s") }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains('.') && host != "localhost") return null
        return if (uri.port == -1) host else "$host:${uri.port}"
    }
}
