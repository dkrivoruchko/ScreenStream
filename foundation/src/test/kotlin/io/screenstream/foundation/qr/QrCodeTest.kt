package io.screenstream.foundation.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test
    fun selectsSmallestVersionAtEveryByteCapacity() {
        byteCapacities.forEachIndexed { index, capacity ->
            val version = index + 1
            val qr = QrEncoder("a".repeat(capacity))
            assertEquals("Version $version", version * 4 + 17, qr.size)
            assertFunctionPatterns(qr)
            if (version < 40) {
                assertEquals("After version $version", qr.size + 4, QrEncoder("a".repeat(capacity + 1)).size)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { QrEncoder("a".repeat(2332)) }
    }

    @Test
    fun preservesExactUrlBytesAndDeclaresUtf8WhenNeeded() {
        val texts = listOf(
            "",
            "http://10.0.2.15:8080/",
            "rtsp://[::1]:8554/",
            "https://s.example/?a=%20&b=+#p=Ab3",
            "http://a/екран/😀?q=é",
            "https://例え.example/路径",
            "é",
            "e\u0301",
            "😀",
        )
        for (text in texts) {
            val qr = QrEncoder(text)
            val (eci, bytes) = readSingleBlockPayload(qr)
            assertArrayEquals(text, text.toByteArray(Charsets.UTF_8), bytes)
            assertEquals(text, if (text.any { it.code > 127 }) 26 else null, eci)
        }
    }

    @Test
    fun parityHasZeroSyndromesAndDetectsChangedCodeword() {
        // Versions 1–6 have equal-size blocks: count, data codewords, ECC codewords.
        val layouts = listOf(
            Triple(1, 16, 10), Triple(1, 28, 16), Triple(1, 44, 26),
            Triple(2, 32, 18), Triple(2, 43, 24), Triple(4, 27, 16),
        )
        for ((index, layout) in layouts.withIndex()) {
            val version = index + 1
            val (blockCount, dataLength, eccLength) = layout
            val prefix = when (version) {
                4 -> "http://[2001:db8::1]:8080/?token="
                5 -> "https://s.example/?id=stream#p="
                6 -> "rtsp://[2001:db8::1]:8554/screen?token="
                else -> "http://a/"
            }
            val payload = prefix + "x".repeat(byteCapacities[index] - prefix.length)
            val codewords = readCodewords(QrEncoder(payload))
            assertEquals(blockCount * (dataLength + eccLength), codewords.size)
            for (block in 0 until blockCount) {
                val words = ByteArray(dataLength + eccLength) { codewords[it * blockCount + block] }
                assertTrue("Version $version block $block parity", hasValidParity(words, eccLength))
                words[words.lastIndex] = (words.last().toInt() xor 1).toByte()
                assertFalse("Version $version block $block changed codeword", hasValidParity(words, eccLength))
            }
        }
    }

    @Test
    fun includesEciOverheadInVersionSelection() {
        assertEquals(21, QrEncoder("é" + "a".repeat(11)).size) // 13 bytes plus ECI fit version 1.
        assertEquals(25, QrEncoder("é" + "a".repeat(12)).size)
        assertEquals(53, QrEncoder("é" + "a".repeat(177)).size) // 179 bytes fit version 9.
        assertEquals(57, QrEncoder("é" + "a".repeat(178)).size)
        assertEquals(177, QrEncoder("é" + "a".repeat(2328)).size)
        assertThrows(IllegalArgumentException::class.java) { QrEncoder("é" + "a".repeat(2329)) }
    }

    @Test
    fun formatInformationIdentifiesAllEightMasks() {
        val masks = mutableSetOf<Int>()
        for (number in 0 until 256) {
            val qr = QrEncoder("https://s.example/" + "x".repeat(number) + "?id=$number")
            masks += readFormat(qr)
            if (masks.size == 8) break
        }
        assertEquals((0..7).toSet(), masks)
    }

    @Test
    fun rendersIntegerModulesWithCenteredWhiteQuietZone() {
        for (text in listOf("http://a/", "http://a/екран/😀", "a".repeat(2331))) {
            val qr = QrEncoder(text)
            for (side in listOf(qr.size + 8, (qr.size + 8) * 2, (qr.size + 8) * 2 + 7)) {
                val pixels = qr.renderPixels(side)
                val scale = side / (qr.size + 8)
                val left = (side - qr.size * scale) / 2
                val right = side - left - qr.size * scale
                assertEquals(side * side, pixels.size)
                assertTrue(left >= 4 * scale)
                assertTrue(right >= 4 * scale)
                assertTrue(kotlin.math.abs(left - right) <= 1)
                for (y in 0 until side) {
                    for (x in 0 until side) {
                        val inside = x in left until side - right && y in left until side - right
                        val dark = inside && qr[(x - left) / scale, (y - left) / scale]
                        assertEquals("Pixel ($x,$y) of $side", if (dark) BLACK else WHITE, pixels[y * side + x])
                    }
                }
            }
        }
    }

    @Test
    fun rejectsInvalidOrUndersizedRasterWithoutChangingVersion() {
        val qr = QrEncoder("http://a/")
        for (side in listOf(Int.MIN_VALUE, -1, 0, qr.size + 7, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { qr.renderPixels(side) }
        }
        assertEquals(21, qr.size)
        assertEquals(29 * 29, qr.renderPixels(29).size)
        assertEquals(193 * 193, qr.renderPixels(193).size)
    }

    private fun assertFunctionPatterns(qr: QrEncoder) {
        for ((left, top) in listOf(0 to 0, qr.size - 7 to 0, 0 to qr.size - 7)) {
            for (y in 0..6) {
                for (x in 0..6) {
                    val dark = x == 0 || x == 6 || y == 0 || y == 6 || (x in 2..4 && y in 2..4)
                    assertEquals(dark, qr[left + x, top + y])
                }
            }
        }
        for (i in 8 until qr.size - 8) {
            assertEquals(i % 2 == 0, qr[i, 6])
            assertEquals(i % 2 == 0, qr[6, i])
        }
        assertTrue(qr[8, qr.size - 8])
        readFormat(qr)
        val version = (qr.size - 17) / 4
        if (version >= 7) {
            val first = readWord(qr, List(18) { (qr.size - 11 + it % 3) to (it / 3) })
            val second = readWord(qr, List(18) { (it / 3) to (qr.size - 11 + it % 3) })
            assertEquals(first, second)
            assertEquals(version, first ushr 12)
            assertEquals(0, polynomialRemainder(first, 0x1F25))
        }
    }

    private fun readFormat(qr: QrEncoder): Int {
        val first = readWord(
            qr, listOf(
                8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8,
                7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8,
            )
        )
        val second = readWord(qr, List(15) {
            if (it < 8) (qr.size - 1 - it) to 8 else 8 to (qr.size - 15 + it)
        })
        assertEquals(first, second)
        val unmasked = first xor 0x5412
        assertEquals(0, polynomialRemainder(unmasked, 0x537))
        assertEquals("ECC M", 0, unmasked ushr 13)
        return (unmasked ushr 10) and 7
    }

    private fun readWord(qr: QrEncoder, positions: List<Pair<Int, Int>>): Int =
        positions.foldIndexed(0) { bit, value, (x, y) -> value or (if (qr[x, y]) 1 shl bit else 0) }

    private fun polynomialRemainder(value: Int, divisor: Int): Int {
        var remainder = value
        val degree = 31 - Integer.numberOfLeadingZeros(divisor)
        while (remainder >= 1 shl degree) {
            val shift = 31 - Integer.numberOfLeadingZeros(remainder) - degree
            remainder = remainder xor (divisor shl shift)
        }
        return remainder
    }

    // Limited to versions 1–6, before the extra alignment and version-information regions.
    private fun readCodewords(qr: QrEncoder): ByteArray {
        assertTrue(qr.size in 21..41)
        val mask = readFormat(qr)
        val bits = mutableListOf<Boolean>()
        var upward = true
        for (pair in qr.size - 1 downTo 1 step 2) {
            val right = if (pair <= 6) pair - 1 else pair
            for (row in 0 until qr.size) {
                val y = if (upward) qr.size - row - 1 else row
                for (x in right downTo right - 1) {
                    val alignment = qr.size > 21 && x in qr.size - 9..qr.size - 5 && y in qr.size - 9..qr.size - 5
                    val reserved = alignment || x == 6 || y == 6 || (x <= 8 && y <= 8) ||
                            (x >= qr.size - 8 && y <= 8) || (x <= 8 && y >= qr.size - 8)
                    if (reserved) continue
                    val inverted = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    bits += qr[x, y] xor inverted
                }
            }
            upward = !upward
        }
        return ByteArray(bits.size / 8) { index ->
            var value = 0
            for (bit in 0..7) value = (value shl 1) or if (bits[index * 8 + bit]) 1 else 0
            value.toByte()
        }
    }

    private fun readSingleBlockPayload(qr: QrEncoder): Pair<Int?, ByteArray> {
        assertTrue(qr.size in 21..29)
        val codewords = readCodewords(qr)
        var offset = 0
        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((codewords[offset / 8].toInt() ushr (7 - offset % 8)) and 1)
                offset++
            }
            return value
        }

        var mode = read(4)
        val eci = if (mode == 7) read(8).also { mode = read(4) } else null
        assertEquals(4, mode)
        val data = ByteArray(read(8)) { read(8).toByte() }
        assertEquals(0, read(4))
        return eci to data
    }

    private fun hasValidParity(codewords: ByteArray, degree: Int): Boolean {
        var root = 1
        repeat(degree) {
            var syndrome = 0
            for (word in codewords) syndrome = fieldProduct(syndrome, root) xor (word.toInt() and 0xFF)
            if (syndrome != 0) return false
            root = fieldProduct(root, 2)
        }
        return true
    }

    private fun fieldProduct(left: Int, right: Int): Int {
        var a = left
        var b = right
        var result = 0
        repeat(8) {
            if (b and 1 != 0) result = result xor a
            b = b ushr 1
            a = a shl 1
            if (a and 0x100 != 0) a = a xor 0x11D
        }
        return result
    }

    private companion object {
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1

        // DENSO WAVE's published ECC-M binary capacities, versions 1–40.
        // https://www.qrcode.com/en/about/version.html
        private val byteCapacities = intArrayOf(
            14, 26, 42, 62, 84, 106, 122, 152, 180, 213, 251, 287, 331, 362, 412, 450, 504, 560, 624, 666,
            711, 779, 857, 911, 997, 1059, 1125, 1190, 1264, 1370, 1452, 1538, 1628, 1722, 1809,
            1911, 1989, 2099, 2213, 2331,
        )
    }
}
