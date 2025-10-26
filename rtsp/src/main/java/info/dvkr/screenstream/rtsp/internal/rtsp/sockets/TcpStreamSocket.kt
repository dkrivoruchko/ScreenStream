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
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

internal class TcpStreamSocket private constructor(
    private val tlsCoroutineContext: CoroutineContext,
    internal val selectorManager: SelectorManager,
    private var tcpSocket: ReadWriteSocket?,
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
    internal suspend inline fun <T> withLock(crossinline block: suspend TcpStreamSocket.() -> T): T = writeMutex.withLock { block() }


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

    @Throws
    internal suspend fun writeAndFlush(string: String) {
        // Avoid potential issues in writeStringUtf8 with large strings on some kotlinx-io versions
        val bytes = string.toByteArray(Charsets.US_ASCII)
        output?.writeFully(bytes)
        output?.flush()
    }

    @Throws
    internal suspend fun readRequestHeaders(): String? {
        val ch = input ?: return null
        val one = ByteArray(1)
        while (true) {
            // Read first byte; null -> closed
            if (runCatching { ch.readFully(one, 0, 1) }.isFailure) return null
            val first = one[0]
            if (first == '$'.code.toByte()) {
                // Interleaved frame: skip channel + length + payload
                val hdr = ByteArray(3)
                if (runCatching { ch.readFully(hdr, 0, 3) }.isFailure) return null
                val len = ((hdr[1].toInt() and 0xFF) shl 8) or (hdr[2].toInt() and 0xFF)
                if (len > 0) {
                    val payload = ByteArray(len)
                    if (runCatching { ch.readFully(payload, 0, len) }.isFailure) return null
                }
                // Continue to next item (either another interleaved frame or RTSP request)
                continue
            } else {
                // Start of RTSP request; assemble first line from first byte + remainder
                val sb = StringBuilder()
                sb.append(first.toInt().toChar())
                val firstLine = ch.readUTF8Line() ?: return null
                sb.append(firstLine).append("\r\n")
                while (true) {
                    val line = ch.readUTF8Line() ?: break
                    if (line.isEmpty()) break
                    sb.append(line).append("\r\n")
                }
                return sb.toString()
            }
        }
    }

    @Throws
    internal suspend fun readLine(): String? = input?.readUTF8Line()

    @Throws
    internal suspend fun readBytes(buffer: ByteArray, length: Int) = input?.readFully(buffer, 0, length)
}
