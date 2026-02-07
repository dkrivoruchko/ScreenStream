package info.dvkr.screenstream.rtsp.internal.rtsp.sockets

import info.dvkr.screenstream.rtsp.internal.rtsp.RtspMessage
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ReadWriteSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.tls.tls
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.CoroutineContext

internal class TcpStreamSocket private constructor(
    private val tlsCoroutineContext: CoroutineContext,
    internal val selectorManager: SelectorManager,
    @Volatile private var tcpSocket: ReadWriteSocket?,
    internal val remoteHost: String,
    private val remotePort: Int,
    private val sslEnabled: Boolean
) {
    // Separate locks for write and read: avoid blocking RTP interleaved writes
    // while the command loop is waiting for next RTSP request.
    private val writeMutex = Mutex()
    private val readMutex = Mutex()

    private var input: ByteReadChannel? = null
    private var output: ByteWriteChannel? = null
    @Volatile
    private var connected = false

    // For client
    constructor(
        tlsCoroutineContext: CoroutineContext,
        selectorManager: SelectorManager,
        remoteHost: String,
        remotePort: Int,
        sslEnabled: Boolean
    ) : this(tlsCoroutineContext, selectorManager, null, remoteHost, remotePort, sslEnabled)

    // For server (accepted socket)
    constructor(
        tlsCoroutineContext: CoroutineContext,
        selectorManager: SelectorManager,
        acceptedSocket: Socket
    ) : this(
        tlsCoroutineContext,
        selectorManager,
        acceptedSocket,
        (acceptedSocket.remoteAddress as InetSocketAddress).hostname,
        (acceptedSocket.remoteAddress as InetSocketAddress).port,
        false
    ) {
        input = acceptedSocket.openReadChannel()
        output = acceptedSocket.openWriteChannel()
        connected = true
    }

    // Serialize writes to keep ByteWriteChannel framing atomically consistent
    internal suspend inline fun <T> withWriteLock(crossinline block: suspend TcpStreamSocket.() -> T): T = writeMutex.withLock { block() }

    internal suspend fun connect() {
        if (tcpSocket != null) return
        withTimeout(10_000) {
            tcpSocket = aSocket(selectorManager)
                .tcp()
                .connect(InetSocketAddress(remoteHost, remotePort)) { keepAlive = true; noDelay = true }
                .run { if (sslEnabled) tls(tlsCoroutineContext) else this }
                .apply {
                    input = openReadChannel()
                    output = openWriteChannel()
                }
            connected = true
        }
    }

    internal suspend fun close() = writeMutex.withLock {
        runCatching { tcpSocket?.close() }
        tcpSocket = null
        connected = false
    }

    internal fun isConnected(): Boolean = connected && (tcpSocket?.isClosed == false)

    @Throws
    internal suspend fun writeAndFlush(message: RtspMessage) {
        output?.writeFully(message.header)
        if (message.body != null) output?.writeFully(message.body)
        output?.flush()
    }

    @Throws
    internal suspend fun writeAndFlush(bytes1: ByteArray, bytes2: ByteArray, offset2: Int = 0, size2: Int = bytes2.size) {
        output?.writeFully(bytes1)
        output?.writeFully(bytes2, offset2, size2)
        output?.flush()
    }

    // Optimized path for RTP interleaved packets: buffer multiple writes and flush once.
    internal suspend fun write(bytes1: ByteArray, bytes2: ByteArray, offset2: Int = 0, size2: Int = bytes2.size) {
        output?.writeFully(bytes1)
        output?.writeFully(bytes2, offset2, size2)
    }

    internal suspend fun flush() {
        output?.flush()
    }

    /**
     * Reads a single RTSP request/response message, while skipping RTSP interleaved ($) frames.
     *
     * @param allowedInterleavedChannels
     * - `null`: accept and skip interleaved frames on any channel (client-friendly).
     * - non-null: only accept channels in the set; otherwise throws (server-strict).
     */
    @Throws(IOException::class)
    internal suspend fun readRtspMessage(
        allowedInterleavedChannels: Set<Int>? = emptySet(),
        maxInterleavedLength: Int = 65535,
        maxHeaderLength: Int = 16 * 1024,
        maxBodyLength: Int = 256 * 1024,
        onInterleavedChunk: (suspend (channel: Int, data: ByteArray, length: Int, isLast: Boolean) -> Unit)? = null
    ): RtspMessage? = readMutex.withLock {
        val ch = input ?: return@withLock null
        val one = ByteArray(1)
        val scratch = ByteArray(minOf(4096, maxInterleavedLength))

        while (true) {
            if (runCatching { ch.readFully(one, 0, 1) }.isFailure) return@withLock null
            val first = one[0]

            if (first == '$'.code.toByte()) {
                // Interleaved frame: '$' + channel + length(2) + payload
                val hdr = ByteArray(3)
                if (runCatching { ch.readFully(hdr, 0, 3) }.isFailure) return@withLock null
                val channel = hdr[0].toInt() and 0xFF
                val len = ((hdr[1].toInt() and 0xFF) shl 8) or (hdr[2].toInt() and 0xFF)

                if (len > maxInterleavedLength) throw IOException("Interleaved frame length $len exceeds limit $maxInterleavedLength")

                val allowed = allowedInterleavedChannels
                if (allowed != null && channel !in allowed) throw IOException("Unexpected interleaved channel $channel")

                var remaining = len
                while (remaining > 0) {
                    val toRead = minOf(remaining, scratch.size)
                    if (runCatching { ch.readFully(scratch, 0, toRead) }.isFailure) return@withLock null
                    onInterleavedChunk?.invoke(channel, scratch, toRead, remaining == toRead)
                    remaining -= toRead
                }
                continue
            }

            // Start of RTSP message; assemble start line from first byte + remainder
            val sb = StringBuilder()
            var headerBytes = 0
            var contentLength = 0

            sb.append(first.toInt().toChar())
            val firstLine = ch.readLine() ?: return@withLock null
            headerBytes += 1 + firstLine.length
            if (headerBytes > maxHeaderLength) throw IOException("RTSP header too large (> $maxHeaderLength bytes)")
            sb.append(firstLine).append("\r\n")

            while (true) {
                val line = ch.readLine() ?: return@withLock null
                if (line.isEmpty()) break
                headerBytes += line.length + 2
                if (headerBytes > maxHeaderLength) throw IOException("RTSP header too large (> $maxHeaderLength bytes)")
                sb.append(line).append("\r\n")

                if (line.startsWith("Content-Length", ignoreCase = true)) {
                    contentLength = line.substringAfter(':', "").trim().toIntOrNull()?.coerceAtLeast(0) ?: contentLength
                }
            }

            if (contentLength > maxBodyLength) throw IOException("RTSP body too large ($contentLength > $maxBodyLength bytes)")
            val body = if (contentLength <= 0) null else ByteArray(contentLength).also { ch.readFully(it, 0, contentLength) }
            return@withLock RtspMessage(header = sb.toString().toByteArray(Charsets.ISO_8859_1), body = body)
        }

        // Defensive: gives the lambda an explicit RtspMessage? result type for inference.
        null
    }
}
