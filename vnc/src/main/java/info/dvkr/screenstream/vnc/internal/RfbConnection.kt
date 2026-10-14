package info.dvkr.screenstream.vnc.internal

import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readShort
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import io.ktor.utils.io.writeShort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Deflater

internal class RfbConnection(
    private val parentScope: CoroutineScope,
    private val socket: Socket,
    private val address: String,
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val maxFps: () -> Int,
    private val zlibEnabled: () -> Boolean,
    private val onClosed: () -> Unit
) {
    private companion object {
        private const val RFB_VERSION = "RFB 003.008\n"
        private const val SERVER_NAME = "ScreenStream VNC"
        private const val ENCODING_RAW = 0
        private const val ENCODING_ZLIB = 6
        private const val ENCODING_ZRLE = 16
        private const val ZRLE_TILE_SIZE = 64
        private const val MAX_CUT_TEXT_SKIP = 1 shl 20
        private const val FIRST_BITMAP_TIMEOUT_MS = 3000L

        private val VERSION_REGEX = Regex("RFB\\s\\d{3}\\.\\d{3}")

        private val PIXEL_FORMAT: ByteArray = byteArrayOf(
            32,          // Bits per pixel
            24,          // Depth
            0,           // Big endian flag
            1,           // True colour flag
            0x00, 0xFF.toByte(),  // Red max
            0x00, 0xFF.toByte(),  // Green max
            0x00, 0xFF.toByte(),  // Blue max
            16,          // Red shift
            8,           // Green shift
            0,           // Blue shift
            0, 0, 0      // Padding
        )
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private var runJob: Job? = null

    @Volatile
    private var clientEncodings: Set<Int> = emptySet()

    @Volatile
    private var updateRequested = false

    // Whether the most recent FBU request was incremental. A non-incremental
    // request must be answered with the current frame even if nothing changed.
    @Volatile
    private var lastRequestIncremental = false

    @Volatile
    private var clientPixelFormat = PixelFormat.DEFAULT
    private var encoder = PixelEncoder(clientPixelFormat)
    private var zrleDeflater: Deflater? = null
    private var zlibDeflater: Deflater? = null
    private var zrleBuffer: ByteArray? = null
    private var pixelBuffer: IntArray? = null
    private val deflateScratch = ByteArray(65536)
    private val deflateOut = ByteArrayOutputStream(65536)
    private val paletteMap = HashMap<Int, Int>(16)
    private val paletteColors = IntArray(16)

    internal fun start() {
        runJob = scope.launch { run() }
    }

    internal suspend fun awaitTermination() {
        runJob?.join()
    }

    private suspend fun run() {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel()
        try {
            handshake(input, output)
            clientInit(input)
            val (width, height) = serverInit(output)
            XLog.i(getLog("RfbConnection", "Client connected: $address, ${width}x$height"))
            scope.launch { frameLoop(output, width, height) }
            clientLoop(input)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            XLog.d(getLog("RfbConnection", "Client $address disconnected: ${throwable.message}"))
        } finally {
            close()
        }
    }

    private suspend fun handshake(input: ByteReadChannel, output: ByteWriteChannel) {
        output.writeFully(RFB_VERSION.toByteArray(Charsets.US_ASCII))
        output.flush()

        val clientVersion = ByteArray(12)
        input.readFully(clientVersion)
        val versionString = String(clientVersion, Charsets.US_ASCII).trim()
        if (!VERSION_REGEX.matches(versionString)) {
            throw IOException("Unsupported client version '$versionString'")
        }
        XLog.d(getLog("RfbConnection.handshake", "Client version: $versionString"))

        output.writeByte(0x01.toByte()) // Number of security types
        output.writeByte(0x01.toByte()) // Security type: None
        output.flush()

        val chosenType = input.readByte().toInt() and 0xFF
        if (chosenType != 0x01) {
            sendSecurityFailure(output, "Unsupported security type $chosenType")
            throw IOException("Client chose unsupported security type $chosenType")
        }

        output.writeInt(0) // Security result: OK
        output.flush()
    }

    private suspend fun sendSecurityFailure(output: ByteWriteChannel, reason: String) {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        output.writeInt(1) // Security result: FAIL
        output.writeInt(reasonBytes.size)
        output.writeFully(reasonBytes)
        output.flush()
    }

    private suspend fun clientInit(input: ByteReadChannel) {
        input.readByte() // Shared flag (ignored, we are view-only)
    }

    private suspend fun serverInit(output: ByteWriteChannel): Pair<Int, Int> {
        val bitmap = awaitRealBitmap()
        val width = bitmap.width.toShort()
        val height = bitmap.height.toShort()

        output.writeShort(width)
        output.writeShort(height)
        output.writeFully(PIXEL_FORMAT)
        val name = SERVER_NAME.toByteArray(Charsets.UTF_8)
        output.writeInt(name.size)
        output.writeFully(name)
        output.flush()

        return bitmap.width to bitmap.height
    }

    // The RFB server keeps accepting connections before and while screen
    // capture is running. Wait here, without a timeout, until the capture
    // pipeline delivers a real frame, so the ServerInit message never
    // advertises the 1x1 placeholder bitmap as the framebuffer size.
    private suspend fun awaitRealBitmap(): Bitmap {
        var bitmap = bitmapStateFlow.value
        while (scope.isActive && (bitmap.width <= 1 || bitmap.height <= 1)) {
            delay(25)
            bitmap = bitmapStateFlow.value
        }
        return bitmap
    }

    private suspend fun clientLoop(input: ByteReadChannel) {
        while (scope.isActive) {
            when (val type = input.readByte().toInt() and 0xFF) {
                0x00 -> { // SetPixelFormat
                    input.readFully(ByteArray(3))
                    val format = ByteArray(16)
                    input.readFully(format)
                    clientPixelFormat = parsePixelFormat(format)
                    encoder = PixelEncoder(clientPixelFormat)
                }

                0x02 -> { // SetEncodings
                    input.readByte() // Padding
                    val count = input.readShort().toInt() and 0xFFFF
                    val encodings = HashSet<Int>(count)
                    repeat(count) { encodings.add(input.readInt()) }
                    clientEncodings = encodings
                }

                0x03 -> { // FramebufferUpdateRequest
                    val incremental = input.readByte().toInt() and 0xFF != 0
                    input.readShort() // X
                    input.readShort() // Y
                    input.readShort() // Width
                    input.readShort() // Height
                    lastRequestIncremental = incremental
                    updateRequested = true
                }

                0x04 -> { // KeyEvent (ignored, view-only)
                    input.readByte() // Down flag
                    input.readByte() // Padding
                    input.readByte() // Padding
                    input.readInt()  // Key
                }

                0x05 -> { // PointerEvent (ignored, view-only)
                    input.readByte()
                    input.readShort()
                    input.readShort()
                }

                0x06 -> { // ClientCutText (ignored)
                    input.readFully(ByteArray(3)) // Padding
                    val length = input.readInt().toLong() and 0xFFFFFFFFL
                    val skip = length.coerceAtMost(MAX_CUT_TEXT_SKIP.toLong()).toInt()
                    input.readFully(ByteArray(skip))
                    if (skip.toLong() < length) throw IOException("ClientCutText too large ($length bytes)")
                }

                else -> throw IOException("Unknown message type 0x${type.toString(16)} from VNC client")
            }
        }
    }

    private suspend fun frameLoop(output: ByteWriteChannel, width: Int, height: Int) {
        var lastBitmap: Bitmap? = null
        while (scope.isActive) {
            if (lastBitmap == null) {
                // Wait for the client to negotiate encodings so the first frame uses the right encoding.
                val deadline = System.currentTimeMillis() + FIRST_BITMAP_TIMEOUT_MS
                while (scope.isActive && clientEncodings.isEmpty() && !updateRequested && System.currentTimeMillis() < deadline) {
                    delay(10)
                }
                if (!scope.isActive) break
            }
            val bitmap = bitmapStateFlow.value
            if (bitmap !== lastBitmap) {
                lastBitmap = bitmap
                if (bitmap.width != width || bitmap.height != height) {
                    // The RFB framebuffer size is fixed for the lifetime of the
                    // connection (RFC 6143 has no resize message). Close this
                    // connection so the viewer reconnects and receives a fresh
                    // ServerInit with the new dimensions. Cancelling the scope
                    // routes through the normal shutdown path (run()'s finally
                    // closes the socket); throwing here would escape this child
                    // coroutine and crash the process.
                    XLog.i(getLog("RfbConnection", "Framebuffer resized to ${bitmap.width}x${bitmap.height}, closing connection"))
                    scope.cancel()
                    return
                }
                sendFrameUpdate(output, bitmap, width, height)
                updateRequested = false
            } else if (updateRequested && !lastRequestIncremental) {
                // A non-incremental request must be answered with the current state
                // even when the bitmap has not changed.
                sendFrameUpdate(output, bitmap, width, height)
                updateRequested = false
            } else if (updateRequested) {
                // Incremental request with nothing new to send: answer with an
                // empty update as required by RFC 6143.
                sendEmptyUpdate(output)
                updateRequested = false
            }
            val fps = maxFps().coerceIn(1, 60)
            delay(1000L / fps)
        }
    }

    private suspend fun sendEmptyUpdate(output: ByteWriteChannel) {
        output.writeByte(0x00.toByte()) // FramebufferUpdate
        output.writeByte(0x00.toByte()) // Padding
        output.writeShort(0.toShort()) // No rectangles
        output.flush()
    }

    private suspend fun sendFrameUpdate(output: ByteWriteChannel, bitmap: Bitmap, width: Int, height: Int) {
        val pixels = pixelBuffer?.takeIf { it.size == width * height } ?: IntArray(width * height).also { pixelBuffer = it }
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val encodings = clientEncodings
        val compressionEnabled = zlibEnabled()
        val useZRLE = compressionEnabled && ENCODING_ZRLE in encodings
        val useZlib = !useZRLE && compressionEnabled && ENCODING_ZLIB in encodings

        val tile = ZRLE_TILE_SIZE
        val rows = (height + tile - 1) / tile
        val encoding = when {
            useZRLE -> ENCODING_ZRLE
            useZlib -> ENCODING_ZLIB
            else -> ENCODING_RAW
        }

        output.writeByte(0x00.toByte()) // FramebufferUpdate
        output.writeByte(0x00.toByte()) // Padding
        output.writeShort(rows.toShort()) // Number of rectangles, one per tile row

        val startNanos = System.nanoTime()
        var totalBytes = 0
        for (row in 0 until rows) {
            val y = row * tile
            val rectHeight = minOf(tile, height - y)
            output.writeShort(0.toShort()) // X
            output.writeShort(y.toShort()) // Y
            output.writeShort(width.toShort())
            output.writeShort(rectHeight.toShort())
            output.writeInt(encoding)
            val payload = when {
                useZRLE -> flushZRLE(buildZRLEStrip(pixels, width, height, row))
                useZlib -> flushZlib(buildRasterStrip(pixels, width, y, rectHeight))
                else -> buildRasterStrip(pixels, width, y, rectHeight)
            }
            totalBytes += payload.size
            output.writeInt(payload.size)
            output.writeFully(payload)
        }
        val encodeMillis = (System.nanoTime() - startNanos) / 1_000_000L
        XLog.d(getLog("RfbConnection", "Frame ${width}x${height}: ${rows} rects, ${totalBytes} bytes in ${encodeMillis} ms"))
        output.flush()
    }

    private fun buildZRLEStrip(pixels: IntArray, width: Int, height: Int, tileRow: Int): ByteArray {
        val tile = ZRLE_TILE_SIZE
        val bytePerPixel = encoder.cpixelBytes
        val cols = (width + tile - 1) / tile
        val ty = tileRow * tile
        val th = minOf(tile, height - ty)
        val capacity = cols * (1 + tile * tile * bytePerPixel)
        val zrle = zrleBuffer ?: ByteArray(capacity).also { zrleBuffer = it }
        var offset = 0
        var tx = 0
        while (tx < width) {
            val tw = minOf(tile, width - tx)
            offset = buildTile(pixels, width, tx, ty, tw, th, zrle, offset)
            tx += tile
        }
        return zrle.copyOf(offset)
    }

    // RFC 6143 subencodings 0 (raw), 1 (solid) and 2-16 (packed palette).
    // Phone UIs are mostly flat colors, so solid/palette tiles shrink the
    // payload dramatically compared to always-raw encoding.
    private fun buildTile(pixels: IntArray, width: Int, tx: Int, ty: Int, tw: Int, th: Int, out: ByteArray, startOffset: Int): Int {
        paletteMap.clear()
        var unique = 0
        var raw = false
        rowLoop@ for (row in 0 until th) {
            var index = (ty + row) * width + tx
            for (col in 0 until tw) {
                val pixel = pixels[index++]
                if (!paletteMap.containsKey(pixel)) {
                    if (unique >= 16) {
                        raw = true
                        break@rowLoop
                    }
                    paletteMap[pixel] = unique
                    paletteColors[unique] = pixel
                    unique++
                }
            }
        }

        var offset = startOffset
        if (raw) {
            out[offset++] = 0x00.toByte()
            for (row in 0 until th) {
                var index = (ty + row) * width + tx
                for (col in 0 until tw) offset = encoder.writePixelZRLE(pixels[index++], out, offset)
            }
        } else if (unique == 1) {
            out[offset++] = 0x01.toByte()
            offset = encoder.writePixelZRLE(paletteColors[0], out, offset)
        } else {
            out[offset++] = unique.toByte()
            for (i in 0 until unique) offset = encoder.writePixelZRLE(paletteColors[i], out, offset)
            val bitsPerIndex = when (unique) {
                2 -> 1
                in 3..4 -> 2
                else -> 4
            }
            for (row in 0 until th) {
                var index = (ty + row) * width + tx
                var byte = 0
                var bitPos = 0
                for (col in 0 until tw) {
                    byte = (byte shl bitsPerIndex) or paletteMap.getValue(pixels[index++])
                    bitPos += bitsPerIndex
                    if (bitPos == 8) {
                        out[offset++] = byte.toByte()
                        byte = 0
                        bitPos = 0
                    }
                }
                if (bitPos > 0) out[offset++] = (byte shl (8 - bitPos)).toByte()
            }
        }
        return offset
    }

    // RFC 6143 7.7.6: a single zlib "stream" object is used for the whole RFB
    // connection, flushed (not finished, not reset) to a byte boundary at the
    // end of each ZRLE rectangle. Clients like TigerVNC/TightVNC keep one
    // persistent inflater for the connection and Z_SYNC_FLUSH per rectangle.
    private fun flushZRLE(tileBytes: ByteArray): ByteArray {
        val deflater = zrleDeflater ?: Deflater(Deflater.DEFAULT_COMPRESSION).also { zrleDeflater = it }
        return compress(deflater, tileBytes)
    }

    private fun flushZlib(raster: ByteArray): ByteArray {
        val deflater = zlibDeflater ?: Deflater(Deflater.DEFAULT_COMPRESSION).also { zlibDeflater = it }
        return compress(deflater, raster)
    }

    private fun compress(deflater: Deflater, input: ByteArray): ByteArray {
        deflateOut.reset()
        deflater.setInput(input)
        while (!deflater.needsInput()) {
            val size = deflater.deflate(deflateScratch, 0, deflateScratch.size, Deflater.NO_FLUSH)
            if (size == 0) break
            deflateOut.write(deflateScratch, 0, size)
        }
        while (true) {
            val size = deflater.deflate(deflateScratch, 0, deflateScratch.size, Deflater.SYNC_FLUSH)
            if (size == 0) break
            deflateOut.write(deflateScratch, 0, size)
        }
        return deflateOut.toByteArray()
    }

    private fun buildRasterStrip(pixels: IntArray, width: Int, y: Int, height: Int): ByteArray {
        val bytePerPixel = encoder.rasterBpp
        val raster = ByteArray(height * width * bytePerPixel)
        var offset = 0
        var index = y * width
        val end = index + height * width
        while (index < end) offset = encoder.writePixel(pixels[index++], raster, offset)
        return raster
    }

    private fun parsePixelFormat(bytes: ByteArray): PixelFormat {
        val bitsPerPixel = bytes[0].toInt() and 0xFF
        val depth = bytes[1].toInt() and 0xFF
        val bigEndian = (bytes[2].toInt() and 0xFF) != 0
        val trueColour = (bytes[3].toInt() and 0xFF) != 0
        val redMax = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        val greenMax = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        val blueMax = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
        val redShift = bytes[10].toInt() and 0xFF
        val greenShift = bytes[11].toInt() and 0xFF
        val blueShift = bytes[12].toInt() and 0xFF
        return PixelFormat(bitsPerPixel, depth, bigEndian, trueColour, redMax, greenMax, blueMax, redShift, greenShift, blueShift)
    }

    private fun close() {
        runCatching { socket.close() }
        zrleDeflater = null
        zlibDeflater = null
        zrleBuffer = null
        scope.cancel()
        onClosed()
    }
}

// RFC 6143 CPIXEL: 32bpp formats with depth <= 24 whose RGB bits fit in
// 3 bytes use 3-byte pixels in ZRLE. All per-pixel transforms and byte
// ordering are precomputed here so encoding 1080x2280 frames stays fast.
private class PixelEncoder(pf: PixelFormat) {
    val rasterBpp: Int = (pf.bitsPerPixel / 8).coerceIn(1, 4)
    val cpixelBytes: Int = cpixelSize(pf)

    private val identity =
        pf.redMax == 255 && pf.greenMax == 255 && pf.blueMax == 255 &&
            pf.redShift == 16 && pf.greenShift == 8 && pf.blueShift == 0

    private val redLUT = buildLUT(pf.redMax, pf.redShift)
    private val greenLUT = buildLUT(pf.greenMax, pf.greenShift)
    private val blueLUT = buildLUT(pf.blueMax, pf.blueShift)

    val rasterShifts: IntArray
    val cpixelShifts: IntArray

    init {
        rasterShifts = when (rasterBpp) {
            1 -> intArrayOf(0)
            2 -> if (pf.bigEndian) intArrayOf(8, 0) else intArrayOf(0, 8)
            3 -> if (pf.bigEndian) intArrayOf(16, 8, 0) else intArrayOf(0, 8, 16)
            else -> if (pf.bigEndian) intArrayOf(24, 16, 8, 0) else intArrayOf(0, 8, 16, 24)
        }
        cpixelShifts = when (cpixelBytes) {
            1 -> intArrayOf(0)
            2 -> rasterShifts
            3 -> if (pf.bigEndian) {
                if (lowCpixel(pf)) intArrayOf(24, 16, 8) else intArrayOf(16, 8, 0)
            } else {
                if (lowCpixel(pf)) intArrayOf(0, 8, 16) else intArrayOf(8, 16, 24)
            }
            else -> rasterShifts
        }
    }

    fun writePixel(pixel: Int, out: ByteArray, offset: Int): Int = writeBytes(pixel, rasterShifts, out, offset)

    fun writePixelZRLE(pixel: Int, out: ByteArray, offset: Int): Int = writeBytes(pixel, cpixelShifts, out, offset)

    private fun writeBytes(pixel: Int, shifts: IntArray, out: ByteArray, offset: Int): Int {
        val value = if (identity) {
            pixel and 0xFFFFFF
        } else {
            redLUT[(pixel ushr 16) and 0xFF] or greenLUT[(pixel ushr 8) and 0xFF] or blueLUT[pixel and 0xFF]
        }
        var o = offset
        for (shift in shifts) out[o++] = ((value ushr shift) and 0xFF).toByte()
        return o
    }

    companion object {
        private fun cpixelSize(pf: PixelFormat): Int = when {
            pf.bitsPerPixel <= 8 -> 1
            pf.bitsPerPixel <= 16 -> 2
            else -> if (pf.depth <= 24 && (lowCpixel(pf) || highCpixel(pf))) 3 else 4
        }

        private fun lowCpixel(pf: PixelFormat): Boolean {
            val fitsLow = maxPixel(pf) < (1 shl 24)
            val fitsHigh = (maxPixel(pf) and 0xFF) == 0
            return (fitsLow && !pf.bigEndian) || (fitsHigh && pf.bigEndian)
        }

        private fun highCpixel(pf: PixelFormat): Boolean {
            val fitsLow = maxPixel(pf) < (1 shl 24)
            val fitsHigh = (maxPixel(pf) and 0xFF) == 0
            return (fitsLow && pf.bigEndian) || (fitsHigh && !pf.bigEndian)
        }

        private fun maxPixel(pf: PixelFormat): Int =
            (pf.redMax shl pf.redShift) or (pf.greenMax shl pf.greenShift) or (pf.blueMax shl pf.blueShift)

        private fun buildLUT(maxValue: Int, shift: Int): IntArray {
            val lut = IntArray(256)
            for (i in 0 until 256) lut[i] = (i * maxValue / 255) shl shift
            return lut
        }
    }
}

private data class PixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    val bigEndian: Boolean,
    val trueColour: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int
) {
    companion object {
        val DEFAULT = PixelFormat(bitsPerPixel = 32, depth = 24, bigEndian = false, trueColour = true, redMax = 255, greenMax = 255, blueMax = 255, redShift = 16, greenShift = 8, blueShift = 0)
    }
}
