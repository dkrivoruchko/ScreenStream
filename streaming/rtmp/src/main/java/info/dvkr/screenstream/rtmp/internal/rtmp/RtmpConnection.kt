package info.dvkr.screenstream.rtmp.internal.rtmp

import info.dvkr.screenstream.rtmp.internal.rtmp.sockets.TcpStreamSocket
import java.io.IOException
import kotlin.math.min

internal class RtmpMessage(val typeId: Int, val streamId: Int, val timestamp: Long, val payload: ByteArray)

internal object RtmpConst {
    const val MSG_SET_CHUNK_SIZE = 1
    const val MSG_ABORT = 2
    const val MSG_ACK = 3
    const val MSG_USER_CONTROL = 4
    const val MSG_WINDOW_ACK_SIZE = 5
    const val MSG_SET_PEER_BANDWIDTH = 6
    const val MSG_AUDIO = 8
    const val MSG_VIDEO = 9
    const val MSG_DATA_AMF0 = 18
    const val MSG_COMMAND_AMF0 = 20

    const val UC_STREAM_BEGIN = 0
    const val UC_PING_REQUEST = 6
    const val UC_PING_RESPONSE = 7

    const val CSID_PROTOCOL = 2
    const val CSID_COMMAND = 3
    const val CSID_AUDIO = 4
    const val CSID_DATA = 5
    const val CSID_VIDEO = 6

    const val DEFAULT_CHUNK_SIZE = 128
    const val OUT_CHUNK_SIZE = 4096
    const val HANDSHAKE_SIZE = 1536
    const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024
}

/** RTMP chunk stream (de)multiplexer on top of a [TcpStreamSocket]. One reader coroutine, any number of writers. */
internal class RtmpConnection(private val socket: TcpStreamSocket) {

    private class ChunkState {
        var timestamp = 0L
        var timestampDelta = 0L
        var length = 0
        var typeId = 0
        var streamId = 0
        var hasExtendedTimestamp = false
        var payload: ByteArray? = null
        var received = 0
    }

    private val states = HashMap<Int, ChunkState>()

    @Volatile
    private var inChunkSize = RtmpConst.DEFAULT_CHUNK_SIZE
    private var outChunkSize = RtmpConst.DEFAULT_CHUNK_SIZE   // guarded by socket write lock

    @Volatile
    private var peerWindowAckSize = 0L
    private var bytesRead = 0L
    private var lastAckedAt = 0L

    // ---------------------------------------------------------------------------------------------- handshake

    /** Simple (non-digest) handshake, client side. */
    suspend fun clientHandshake() {
        val c0c1 = ByteArray(1 + RtmpConst.HANDSHAKE_SIZE)
        c0c1[0] = 3
        val random = ByteArray(RtmpConst.HANDSHAKE_SIZE - 8).also { java.security.SecureRandom().nextBytes(it) }
        System.arraycopy(random, 0, c0c1, 1 + 8, random.size)   // time(4)=0, zero(4)=0, random(1528)
        socket.withWriteLock { write(c0c1); flush() }

        val s0 = socket.readByte()
        if (s0 != 3) throw IOException("Unsupported RTMP version: $s0")
        val s1 = ByteArray(RtmpConst.HANDSHAKE_SIZE)
        socket.readFully(s1)
        val s2 = ByteArray(RtmpConst.HANDSHAKE_SIZE)
        socket.readFully(s2)
        socket.withWriteLock { write(s1); flush() }   // C2 = echo of S1
    }

    /** Simple (non-digest) handshake, server side. */
    suspend fun serverHandshake() {
        val c0 = socket.readByte()
        if (c0 != 3) throw IOException("Unsupported RTMP version: $c0")
        val c1 = ByteArray(RtmpConst.HANDSHAKE_SIZE)
        socket.readFully(c1)

        val s0s1s2 = ByteArray(1 + 2 * RtmpConst.HANDSHAKE_SIZE)
        s0s1s2[0] = 3
        val random = ByteArray(RtmpConst.HANDSHAKE_SIZE - 8).also { java.security.SecureRandom().nextBytes(it) }
        System.arraycopy(random, 0, s0s1s2, 1 + 8, random.size)                                   // S1: time=0, zero=0, random
        System.arraycopy(c1, 0, s0s1s2, 1 + RtmpConst.HANDSHAKE_SIZE, RtmpConst.HANDSHAKE_SIZE)   // S2 = echo of C1
        socket.withWriteLock { write(s0s1s2); flush() }

        val c2 = ByteArray(RtmpConst.HANDSHAKE_SIZE)
        socket.readFully(c2)
    }

    // ---------------------------------------------------------------------------------------------- reading

    private suspend fun u8(): Int = socket.readByte().also { bytesRead++ }
    private suspend fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
    private suspend fun u32(): Long = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()

    /** Blocks until a complete message is assembled. Handles chunk-size/ack bookkeeping internally. */
    @Throws(IOException::class)
    suspend fun readMessage(): RtmpMessage {
        while (true) {
            val b0 = u8()
            val fmt = b0 ushr 6
            var csid = b0 and 0x3F
            if (csid == 0) csid = 64 + u8()
            else if (csid == 1) { val lo = u8(); val hi = u8(); csid = 64 + lo + (hi shl 8) }

            val st = states.getOrPut(csid) { ChunkState() }

            var tsField = 0L
            when (fmt) {
                0 -> { tsField = u24().toLong(); st.length = u24(); st.typeId = u8(); st.streamId = Integer.reverseBytes(u32().toInt()) }
                1 -> { tsField = u24().toLong(); st.length = u24(); st.typeId = u8() }
                2 -> { tsField = u24().toLong() }
            }
            if (fmt != 3) {
                st.hasExtendedTimestamp = tsField == 0xFFFFFFL
                if (st.hasExtendedTimestamp) tsField = u32()
                if (fmt == 0) { st.timestamp = tsField; st.timestampDelta = 0 } else st.timestampDelta = tsField
            } else if (st.hasExtendedTimestamp) {
                u32()
            }

            if (st.payload == null) {
                if (st.length < 0 || st.length > RtmpConst.MAX_MESSAGE_SIZE) throw IOException("RTMP message too large: ${st.length}")
                if (fmt != 0) st.timestamp = (st.timestamp + st.timestampDelta) and 0xFFFFFFFFL
                st.payload = ByteArray(st.length)
                st.received = 0
            }

            val payload = st.payload!!
            val toRead = min(inChunkSize, st.length - st.received)
            if (toRead > 0) {
                val tmp = ByteArray(toRead)
                socket.readFully(tmp, toRead)
                bytesRead += toRead
                System.arraycopy(tmp, 0, payload, st.received, toRead)
                st.received += toRead
            }

            if (st.received >= st.length) {
                st.payload = null
                val message = RtmpMessage(st.typeId, st.streamId, st.timestamp, payload)
                onProtocolMessage(message)
                return message
            }
        }
    }

    private suspend fun onProtocolMessage(message: RtmpMessage) {
        val p = message.payload
        when (message.typeId) {
            RtmpConst.MSG_SET_CHUNK_SIZE -> if (p.size >= 4) {
                inChunkSize = (readInt(p, 0) and 0x7FFFFFFF).coerceIn(1, 0x1000000)
            }
            RtmpConst.MSG_ABORT -> if (p.size >= 4) states[readInt(p, 0)]?.payload = null
            RtmpConst.MSG_WINDOW_ACK_SIZE -> if (p.size >= 4) peerWindowAckSize = readInt(p, 0).toLong() and 0xFFFFFFFFL
            RtmpConst.MSG_USER_CONTROL -> if (p.size >= 6 && ((p[0].toInt() and 0xFF) shl 8 or (p[1].toInt() and 0xFF)) == RtmpConst.UC_PING_REQUEST) {
                writeMessage(RtmpConst.CSID_PROTOCOL, RtmpConst.MSG_USER_CONTROL, 0, 0, byteArrayOf(0, RtmpConst.UC_PING_RESPONSE.toByte(), p[2], p[3], p[4], p[5]))
            }
        }
        if (peerWindowAckSize > 0 && bytesRead - lastAckedAt >= peerWindowAckSize) {
            lastAckedAt = bytesRead
            writeMessage(RtmpConst.CSID_PROTOCOL, RtmpConst.MSG_ACK, 0, 0, intBytes((bytesRead and 0xFFFFFFFFL).toInt()))
        }
    }

    // ---------------------------------------------------------------------------------------------- writing

    /** Writes one message, split into chunks, as a single atomic socket write. */
    @Throws(IOException::class)
    suspend fun writeMessage(
        csid: Int,
        typeId: Int,
        streamId: Int,
        timestamp: Long,
        payload: ByteArray,
        length: Int = payload.size,
        flush: Boolean = true,
    ) {
        require(csid in 2..63) { "Unsupported csid $csid" }
        socket.withWriteLock {
            val chunkSize = outChunkSize
            val extended = timestamp >= 0xFFFFFFL
            val extSize = if (extended) 4 else 0
            val chunks = if (length == 0) 1 else (length + chunkSize - 1) / chunkSize
            val total = (1 + 11 + extSize) + length + (chunks - 1) * (1 + extSize)
            val buf = ByteArray(total)
            var o = 0

            buf[o++] = csid.toByte()                                          // fmt 0 | csid
            val tsField = if (extended) 0xFFFFFF else timestamp.toInt()
            buf[o++] = (tsField ushr 16).toByte(); buf[o++] = (tsField ushr 8).toByte(); buf[o++] = tsField.toByte()
            buf[o++] = (length ushr 16).toByte(); buf[o++] = (length ushr 8).toByte(); buf[o++] = length.toByte()
            buf[o++] = typeId.toByte()
            buf[o++] = streamId.toByte(); buf[o++] = (streamId ushr 8).toByte()                // little endian
            buf[o++] = (streamId ushr 16).toByte(); buf[o++] = (streamId ushr 24).toByte()
            if (extended) o = putInt(buf, o, timestamp.toInt())

            var sent = 0
            while (true) {
                val n = min(chunkSize, length - sent)
                System.arraycopy(payload, sent, buf, o, n)
                o += n; sent += n
                if (sent >= length) break
                buf[o++] = (0xC0 or csid).toByte()                            // fmt 3 continuation
                if (extended) o = putInt(buf, o, timestamp.toInt())
            }

            write(buf, o)
            if (flush) flush()
        }
    }

    /** Announces and applies a new outgoing chunk size. */
    suspend fun setOutChunkSize(size: Int) {
        socket.withWriteLock {
            val msg = ByteArray(1 + 11 + 4)
            msg[0] = RtmpConst.CSID_PROTOCOL.toByte()
            msg[6] = 4                                      // payload length = 4 (24-bit big endian at bytes 4..6)
            msg[7] = RtmpConst.MSG_SET_CHUNK_SIZE.toByte()
            putInt(msg, 12, size)
            write(msg); flush()
            outChunkSize = size
        }
    }

    suspend fun sendWindowAckSize(size: Int) =
        writeMessage(RtmpConst.CSID_PROTOCOL, RtmpConst.MSG_WINDOW_ACK_SIZE, 0, 0, intBytes(size))

    suspend fun sendSetPeerBandwidth(size: Int, limitType: Int = 2) =
        writeMessage(RtmpConst.CSID_PROTOCOL, RtmpConst.MSG_SET_PEER_BANDWIDTH, 0, 0, intBytes(size) + byteArrayOf(limitType.toByte()))

    suspend fun sendUserControl(eventType: Int, value: Int) = writeMessage(
        RtmpConst.CSID_PROTOCOL, RtmpConst.MSG_USER_CONTROL, 0, 0,
        byteArrayOf((eventType ushr 8).toByte(), eventType.toByte()) + intBytes(value)
    )

    // ---------------------------------------------------------------------------------------------- helpers

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun putInt(b: ByteArray, o: Int, v: Int): Int {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
        return o + 4
    }

    private fun intBytes(v: Int): ByteArray = ByteArray(4).also { putInt(it, 0, v) }
}
