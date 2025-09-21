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

    internal fun hasAuth(): Boolean = !user.isNullOrEmpty() && !password.isNullOrEmpty()

    internal companion object {
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
                else -> 554
            }

            val rawPath = uri.path.orEmpty().trim().removePrefix("/")
                .ifBlank { throw URISyntaxException(endpoint, "Invalid/missing path") }
            val fullPath = if (!uri.query.isNullOrEmpty()) "/$rawPath?${uri.query}" else "/$rawPath"

            val (user, password) = if (uri.userInfo.isNullOrEmpty()) {
                null to null
            } else {
                if (!uri.userInfo.contains(":")) {
                    throw URISyntaxException(endpoint, "Invalid auth. Must contain 'username:password' if present")
                }
                val parts = uri.userInfo.split(":", limit = 2)
                parts.getOrNull(0) to parts.getOrNull(1)
            }

            return RtspUrl(tlsEnabled, host, port, fullPath, user, password)
        }
    }
}