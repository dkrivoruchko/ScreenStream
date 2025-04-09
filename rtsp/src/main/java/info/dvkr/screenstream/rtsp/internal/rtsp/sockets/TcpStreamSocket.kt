package info.dvkr.screenstream.rtsp.internal.rtsp.sockets

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ReadWriteSocket
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
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

internal class TcpStreamSocket(
    private val tlsCoroutineContext: CoroutineContext,
    private val selectorManager: SelectorManager,
    private val remoteHost: String,
    private val remotePort: Int,
    private val sslEnabled: Boolean
) {
    private val ioMutex = Mutex()

    private var tcpSocket: ReadWriteSocket? = null
    private var input: ByteReadChannel? = null
    private var output: ByteWriteChannel? = null
    private var connected = false

    internal suspend inline fun <T> withLock(crossinline block: suspend TcpStreamSocket.() -> T): T = ioMutex.withLock { block() }

    internal suspend fun connect() {
        withTimeout(5_000) {
            tcpSocket = aSocket(selectorManager)
                .tcp()
                .connect(InetSocketAddress(remoteHost, remotePort)) { keepAlive = true; noDelay = true }
                .apply { if (sslEnabled) tls(tlsCoroutineContext) }
                .apply {
                    input = openReadChannel()
                    output = openWriteChannel()
                }
            connected = true
        }
    }

    internal fun close() {
        input = null
        output = null
        runCatching { tcpSocket?.close() }
        tcpSocket = null
        connected = false
    }

    internal fun isConnected(): Boolean = connected && (tcpSocket?.isClosed == false)

    @Throws
    internal suspend fun writeAndFlush(bytes1: ByteArray, bytes2: ByteArray, offset2: Int = 0, size2: Int = bytes2.size) {
        output?.writeFully(bytes1)
        output?.writeFully(bytes2, offset2, size2)
        output?.flush()
    }

    @Throws
    internal suspend fun writeAndFlush(string: String) {
        output?.writeStringUtf8(string)
        output?.flush()
    }

    @Throws
    internal suspend fun readLine(): String? = input?.readUTF8Line()

    @Throws
    internal suspend fun readBytes(buffer: ByteArray, length: Int) = input?.readFully(buffer, 0, length)
}