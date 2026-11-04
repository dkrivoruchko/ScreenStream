package io.screenstream.mjpeg.http.media

import android.os.SystemClock
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeBuffer
import io.ktor.utils.io.writeFully
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.media.JpegStore.Frame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSource
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * One admitted physical media response. The writer owns acquired leases through their final byte
 * read, including failures. Runtime owns admission/cancellation and retires coherent [Progress]
 * only after the exact call job completes, even when responses share an endpoint.
 */
internal class JpegResponse(
    private val reader: JpegStore.Reader,
    val format: MediaFormat,
) {
    @Volatile
    var progress: Progress = Progress()
        private set
    private var lastObservedPublicationVersion = 0L
    private var skippedPublications = 0

    /**
     * Close frame admission and release an unclaimed initial lease. An acquired JPEG may finish;
     * runtime must cancel the call job to interrupt active I/O.
     */
    fun close() {
        reader.close()
    }

    /**
     * Use once per call; JPEG headers describe the frame pinned at admission. Closure before its
     * writer handoff can leave a truncated response with that pinned content length.
     */
    suspend fun respond(call: ApplicationCall) {
        try {
            call.respondBytesWriter(
                contentType = if (format == MediaFormat.Jpeg) {
                    ContentType.Image.JPEG
                } else {
                    ContentType.parse("multipart/x-mixed-replace; boundary=$BOUNDARY")
                },
                contentLength = if (format == MediaFormat.Jpeg) reader.initialByteCount.toLong() else null,
            ) {
                writeResponseBody(this)
            }
        } finally {
            // The engine can fail before invoking the writer, leaving its initial lease unclaimed.
            reader.close()
        }
    }

    private suspend fun writeResponseBody(channel: ByteWriteChannel) {
        if (format == MediaFormat.Jpeg) {
            currentCoroutineContext().ensureActive()
            val frame = reader.acquireFrame() ?: return
            try {
                writeJpegPayload(channel, frame)
            } finally {
                reader.releaseFrame(frame)
            }
            channel.flush()
            return
        }

        val changeRevision = reader.changeRevision
        var lastWrittenPublicationVersion = -1L
        var nextRepeatAtMillis = 0L
        channel.writeFully(PART_BEGIN)
        while (true) {
            currentCoroutineContext().ensureActive()
            // Observe before acquisition so a selection change cannot be lost before suspension.
            val observedRevision = changeRevision.value
            val frame = reader.acquireFrame()
            if (frame == null) {
                if (reader.isClosed) return
                changeRevision.first { it != observedRevision }
                continue
            }
            val publicationVersion = frame.publicationVersion
            val wrotePayload = try {
                val repeatPending = publicationVersion == lastWrittenPublicationVersion && SystemClock.elapsedRealtime() < nextRepeatAtMillis
                if (repeatPending) {
                    false
                } else {
                    updateSlowStatus(frame)
                    channel.writeFully(
                        "Content-Type: image/jpeg\r\nContent-Length: ${frame.byteCount}\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    )
                    writeJpegPayload(channel, frame)
                    true
                }
            } finally {
                // Footer and flush never read the frame; no lease survives an I/O failure or wait.
                reader.releaseFrame(frame)
            }
            if (!wrotePayload) {
                val repeatDelay = (nextRepeatAtMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1).milliseconds
                withTimeoutOrNull(repeatDelay) {
                    changeRevision.first { it != observedRevision }
                }
                continue
            }
            channel.writeFully(PART_END)
            channel.flush()
            lastWrittenPublicationVersion = publicationVersion
            nextRepeatAtMillis = SystemClock.elapsedRealtime() + 1_000L
        }
    }

    /**
     * The Source adapter reads only the valid prefix of the leased array; spare capacity is not sent.
     */
    private suspend fun writeJpegPayload(channel: ByteWriteChannel, frame: Frame) {
        frame.bytes.inputStream(0, frame.byteCount).asSource().use { channel.writeBuffer(it) }
        val previous = progress
        progress = previous.copy(
            completedJpegs = previous.completedJpegs + 1,
            completedJpegBytes = previous.completedJpegBytes + frame.byteCount,
        )
    }

    /**
     * Count skipped publications within one observed capture generation; consecutive publications reset
     * the count. A different generation resets the baseline; repeats and the logo are neutral.
     * Store versions/generation identity, never leased arrays; runtime filters obsolete generations.
     */
    private fun updateSlowStatus(frame: Frame) {
        if (frame.captureGeneration == null) return
        val previous = progress
        if (previous.observedCaptureGeneration !== frame.captureGeneration) {
            skippedPublications = 0
        } else if (lastObservedPublicationVersion == frame.publicationVersion) {
            return
        } else {
            val skippedSinceLastObservation = (frame.publicationVersion - lastObservedPublicationVersion - 1).coerceAtLeast(0)
            skippedPublications = if (skippedSinceLastObservation == 0L) {
                0
            } else {
                minOf(SLOW_FRAME_THRESHOLD.toLong(), skippedPublications + skippedSinceLastObservation).toInt()
            }
        }
        lastObservedPublicationVersion = frame.publicationVersion
        val isSlow = skippedPublications >= SLOW_FRAME_THRESHOLD
        if (previous.isSlow != isSlow || previous.observedCaptureGeneration !== frame.captureGeneration) {
            progress = previous.copy(isSlow = isSlow, observedCaptureGeneration = frame.captureGeneration)
        }
    }

    /**
     * Completed JPEG payload counts advance before footer/flush. Repeats count; framing and
     * partial writes do not.
     * [observedCaptureGeneration] remains the baseline even when [isSlow] is false. Retained frames may
     * update an obsolete baseline. Runtime displays slow status only for the current generation; store
     * and progress samples can observe adjacent moments.
     */
    data class Progress(
        val completedJpegs: Long = 0,
        val completedJpegBytes: Long = 0,
        val isSlow: Boolean = false,
        val observedCaptureGeneration: Any? = null,
    )

    private companion object {
        const val SLOW_FRAME_THRESHOLD = 5
        const val BOUNDARY = "screenstream-jpeg"
        val PART_BEGIN = "--$BOUNDARY\r\n".toByteArray(Charsets.US_ASCII)
        val PART_END = "\r\n--$BOUNDARY\r\n".toByteArray(Charsets.US_ASCII)
    }
}
