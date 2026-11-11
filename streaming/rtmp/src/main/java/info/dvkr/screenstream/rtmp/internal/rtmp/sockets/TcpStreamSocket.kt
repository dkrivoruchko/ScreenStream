package info.dvkr.screenstream.rtmp.internal.rtmp.sockets

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
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/** Thin TCP (optionally TLS) wrapper used by both RTMP client and RTMP server connections. */
internal class TcpStreamSocket private constructor(
    private val tlsCoroutineContext: CoroutineContext,
    internal val selectorManager: SelectorManager,
    @Volatile private var tcpSocket: ReadWriteSocket?,
    internal val remoteHost: String,
    private val remotePort: Int,
    private val sslEnabled: Boolean
) {
    private val writeMutex = Mutex()

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

    // Serialize writes: an RTMP message (all its chunks) must never be interleaved with another message
    internal suspend inline fun <T> withWriteLock(crossinline block: suspend TcpStreamSocket.() -> T): T = writeMutex.withLock { block() }

    internal suspend fun connect() {
        if (tcpSocket != null) return
        withTimeout(10_000.milliseconds) {
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

    /** Reads exactly [length] bytes into [dst] starting at index 0. Throws if the stream ends first. */
    @Throws(IOException::class)
    internal suspend fun readFully(dst: ByteArray, length: Int = dst.size) {
        val channel = input ?: throw IOException("Socket is not connected")
        channel.readFully(dst, 0, length)
    }

    @Throws(IOException::class)
    internal suspend fun readByte(): Int {
        val channel = input ?: throw IOException("Socket is not connected")
        val one = ByteArray(1)
        channel.readFully(one, 0, 1)
        return one[0].toInt() and 0xFF
    }

    /** Must be called under [withWriteLock]. Writes bytes [0, length) of [bytes]. */
    @Throws(IOException::class)
    internal suspend fun write(bytes: ByteArray, length: Int = bytes.size) {
        val channel = output ?: throw IOException("Socket is not connected")
        channel.writeFully(bytes, 0, length)
    }

    /** Must be called under [withWriteLock]. */
    @Throws(IOException::class)
    internal suspend fun flush() {
        output?.flush()
    }
}
