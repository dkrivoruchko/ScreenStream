package info.dvkr.screenstream.rtsp.internal.rtsp.sockets

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ConnectedDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeFully
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet6Address
import java.net.InetAddress

internal class UdpStreamSocket(
    private val selectorManager: SelectorManager,
    private val remoteHost: String,
    private val remotePort: Int,
    private val localPort: Int
) {
    private val ioMutex = Mutex()
    private var udpSocket: ConnectedDatagramSocket? = null
    private var remoteSocketAddress: InetSocketAddress? = null

    suspend fun connect() = ioMutex.withLock {
        runCatching { udpSocket?.close() }
        udpSocket = null
        val remoteAddress = InetAddress.getByName(remoteHost)
        val resolvedRemote = InetSocketAddress(remoteAddress.hostAddress ?: remoteHost, remotePort)
        val localBindHost = if (remoteAddress is Inet6Address) "::" else "0.0.0.0"
        udpSocket = aSocket(selectorManager)
            .udp()
            .connect(resolvedRemote, InetSocketAddress(localBindHost, localPort))
        remoteSocketAddress = resolvedRemote
    }

    suspend fun close() = ioMutex.withLock {
        runCatching { udpSocket?.close() }
        udpSocket = null
    }

    suspend fun write(bytes: ByteArray) = ioMutex.withLock {
        val target = remoteSocketAddress ?: return@withLock
        udpSocket?.send(Datagram(buildPacket { writeFully(bytes) }, target))
    }

    suspend fun write(bytes: ByteArray, offset: Int, length: Int) = ioMutex.withLock {
        val target = remoteSocketAddress ?: return@withLock
        udpSocket?.send(Datagram(buildPacket { writeFully(bytes, offset, length) }, target))
    }

    fun localPort(): Int? = (udpSocket?.localAddress as? InetSocketAddress)?.port
}
