package io.screenstream.capture

import androidx.annotation.CheckResult
import androidx.annotation.IntRange

/**
 * Callback-scoped access to one complete encoded JPEG frame and its immutable metadata.
 *
 * This object is borrowed only for the body of the frame-consumer callback that receives it and
 * only on the exact thread running that callback. Every public frame property and copy function
 * throws [IllegalStateException] from another thread or after the callback returns. The frame has
 * identity semantics and must not be retained for later access. Retain a caller-owned byte copy
 * from [toByteArray] or [copyTo], and retain immutable metadata or scalar values only after reading
 * them inside the callback.
 *
 * A newly registered consumer may first receive the latest cached frame. That delivery preserves
 * the frame's original bytes, [outputInfo], [sequence], and [outputTimestampElapsedRealtimeNanos].
 * Production continues without a consumer, and busy consumers drop delivery opportunities, so the
 * first or next observed [sequence] may contain gaps.
 */
public class EncodedFrame private constructor(private val access: Access) {
    internal interface Access {
        fun byteCount(): Int

        fun outputInfo(): CaptureOutputInfo

        fun sequence(): Long

        fun outputTimestampElapsedRealtimeNanos(): Long

        fun copyTo(destination: ByteArray, destinationOffset: Int): Int

        fun toByteArray(): ByteArray
    }

    /**
     * Positive byte count of the complete JPEG payload.
     *
     * @throws IllegalStateException if accessed outside the receiving callback body or from a
     *     different thread.
     */
    public val byteCount: Int
        get() = access.byteCount()

    /**
     * Immutable parameters and geometry committed with this payload.
     *
     * Cached delivery retains the original value, which can be older than the current
     * [ScreenCaptureState.Active] value after a `frameRate`-only parameter update. A later fresh
     * output receives the descriptor current at that output commit.
     *
     * @throws IllegalStateException if accessed outside the receiving callback body or from a
     *     different thread.
     */
    public val outputInfo: CaptureOutputInfo
        get() = access.outputInfo()

    /**
     * Positive sequence number local to the capture session, starting at one and never wrapping or
     * repeating.
     *
     * Fresh output commits receive new values; cached delivery preserves the original value.
     * Sequence exhaustion is reported by the session as [ScreenCaptureProblem.InternalFailure].
     *
     * @throws IllegalStateException if accessed outside the receiving callback body or from a
     *     different thread.
     */
    public val sequence: Long
        get() = access.sequence()

    /**
     * Nonnegative output-commit timestamp from the elapsed-realtime clock, in nanoseconds. It is
     * neither the source-capture time nor a Unix epoch timestamp.
     *
     * Equal timestamps are valid. Fresh output commits receive a new timestamp; cached delivery
     * preserves the original timestamp.
     *
     * @throws IllegalStateException if accessed outside the receiving callback body or from a
     *     different thread.
     */
    public val outputTimestampElapsedRealtimeNanos: Long
        get() = access.outputTimestampElapsedRealtimeNanos()

    /**
     * Copies the complete JPEG payload into [destination] starting at [destinationOffset].
     *
     * The entire destination range is validated before writing. On success this writes and returns
     * exactly [byteCount] bytes; on an invalid range [destination] remains unchanged.
     *
     * @param destination caller-owned array that receives the payload.
     * @param destinationOffset first destination index, defaulting to zero.
     * @return the number of bytes copied, equal to [byteCount].
     * @throws IllegalStateException if called outside the receiving callback body or from a
     *     different thread.
     * @throws IndexOutOfBoundsException if [destinationOffset] is negative or the complete payload
     *     does not fit in [destination].
     */
    public fun copyTo(
        destination: ByteArray,
        @IntRange(from = 0) destinationOffset: Int = 0,
    ): Int =
        access.copyTo(destination, destinationOffset)

    /**
     * Returns an exact caller-owned copy of the complete JPEG payload.
     *
     * The returned array has size [byteCount] and may outlive the callback. Allocation or copy
     * failure does not mutate the engine-owned payload bytes.
     *
     * @return a newly allocated byte array containing the frame payload.
     * @throws IllegalStateException if called outside the receiving callback body or from a
     *     different thread.
     */
    @CheckResult
    public fun toByteArray(): ByteArray = access.toByteArray()

    public companion object {
        /** MIME type of every [EncodedFrame] payload. */
        public const val JPEG_MIME_TYPE: String = "image/jpeg"

        @JvmSynthetic
        internal fun create(access: Access): EncodedFrame = EncodedFrame(access)
    }
}
