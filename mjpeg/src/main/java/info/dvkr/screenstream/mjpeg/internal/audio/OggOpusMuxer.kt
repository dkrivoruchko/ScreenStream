package info.dvkr.screenstream.mjpeg.internal.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

internal class OggOpusMuxer(
    private val channelCount: Int,
    private val serial: Int = Random.nextInt()
) {
    private var sequence = 0
    private var granulePosition = 0L

    fun headerPages(): List<ByteArray> = listOf(
        createPage(createOpusHead(), granule = 0L, headerType = FLAG_BOS),
        createPage(createOpusTags(), granule = 0L, headerType = 0)
    )

    fun audioPage(packet: ByteArray, durationSamples: Int): ByteArray {
        granulePosition += durationSamples.coerceAtLeast(1)
        return createPage(packet, granule = granulePosition, headerType = 0)
    }

    fun eosPage(): ByteArray = createPage(ByteArray(0), granule = granulePosition, headerType = FLAG_EOS)

    private fun createOpusHead(): ByteArray =
        ByteBuffer.allocate(19)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1)
            .put(channelCount.toByte())
            .putShort(312)
            .putInt(OPUS_SAMPLE_RATE)
            .putShort(0)
            .put(0)
            .array()

    private fun createOpusTags(): ByteArray {
        val vendor = "ScreenStream".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + 4 + vendor.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".toByteArray(Charsets.US_ASCII))
            .putInt(vendor.size)
            .put(vendor)
            .putInt(0)
            .array()
    }

    private fun createPage(packet: ByteArray, granule: Long, headerType: Int): ByteArray {
        val lacing = buildList {
            var remaining = packet.size
            while (remaining >= 255) {
                add(255)
                remaining -= 255
            }
            add(remaining)
        }
        require(lacing.size <= 255) { "Ogg packet is too large for one page" }

        val page = ByteArray(27 + lacing.size + packet.size)
        val buffer = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("OggS".toByteArray(Charsets.US_ASCII))
        buffer.put(0)
        buffer.put(headerType.toByte())
        buffer.putLong(granule)
        buffer.putInt(serial)
        buffer.putInt(sequence++)
        buffer.putInt(0)
        buffer.put(lacing.size.toByte())
        lacing.forEach { buffer.put(it.toByte()) }
        buffer.put(packet)

        val checksum = checksum(page)
        ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(checksum)
        return page
    }

    private fun checksum(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) {
                    crc shl 1 xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }

    private companion object {
        private const val FLAG_BOS = 0x02
        private const val FLAG_EOS = 0x04
    }
}
