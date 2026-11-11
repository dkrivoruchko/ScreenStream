package info.dvkr.screenstream.rtmp.internal.rtmp

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * rtmp://host[:port]/app[/more/app]/streamKey[?query]  (rtmps:// for TLS)
 *
 * The last path segment is the stream key, everything before it is the application name.
 * User info (rtmp://user:pass@host/...) is forwarded as `?user=..&pass=..` query on the stream key,
 * which is how MediaMTX and several other servers accept RTMP credentials.
 */
internal class RtmpUrl(
    val tlsEnabled: Boolean,
    val host: String,
    val port: Int,
    val app: String,
    val streamName: String,
) {
    val tcUrl: String
        get() = "${if (tlsEnabled) "rtmps" else "rtmp"}://$host:$port/$app"

    internal companion object {
        @Throws(URISyntaxException::class)
        internal fun parse(endpoint: String): RtmpUrl {
            val uri = URI(endpoint)
            val scheme = uri.scheme ?: throw URISyntaxException(endpoint, "Scheme is missing")
            if (scheme != "rtmp" && scheme != "rtmps") throw URISyntaxException(endpoint, "Invalid protocol: $scheme")
            val tlsEnabled = scheme == "rtmps"
            val host = uri.host ?: throw URISyntaxException(endpoint, "Invalid/missing host")
            val port = when {
                uri.port >= 0 -> uri.port
                tlsEnabled -> 443
                else -> 1935
            }

            val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) throw URISyntaxException(endpoint, "Application name is missing")
            val app = if (segments.size == 1) segments[0] else segments.dropLast(1).joinToString("/")
            var streamName = if (segments.size == 1) "" else segments.last()

            val queryParts = mutableListOf<String>()
            if (!uri.rawQuery.isNullOrEmpty()) queryParts += uri.rawQuery
            if (!uri.userInfo.isNullOrEmpty()) {
                val parts = uri.userInfo.split(":", limit = 2)
                queryParts += "user=${URLEncoder.encode(parts[0], "UTF-8")}"
                queryParts += "pass=${URLEncoder.encode(parts.getOrNull(1).orEmpty(), "UTF-8")}"
            }
            if (queryParts.isNotEmpty()) streamName += "?" + queryParts.joinToString("&")

            return RtmpUrl(tlsEnabled, host, port, app, streamName)
        }
    }
}
