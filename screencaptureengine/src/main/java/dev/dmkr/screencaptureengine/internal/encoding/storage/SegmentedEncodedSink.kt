@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.encoding.storage

import dev.dmkr.screencaptureengine.EncodedImageSink
import dev.dmkr.screencaptureengine.internal.policy.ScreenCaptureEnginePolicyDefaults
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

internal sealed interface EncodedSegmentAllocation {
    class Granted(
        val bytes: ByteArray,
    ) : EncodedSegmentAllocation

    data object Denied : EncodedSegmentAllocation
}

/** Performs the synchronous memory check and allocation for one encoded-segment growth. */
internal fun interface EncodedSegmentAllocator {
    fun allocate(byteCount: Int): EncodedSegmentAllocation
}

internal enum class EncodedSinkRejection {
    CallerCap,
    ResourceExhausted,
}

internal sealed interface SegmentedSinkFinish {
    data class Completed(
        val payload: ImmutableEncodedPayload,
    ) : SegmentedSinkFinish

    data object Empty : SegmentedSinkFinish

    data class Rejected(
        val rejection: EncodedSinkRejection,
    ) : SegmentedSinkFinish

    data object Invalidated : SegmentedSinkFinish
}

/** One invocation-confined segmented sink whose accepted bytes remain tentative until finish. */
internal class SegmentedEncodedSink internal constructor(
    private val callerMaxByteCount: Int,
    private val segmentAllocator: EncodedSegmentAllocator,
    private val releaseObserver: EncodedPayloadReleaseObserver,
    private val segmentByteCount: Int = ScreenCaptureEnginePolicyDefaults.MAX_ENCODED_SEGMENT_BYTES,
) : EncodedImageSink {
    private val lock = Any()
    private var accumulator: EncodedPayloadAccumulator? = EncodedPayloadAccumulator()

    @Volatile
    private var invocationThread: Thread? = null

    @Volatile
    private var invocationReturned = false

    @Volatile
    private var invalidated = false
    private var finished = false
    private var acceptedByteCount = 0

    @Volatile
    private var rejection: EncodedSinkRejection? = null

    override val byteCount: Int
        get() {
            checkInvocationAccess()
            return acceptedByteCount
        }

    override val maxByteCount: Int
        get() {
            checkInvocationAccess()
            return callerMaxByteCount
        }

    internal val firstRejection: EncodedSinkRejection?
        get() = rejection

    init {
        require(callerMaxByteCount > 0) { "maxByteCount must be positive." }
        require(segmentByteCount in 1..ScreenCaptureEnginePolicyDefaults.MAX_ENCODED_SEGMENT_BYTES) {
            "Segment size must be within the encoded-segment policy bound."
        }
    }

    internal fun enterEncoderInvocation(): SegmentedEncodedSink = synchronized(lock) {
        check(invocationThread == null && !invocationReturned && !invalidated && !finished) {
            "Encoded sink cannot enter another invocation."
        }
        invocationThread = Thread.currentThread()
        this
    }

    internal fun exitEncoderInvocation() {
        synchronized(lock) {
            check(invocationThread === Thread.currentThread()) {
                "Only the encoder invocation thread may record its return."
            }
            check(!invocationReturned) { "Encoder invocation already returned." }
            invocationReturned = true
            invocationThread = null
        }
    }

    /** Fences every later provider access. It does not release storage before provider return. */
    internal fun invalidate() {
        synchronized(lock) {
            check(!finished) { "Finished encoded sink cannot be invalidated." }
            invalidated = true
            if (invocationThread == null) invocationReturned = true
        }
    }

    override fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean {
        checkInvocationAccess()
        checkArrayRange(source, offset, byteCount)
        return writeFromInvocation(byteCount) { destination, destinationOffset, length, sourceOffset ->
            source.copyInto(
                destination = destination,
                destinationOffset = destinationOffset,
                startIndex = offset + sourceOffset,
                endIndex = offset + sourceOffset + length,
            )
        }
    }

    override fun write(source: ByteBuffer, byteCount: Int): Boolean {
        checkInvocationAccess()
        if (byteCount < 0) throw IllegalArgumentException("byteCount must be non-negative.")
        if (source.remaining() < byteCount) throw BufferUnderflowException()

        val sourceStart = source.position()
        val sourceView = source.duplicate()
        val accepted = writeFromInvocation(byteCount) { destination, destinationOffset, length, _ ->
            sourceView.get(destination, destinationOffset, length)
        }
        if (accepted) source.position(sourceStart + byteCount)
        return accepted
    }

    internal fun finish(): SegmentedSinkFinish = synchronized(lock) {
        check(invocationReturned) { "Encoded sink cannot finish before provider return." }
        check(!finished) { "Encoded sink is already finished." }
        finished = true

        val outcome = when {
            rejection != null -> SegmentedSinkFinish.Rejected(checkNotNull(rejection))
            invalidated -> SegmentedSinkFinish.Invalidated
            acceptedByteCount == 0 -> SegmentedSinkFinish.Empty
            else -> SegmentedSinkFinish.Completed(
                ImmutableEncodedPayload(
                    accumulator = checkNotNull(accumulator),
                    byteCount = acceptedByteCount,
                    releaseObserver = releaseObserver,
                ),
            )
        }
        accumulator = null
        acceptedByteCount = 0
        outcome
    }

    private fun writeFromInvocation(
        additionalByteCount: Int,
        copy: (destination: ByteArray, destinationOffset: Int, length: Int, sourceOffset: Int) -> Unit,
    ): Boolean {
        rejection?.let { return false }
        if (additionalByteCount == 0) return true
        if (additionalByteCount > callerMaxByteCount - acceptedByteCount) {
            latchRejection(EncodedSinkRejection.CallerCap)
            return false
        }

        val preparedSegments = try {
            prepareGrowth(additionalByteCount)
        } catch (_: OutOfMemoryError) {
            latchRejection(EncodedSinkRejection.ResourceExhausted)
            return false
        } ?: return false
        try {
            checkNotNull(accumulator).appendAllocations(preparedSegments)
        } catch (_: OutOfMemoryError) {
            latchRejection(EncodedSinkRejection.ResourceExhausted)
            return false
        }

        checkNotNull(accumulator).write(additionalByteCount, copy)
        acceptedByteCount += additionalByteCount
        return true
    }

    private fun prepareGrowth(additionalByteCount: Int): List<ByteArray>? {
        val currentAccumulator = checkNotNull(accumulator)
        val tailRemaining = currentAccumulator.tailWritableByteCount
        var requiredCapacity = (additionalByteCount - tailRemaining).coerceAtLeast(0)
        if (requiredCapacity == 0) return emptyList()

        val prepared = ArrayList<ByteArray>()
        var remainingCapacity = callerMaxByteCount - currentAccumulator.allocatedByteCount
        while (requiredCapacity > 0) {
            val allocationSize = minOf(segmentByteCount, remainingCapacity)
            check(allocationSize > 0) { "Accepted encoded bytes must have representable segment capacity." }
            val allocation = try {
                segmentAllocator.allocate(allocationSize)
            } catch (_: OutOfMemoryError) {
                latchRejection(EncodedSinkRejection.ResourceExhausted)
                return null
            }
            val bytes = when (allocation) {
                EncodedSegmentAllocation.Denied -> {
                    latchRejection(EncodedSinkRejection.ResourceExhausted)
                    return null
                }

                is EncodedSegmentAllocation.Granted -> allocation.bytes
            }
            check(bytes.size == allocationSize) {
                "Encoded segment allocator must return exactly the requested capacity."
            }
            prepared += bytes
            remainingCapacity -= allocationSize
            requiredCapacity -= minOf(requiredCapacity, allocationSize)
        }
        return prepared
    }

    private fun latchRejection(value: EncodedSinkRejection) {
        if (rejection == null) rejection = value
    }

    private fun checkInvocationAccess() {
        check(!invalidated && !invocationReturned && invocationThread === Thread.currentThread()) {
            "Encoded sink is accessible only from its active encoder invocation thread."
        }
    }
}

private fun checkArrayRange(source: ByteArray, offset: Int, byteCount: Int) {
    val endOffset = offset.toLong() + byteCount.toLong()
    if (offset < 0 || byteCount < 0 || endOffset > source.size.toLong()) {
        throw IndexOutOfBoundsException("Source range is outside the byte array.")
    }
}
