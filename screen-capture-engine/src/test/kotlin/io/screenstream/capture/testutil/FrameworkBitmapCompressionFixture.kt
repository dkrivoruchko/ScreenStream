package io.screenstream.capture.testutil

import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Scoped public-API fault fixture for one Framework Bitmap allocation and its compression stream. */
internal class FrameworkBitmapCompressionFixture(
    private val widthPx: Int,
    private val heightPx: Int,
) : AutoCloseable {
    internal val partialBytes: ByteArray = byteArrayOf(0x50, 0x41, 0x52, 0x54)
    internal val successfulJpegBytes: ByteArray

    private val bitmap: Bitmap
    private val compressionAttempt = AtomicInteger()
    private var closed = false
    private var staticMockCleanupRequired = false

    internal val compressionAttemptCount: Int
        get() = compressionAttempt.get()

    init {
        var bitmapCandidate: Bitmap? = null
        try {
            val jpegSource = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            try {
                jpegSource.eraseColor(0xFF2070B0.toInt())
                successfulJpegBytes = ByteArrayOutputStream().use { output ->
                    check(jpegSource.compress(Bitmap.CompressFormat.JPEG, 80, output))
                    output.toByteArray()
                }
            } finally {
                jpegSource.recycle()
            }

            bitmapCandidate = spyk(Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888))
            bitmap = bitmapCandidate
            every { bitmap.compress(Bitmap.CompressFormat.JPEG, any(), any()) } answers {
                val output = thirdArg<OutputStream>()
                if (compressionAttempt.getAndIncrement() == 0) {
                    output.write(partialBytes)
                    false
                } else {
                    output.write(successfulJpegBytes)
                    true
                }
            }
            staticMockCleanupRequired = true
            mockkStatic(Bitmap::class)
            every { Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888) } returns bitmap
        } catch (failure: Throwable) {
            var cleanupFailure: Throwable? = null
            if (staticMockCleanupRequired) {
                try {
                    unmockkStatic(Bitmap::class)
                } catch (cleanup: Throwable) {
                    cleanupFailure = cleanup
                } finally {
                    staticMockCleanupRequired = false
                }
            }
            try {
                bitmapCandidate?.let { candidate -> if (!candidate.isRecycled) candidate.recycle() }
            } catch (cleanup: Throwable) {
                if (cleanupFailure == null) cleanupFailure = cleanup else cleanupFailure.addSuppressed(cleanup)
            }
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var cleanupFailure: Throwable? = null
        if (staticMockCleanupRequired) {
            try {
                unmockkStatic(Bitmap::class)
            } catch (failure: Throwable) {
                cleanupFailure = failure
            } finally {
                staticMockCleanupRequired = false
            }
        }
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (failure: Throwable) {
            if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
        }
        cleanupFailure?.let { throw it }
    }
}
