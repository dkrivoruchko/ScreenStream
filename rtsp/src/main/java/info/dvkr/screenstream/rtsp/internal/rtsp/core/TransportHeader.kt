package info.dvkr.screenstream.rtsp.internal.rtsp.core

internal data class TransportHeader(
    val profile: String,
    val unicast: Boolean = true,
    val interleaved: Pair<Int, Int>? = null,
    val clientPorts: Pair<Int, Int>? = null,
    val serverPorts: Pair<Int, Int>? = null,
    val mode: String? = null,
    val modeQuoted: Boolean = false,
    val extensions: List<String> = emptyList(),
) {
    fun withServerPorts(rtp: Int, rtcp: Int): TransportHeader = copy(serverPorts = rtp to rtcp)

    override fun toString(): String {
        val parts = mutableListOf<String>()
        parts += profile
        if (unicast) parts += "unicast" else parts += "multicast"
        interleaved?.let { parts += "interleaved=${it.first}-${it.second}" }
        clientPorts?.let { parts += "client_port=${it.first}-${it.second}" }
        serverPorts?.let { parts += "server_port=${it.first}-${it.second}" }
        mode?.let { mode ->
            val v = if (modeQuoted) "\"$mode\"" else mode
            parts += "mode=$v"
        }
        if (extensions.isNotEmpty()) parts += extensions
        return parts.joinToString("; ")
    }

    companion object {
        fun parse(text: String): TransportHeader? {
            val first = text.split(',').firstOrNull()?.trim() ?: return null
            val tokens = first.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val profile = tokens.first()
            var unicast = true
            var interleaved: Pair<Int, Int>? = null
            var client: Pair<Int, Int>? = null
            var server: Pair<Int, Int>? = null
            var mode: String? = null
            var modeQuoted = false
            val extras = mutableListOf<String>()
            for (i in 1 until tokens.size) {
                val token = tokens[i]
                when {
                    token.equals("unicast", true) -> unicast = true
                    token.equals("multicast", true) -> unicast = false
                    token.startsWith("interleaved=", true) -> {
                        val v = token.substringAfter('=')
                        val a = v.split('-').mapNotNull { it.toIntOrNull() }
                        if (a.size == 2) interleaved = a[0] to a[1]
                    }

                    token.startsWith("client_port=", true) -> {
                        val v = token.substringAfter('=')
                        val a = v.split('-').mapNotNull { it.trim().toIntOrNull() }
                        if (a.size == 2) client = a[0] to a[1]
                        else if (a.size == 1) client = a[0] to (a[0] + 1)
                    }

                    token.startsWith("server_port=", true) -> {
                        val v = token.substringAfter('=')
                        val a = v.split('-').mapNotNull { it.trim().toIntOrNull() }
                        if (a.size == 2) server = a[0] to a[1]
                        else if (a.size == 1) server = a[0] to (a[0] + 1)
                    }

                    token.startsWith("mode=", true) -> {
                        val raw = token.substringAfter('=').trim()
                        modeQuoted = raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2
                        mode = raw.trim('"')
                    }

                    else -> extras += token
                }
            }
            return TransportHeader(profile, unicast, interleaved, client, server, mode, modeQuoted, extras)
        }
    }
}
