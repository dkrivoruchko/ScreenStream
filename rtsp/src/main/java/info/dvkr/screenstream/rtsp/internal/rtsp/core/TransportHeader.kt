package info.dvkr.screenstream.rtsp.internal.rtsp.core

internal data class TransportHeader(
    val profile: String, // e.g. "RTP/AVP" or "RTP/AVP/TCP"
    val unicast: Boolean = true,
    val interleaved: Pair<Int, Int>? = null,
    val clientPorts: Pair<Int, Int>? = null,
    val serverPorts: Pair<Int, Int>? = null,
) {
    fun withServerPorts(rtp: Int, rtcp: Int): TransportHeader = copy(serverPorts = rtp to rtcp)

    override fun toString(): String {
        val parts = mutableListOf<String>()
        parts += profile
        if (unicast) parts += "unicast" else parts += "multicast"
        interleaved?.let { parts += "interleaved=${it.first}-${it.second}" }
        clientPorts?.let { parts += "client_port=${it.first}-${it.second}" }
        serverPorts?.let { parts += "server_port=${it.first}-${it.second}" }
        return parts.joinToString(";")
    }

    companion object {
        fun parse(text: String): TransportHeader? {
            // Split by comma first (we only support first alternative)
            val first = text.split(',').firstOrNull()?.trim() ?: return null
            val tokens = first.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val profile = tokens.first()
            var unicast = true
            var interleaved: Pair<Int, Int>? = null
            var client: Pair<Int, Int>? = null
            var server: Pair<Int, Int>? = null
            for (i in 1 until tokens.size) {
                val t = tokens[i]
                when {
                    t.equals("unicast", true) -> unicast = true
                    t.equals("multicast", true) -> unicast = false
                    t.startsWith("interleaved=", true) -> {
                        val v = t.substringAfter('=')
                        val a = v.split('-').mapNotNull { it.toIntOrNull() }
                        if (a.size == 2) interleaved = a[0] to a[1]
                    }

                    t.startsWith("client_port=", true) -> {
                        val v = t.substringAfter('=')
                        val a = v.split('-').mapNotNull { it.toIntOrNull() }
                        if (a.size == 2) client = a[0] to a[1]
                    }

                    t.startsWith("server_port=", true) -> {
                        val v = t.substringAfter('=')
                        val a = v.split('-').mapNotNull { it.toIntOrNull() }
                        if (a.size == 2) server = a[0] to a[1]
                    }
                }
            }
            return TransportHeader(profile, unicast, interleaved, client, server)
        }
    }
}

