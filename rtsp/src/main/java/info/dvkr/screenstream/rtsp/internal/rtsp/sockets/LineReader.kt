package info.dvkr.screenstream.rtsp.internal.rtsp.sockets

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Reads a single line (terminated by LF, optional preceding CR) decoded as ISO-8859-1,
 * buffering at most [limit] bytes (line terminator excluded).
 *
 * @return the line, or `null` if the channel is closed before any byte was read
 * @throws IOException if the line exceeds [limit] or the channel closes mid-line
 */
@Throws(IOException::class)
internal suspend fun ByteReadChannel.readLineBounded(limit: Int): String? {
    if (limit <= 0) throw IOException("Header too large")
    val sb = StringBuilder()
    var read = 0
    while (true) {
        val b = try {
            readByte().toInt() and 0xFF
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            if (read == 0) return null
            throw IOException("Connection closed in the middle of a header line")
        }
        read++
        if (b == '\n'.code) {
            if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
            return sb.toString()
        }
        if (read > limit) throw IOException("Header line too long (> $limit bytes)")
        sb.append(b.toChar())
    }
}
