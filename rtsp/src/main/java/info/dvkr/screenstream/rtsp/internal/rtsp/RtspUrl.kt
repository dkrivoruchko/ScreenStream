package info.dvkr.screenstream.rtsp.internal.rtsp

import java.net.URI
import java.net.URISyntaxException

internal class RtspUrl(
    val tlsEnabled: Boolean,
    val host: String,
    val port: Int,
    val fullPath: String,
    val user: String?,
    val password: String?
) {

    internal fun hasAuth(): Boolean = !user.isNullOrEmpty()

    internal companion object {
        /** Removes `user:password@` from a URL-like string so it can be logged safely. */
        internal fun redactUserInfo(endpoint: String): String {
            val schemeEnd = endpoint.indexOf("://")
            val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
            val at = endpoint.lastIndexOf('@')
            if (at < start) return endpoint
            return endpoint.substring(0, start) + "***@" + endpoint.substring(at + 1)
        }

        @Throws(URISyntaxException::class)
        internal fun parse(endpoint: String): RtspUrl {
            val uri = URI(endpoint)

            val scheme = uri.scheme ?: throw URISyntaxException(endpoint, "Scheme is missing")
            if (scheme != "rtsp" && scheme != "rtsps") {
                throw URISyntaxException(endpoint, "Invalid protocol: $scheme")
            }
            val tlsEnabled = scheme.endsWith('s')

            val host = uri.host ?: throw URISyntaxException(endpoint, "Invalid/missing host")

            val port = when {
                uri.port >= 0 -> uri.port
                tlsEnabled -> 322
                else -> 554
            }

            val rawPath = uri.path.orEmpty().trim().removePrefix("/")
            val fullPath = when {
                rawPath.isEmpty() && !uri.query.isNullOrEmpty() -> "/?${uri.query}"
                rawPath.isEmpty() -> "/"
                !uri.query.isNullOrEmpty() -> "/$rawPath?${uri.query}"
                else -> "/$rawPath"
            }

            val (user, password) = if (uri.userInfo.isNullOrEmpty()) {
                null to null
            } else {
                val parts = uri.userInfo.split(":", limit = 2)
                val u = parts.getOrNull(0)
                val p = parts.getOrNull(1) ?: ""
                u to p
            }

            return RtspUrl(tlsEnabled, host, port, fullPath, user, password)
        }
    }
}
