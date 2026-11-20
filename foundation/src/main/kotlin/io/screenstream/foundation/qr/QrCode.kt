package io.screenstream.foundation.qr

import android.graphics.Bitmap
import android.graphics.Color

/** Encodes the exact text at ECC M, with square modules and at least four white margin modules. */
public fun String.generateQRBitmap(sizePx: Int): Bitmap {
    val pixels = QrEncoder(this).renderPixels(sizePx)
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

/** Model 2, byte mode, versions 1–40. Non-ASCII text explicitly declares UTF-8 (ECI 26). */
internal class QrEncoder(text: String) {
    private val data = text.toByteArray(Charsets.UTF_8)
    private val utf8Eci = data.any { it < 0 }
    private val version = (1..40).firstOrNull {
        val headerBits = 4 + (if (it < 10) 8 else 16) + (if (utf8Eci) 12 else 0)
        headerBits + data.size.toLong() * 8 <= dataCodewordCount(it) * 8L
    } ?: throw IllegalArgumentException("Text exceeds QR byte capacity at ECC M")
    val size = version * 4 + 17
    private val modules = Array(size) { BooleanArray(size) }
    private val functionModules = Array(size) { BooleanArray(size) }

    init {
        drawFunctionPatterns()
        drawCodewords(addErrorCorrection(makeDataCodewords()))
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            applyMask(mask)
            drawFormatBits(mask)
            val penalty = penaltyScore()
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
            applyMask(mask)
        }
        applyMask(bestMask)
        drawFormatBits(bestMask)
    }

    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    fun renderPixels(sizePx: Int): IntArray {
        require(sizePx > 0) { "QR bitmap size must be positive" }
        val scale = sizePx / (size + 8)
        require(scale > 0) { "QR bitmap is too small for this payload" }
        val pixelCount = sizePx.toLong() * sizePx
        require(pixelCount <= Int.MAX_VALUE) { "QR bitmap is too large" }
        val inset = (sizePx - size * scale) / 2
        val pixels = IntArray(pixelCount.toInt()) { Color.WHITE }
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (modules[y][x]) {
                    for (dy in 0 until scale) {
                        val start = (inset + y * scale + dy) * sizePx + inset + x * scale
                        pixels.fill(Color.BLACK, start, start + scale)
                    }
                }
            }
        }
        return pixels
    }

    private fun makeDataCodewords(): ByteArray {
        val result = ByteArray(dataCodewordCount(version))
        var bitIndex = 0
        fun append(value: Int, count: Int) {
            for (shift in count - 1 downTo 0) {
                val index = bitIndex / 8
                result[index] = (result[index].toInt() or (((value ushr shift) and 1) shl (7 - bitIndex % 8))).toByte()
                bitIndex++
            }
        }
        if (utf8Eci) {
            append(0b0111, 4)
            append(26, 8)
        }
        append(0b0100, 4)
        append(data.size, if (version < 10) 8 else 16)
        for (byte in data) append(byte.toInt() and 0xFF, 8)
        append(0, minOf(4, result.size * 8 - bitIndex))
        append(0, (8 - bitIndex % 8) % 8)
        var padding = 0xEC
        while (bitIndex < result.size * 8) {
            append(padding, 8)
            padding = padding xor 0xEC xor 0x11
        }
        return result
    }

    private fun addErrorCorrection(codewords: ByteArray): ByteArray {
        val blockCount = errorCorrectionBlocks[version]
        val eccLength = errorCorrectionLength[version]
        val rawCodewords = rawModuleCount(version) / 8
        val shortBlockCount = blockCount - rawCodewords % blockCount
        val shortDataLength = rawCodewords / blockCount - eccLength
        val divisor = reedSolomonDivisor(eccLength)
        var dataOffset = 0
        val dataBlocks = Array(blockCount) { blockIndex ->
            val length = shortDataLength + if (blockIndex < shortBlockCount) 0 else 1
            codewords.copyOfRange(dataOffset, dataOffset + length).also { dataOffset += length }
        }
        val eccBlocks = Array(blockCount) { errorCorrectionRemainder(dataBlocks[it], divisor) }
        val result = ByteArray(rawCodewords)
        var offset = 0
        for (i in 0..shortDataLength) {
            for (block in dataBlocks) {
                if (i < block.size) result[offset++] = block[i]
            }
        }
        for (i in 0 until eccLength) {
            for (block in eccBlocks) result[offset++] = block[i]
        }
        return result
    }

    private fun errorCorrectionRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val remainder = ByteArray(divisor.size)
        for (byte in data) {
            val factor = (byte.toInt() xor remainder[0].toInt()) and 0xFF
            remainder.copyInto(remainder, 0, 1)
            remainder[remainder.lastIndex] = 0
            for (i in remainder.indices) {
                remainder[i] = (remainder[i].toInt() xor multiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return remainder
    }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        if (version > 1) {
            val count = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val positions = IntArray(count)
            positions[0] = 6
            for (i in count - 1 downTo 1) positions[i] = size - 7 - (count - 1 - i) * step
            for (i in positions.indices) {
                for (j in positions.indices) {
                    if ((i == 0 && j == 0) || (i == 0 && j == count - 1) || (i == count - 1 && j == 0)) continue
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            setFunction(positions[i] + dx, positions[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                        }
                    }
                }
            }
        }
        drawFormatBits(0)
        if (version >= 7) {
            var remainder = version
            repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
            val bits = (version shl 12) or remainder
            for (i in 0 until 18) {
                val a = size - 11 + i % 3
                val b = i / 3
                setFunction(a, b, bit(bits, i))
                setFunction(b, a, bit(bits, i))
            }
        }
    }

    private fun drawFinder(x: Int, y: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) {
                    val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFunction(xx, yy, distance != 2 && distance != 4)
                }
            }
        }
    }

    private fun drawFormatBits(mask: Int) {
        // ECC M has format bits 00, leaving the three mask bits as the format payload.
        var remainder = mask
        repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
        val bits = ((mask shl 10) or remainder) xor 0x5412
        for (i in 0..5) setFunction(8, i, bit(bits, i))
        setFunction(8, 7, bit(bits, 6))
        setFunction(8, 8, bit(bits, 7))
        setFunction(7, 8, bit(bits, 8))
        for (i in 9..14) setFunction(14 - i, 8, bit(bits, i))
        for (i in 0..7) setFunction(size - 1 - i, 8, bit(bits, i))
        for (i in 8..14) setFunction(8, size - 15 + i, bit(bits, i))
        setFunction(8, size - 8, true)
    }

    private fun setFunction(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        functionModules[y][x] = true
    }

    private fun drawCodewords(codewords: ByteArray) {
        var bitIndex = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vertical in 0 until size) {
                val y = if ((right + 1) and 2 == 0) size - 1 - vertical else vertical
                for (j in 0..1) {
                    val x = right - j
                    if (!functionModules[y][x] && bitIndex < codewords.size * 8) {
                        modules[y][x] = bit(codewords[bitIndex / 8].toInt(), 7 - bitIndex % 8)
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
        check(bitIndex == codewords.size * 8)
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    else -> error("Invalid QR mask")
                }
                if (invert && !functionModules[y][x]) modules[y][x] = !modules[y][x]
            }
        }
    }

    private fun penaltyScore(): Int {
        var result = 0
        // Runs and finder-like 1:1:3:1:1 patterns, including the surrounding white margin.
        for (transpose in listOf(false, true)) {
            for (line in 0 until size) {
                var runColor = false
                var runLength = 0
                val history = IntArray(7)
                for (i in 0 until size) {
                    val dark = if (transpose) modules[i][line] else modules[line][i]
                    if (dark == runColor) {
                        runLength++
                        if (runLength == 5) result += 3 else if (runLength > 5) result++
                    } else {
                        addRunHistory(runLength, history)
                        if (!runColor) result += finderPatternCount(history) * 40
                        runColor = dark
                        runLength = 1
                    }
                }
                if (runColor) {
                    addRunHistory(runLength, history)
                    runLength = 0
                }
                addRunHistory(runLength + size, history)
                result += finderPatternCount(history) * 40
            }
        }
        var darkCount = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dark = modules[y][x]
                if (dark) darkCount++
                if (x < size - 1 && y < size - 1 && dark == modules[y][x + 1] &&
                    dark == modules[y + 1][x] && dark == modules[y + 1][x + 1]
                ) result += 3
            }
        }
        val total = size * size
        result += ((kotlin.math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1) * 10
        return result
    }

    private fun addRunHistory(length: Int, history: IntArray) {
        val runLength = length + if (history[0] == 0) size else 0
        history.copyInto(history, 1, 0, history.size - 1)
        history[0] = runLength
    }

    private fun finderPatternCount(history: IntArray): Int {
        val n = history[1]
        if (n == 0 || history[2] != n || history[3] != n * 3 || history[4] != n || history[5] != n) return 0
        return (if (history[0] >= n * 4 && history[6] >= n) 1 else 0) +
                (if (history[6] >= n * 4 && history[0] >= n) 1 else 0)
    }

    private fun bit(value: Int, index: Int): Boolean = (value ushr index) and 1 != 0

    private fun rawModuleCount(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val alignments = version / 7 + 2
            result -= (25 * alignments - 10) * alignments - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private fun dataCodewordCount(version: Int): Int =
        rawModuleCount(version) / 8 - errorCorrectionLength[version] * errorCorrectionBlocks[version]

    private fun reedSolomonDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (i in result.indices) {
                result[i] = multiply(result[i].toInt() and 0xFF, root).toByte()
                if (i + 1 < result.size) result[i] = (result[i].toInt() xor result[i + 1].toInt()).toByte()
            }
            root = multiply(root, 2)
        }
        return result
    }

    /** Multiplication in GF(256), modulo x^8 + x^4 + x^3 + x^2 + 1. */
    private fun multiply(x: Int, y: Int): Int {
        var result = 0
        for (i in 7 downTo 0) {
            result = (result shl 1) xor ((result ushr 7) * 0x11D)
            result = result xor (((y ushr i) and 1) * x)
        }
        return result
    }

    private companion object {
        // ECC M only, indexed by version. Index zero is unused.
        private val errorCorrectionLength = intArrayOf(
            -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
            26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        )
        private val errorCorrectionBlocks = intArrayOf(
            -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
            17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
        )
    }
}
