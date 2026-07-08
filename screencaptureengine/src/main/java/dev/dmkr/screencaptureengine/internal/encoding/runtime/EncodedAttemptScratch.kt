package dev.dmkr.screencaptureengine.internal.encoding.runtime

import dev.dmkr.screencaptureengine.EncodedImageSink
import java.nio.ByteBuffer

/**
 * Reusable tentative sink for one encoded production attempt at a time.
 *
 * Bytes written here are not public encoded retention. A successful attempt copies out exactly the
 * accepted byte range; failed, rejected, stale, or thrown attempts discard tentative bytes. The
 * backing array may grow up to the observed high-water below [maxByteCount] and is retained only as
 * implementation scratch until [trimToSize] or owner cleanup shrinks/releases it.
 */
internal class EncodedAttemptScratch internal constructor(
    override val maxByteCount: Int,
) : EncodedImageSink {
    private var buffer = ByteArray(0)
    private var active = false
    private var rejected = false

    override var byteCount: Int = 0
        private set

    internal val wasRejected: Boolean
        get() = rejected

    init {
        require(maxByteCount > 0) { "maxByteCount must be positive, was $maxByteCount" }
    }

    internal fun begin(): EncodedAttemptScratch {
        check(!active) { "Encoded attempt scratch is already active." }
        active = true
        rejected = false
        byteCount = 0
        return this
    }

    override fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean {
        check(active) { "Encoded attempt scratch is not active." }
        require(offset >= 0) { "offset must be non-negative, was $offset" }
        require(byteCount >= 0) { "byteCount must be non-negative, was $byteCount" }
        require(offset <= source.size && byteCount <= source.size - offset) { "offset and byteCount must fit source." }
        if (rejected) return false
        if (!canAccept(byteCount)) {
            rejected = true
            return false
        }
        ensureCapacity(this.byteCount + byteCount)
        source.copyInto(destination = buffer, destinationOffset = this.byteCount, startIndex = offset, endIndex = offset + byteCount)
        this.byteCount += byteCount
        return true
    }

    override fun write(source: ByteBuffer, byteCount: Int): Boolean {
        check(active) { "Encoded attempt scratch is not active." }
        require(byteCount >= 0) { "byteCount must be non-negative, was $byteCount" }
        require(byteCount <= source.remaining()) { "byteCount must not exceed source.remaining()." }
        if (rejected) return false
        if (!canAccept(byteCount)) {
            rejected = true
            return false
        }
        ensureCapacity(this.byteCount + byteCount)
        source.get(buffer, this.byteCount, byteCount)
        this.byteCount += byteCount
        return true
    }

    internal fun finishSuccess(): ByteArray? {
        check(active) { "Encoded attempt scratch is not active." }
        active = false
        return if (!rejected && byteCount > 0 && byteCount <= maxByteCount) buffer.copyOf(byteCount) else null
    }

    internal fun finishDiscard() {
        check(active) { "Encoded attempt scratch is not active." }
        active = false
        rejected = false
        byteCount = 0
    }

    internal fun trimToSize(maxRetainedBytes: Int = 0) {
        require(maxRetainedBytes >= 0) { "maxRetainedBytes must be non-negative, was $maxRetainedBytes" }
        check(!active) { "Active encoded attempt scratch cannot be trimmed." }
        val retainedBytes = maxRetainedBytes.coerceAtMost(maxByteCount)
        if (buffer.size > retainedBytes) {
            buffer = ByteArray(retainedBytes)
        }
        byteCount = 0
        rejected = false
    }

    private fun canAccept(additionalByteCount: Int): Boolean =
        byteCount <= maxByteCount - additionalByteCount

    private fun ensureCapacity(requiredCapacity: Int) {
        if (buffer.size >= requiredCapacity) return
        var newCapacity = if (buffer.isEmpty()) INITIAL_CAPACITY_BYTES.coerceAtMost(maxByteCount) else buffer.size
        while (newCapacity < requiredCapacity) {
            newCapacity = Math.multiplyExact(newCapacity, 2).coerceAtMost(maxByteCount)
        }
        buffer = buffer.copyOf(newCapacity)
    }
}

private const val INITIAL_CAPACITY_BYTES: Int = 256
