package app.codenvi.data

import app.codenvi.crypto.Base32
import app.codenvi.crypto.DeviceKey
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * codenvi:// strings (Project.md §4.1–4.2), compatible with shared/Codenvi.Contracts/ConnectionString.cs:
 *   codenvi://host[:port]/pair?c=CODE[&dev=ID&kv=N&k=KEY][&dn=NAME][&pin=PIN]
 *   codenvi://key?dev=ID&kv=N&k=KEY[&dn=NAME]
 */
data class ConnectionString(
    val host: String? = null,
    val pairingCode: String? = null,
    val pin: String? = null,
    val deviceId: String? = null,
    val keyVersion: Int? = null,
    val key: ByteArray? = null,
    val deviceName: String? = null,
) {
    val isKeyOnly get() = pairingCode == null && key != null
    val carriesKey get() = key != null && deviceId != null && keyVersion != null

    override fun toString(): String {
        val parts = mutableListOf<String>()
        pairingCode?.let { parts += "c=$it" }
        deviceId?.let { parts += "dev=$it" }
        keyVersion?.let { parts += "kv=$it" }
        key?.let { parts += "k=" + DeviceKey.formatCompact(it) }
        deviceName?.let { parts += "dn=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        pin?.let { parts += "pin=$it" }
        val q = parts.joinToString("&")
        return if (pairingCode == null) "codenvi://key?$q" else "codenvi://$host/pair?$q"
    }

    companion object {
        fun parse(text: String): ConnectionString {
            val trimmed = text.trim()
            require(trimmed.startsWith("codenvi://", ignoreCase = true)) { "Not a codenvi:// connection string." }
            val uri = URI(trimmed)
            val query = (uri.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
                val i = it.indexOf('=')
                if (i < 0) it to "" else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
            val dev = query["dev"]?.lowercase()?.also { java.util.UUID.fromString(it) }
            val kv = query["kv"]?.toIntOrNull()
            val key = query["k"]?.let { DeviceKey.parse(it) }
            val host = uri.host ?: throw IllegalArgumentException("Missing host.")
            if (host.equals("key", ignoreCase = true) && (uri.path.isNullOrEmpty() || uri.path == "/")) {
                require(dev != null && kv != null && key != null) { "Key string is missing dev, kv or k." }
                return ConnectionString(deviceId = dev, keyVersion = kv, key = key, deviceName = query["dn"])
            }
            require(uri.path.equals("/pair", ignoreCase = true)) { "Unknown connection string." }
            val code = query["c"] ?: throw IllegalArgumentException("Pairing code missing.")
            require((key == null) == (dev == null) && (key == null) == (kv == null)) { "dev, kv and k must appear together." }
            return ConnectionString(
                host = if (uri.port == -1) host else "$host:${uri.port}",
                pairingCode = Base32.normalize(code),
                pin = query["pin"],
                deviceId = dev, keyVersion = kv, key = key, deviceName = query["dn"],
            )
        }
    }
}
