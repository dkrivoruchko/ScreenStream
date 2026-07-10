@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.encoding.storage

/** Observes the one final engine-ownership release of an immutable encoded segment chain. */
internal fun interface EncodedPayloadReleaseObserver {
    fun onReleased(allocatedByteCount: Int)
}

/** A move-only owner of one immutable, non-empty encoded segment chain. */
internal class ImmutableEncodedPayload internal constructor(
    accumulator: EncodedPayloadAccumulator,
    byteCount: Int,
    releaseObserver: EncodedPayloadReleaseObserver,
) {
    private val logicalByteCount: Int = byteCount
    private val retainedAllocatedByteCount: Int
    private val reference: EncodedPayloadReference
    private var consumed = false

    internal val byteCount: Int
        get() = synchronized(this) {
            check(!consumed) { "Encoded payload ownership was already consumed." }
            logicalByteCount
        }

    internal val allocatedByteCount: Int
        get() = synchronized(this) {
            check(!consumed) { "Encoded payload ownership was already consumed." }
            retainedAllocatedByteCount
        }

    init {
        val storage = accumulator.freeze(byteCount)
        retainedAllocatedByteCount = storage.allocatedByteCount
        reference = EncodedPayloadReference.create(
            storage = storage,
            byteCount = byteCount,
            allocatedByteCount = retainedAllocatedByteCount,
            releaseObserver = releaseObserver,
        )
    }

    /** Releases an untransferred payload. Returns false after an earlier release or transfer. */
    internal fun release(): Boolean {
        synchronized(this) {
            if (consumed) return false
            consumed = true
        }
        reference.release()
        return true
    }

    internal fun moveToSlot(): EncodedPayloadReference {
        synchronized(this) {
            check(!consumed) { "Encoded payload ownership was already consumed." }
            consumed = true
        }
        return reference
    }

}

/** One independently releasable immutable payload lease. */
internal class ImmutableEncodedPayloadLease internal constructor(
    private val reference: EncodedPayloadReference,
) {
    private var released = false

    internal val byteCount: Int
        get() = synchronized(this) {
            check(!released) { "Encoded payload lease is released." }
            reference.byteCount
        }

    internal val allocatedByteCount: Int
        get() = synchronized(this) {
            check(!released) { "Encoded payload lease is released." }
            reference.allocatedByteCount
        }

    internal fun copyTo(
        destination: ByteArray,
        destinationOffset: Int = 0,
    ): Int = synchronized(this) {
        check(!released) { "Encoded payload lease is released." }
        reference.copyTo(destination, destinationOffset)
    }

    internal fun copyBytes(): ByteArray = synchronized(this) {
        check(!released) { "Encoded payload lease is released." }
        ByteArray(reference.byteCount).also { reference.copyTo(it, destinationOffset = 0) }
    }

    /** Releases this lease exactly once. */
    internal fun release(): Boolean {
        synchronized(this) {
            if (released) return false
            released = true
        }
        reference.release()
        return true
    }
}

internal class EncodedPayloadReference private constructor(
    private val owner: Owner,
) {
    internal val byteCount: Int
        get() = owner.byteCount

    internal val allocatedByteCount: Int
        get() = owner.allocatedByteCount

    internal fun acquireLease(): ImmutableEncodedPayloadLease? {
        if (!owner.tryRetain()) return null
        return ImmutableEncodedPayloadLease(this)
    }

    internal fun copyTo(destination: ByteArray, destinationOffset: Int): Int =
        owner.copyTo(destination, destinationOffset)

    internal fun release() {
        owner.release()
    }

    private class Owner(
        storage: EncodedPayloadAccumulator,
        byteCount: Int,
        allocatedByteCount: Int,
        private val releaseObserver: EncodedPayloadReleaseObserver,
    ) {
        private val logicalByteCount = byteCount
        private var referenceCount = 1
        private var storage: EncodedPayloadAccumulator? = storage
        private var retainedAllocatedByteCount = allocatedByteCount

        val byteCount: Int
            get() = synchronized(this) {
                check(referenceCount > 0) { "Encoded payload storage is released." }
                logicalByteCount
            }

        val allocatedByteCount: Int
            get() = synchronized(this) {
                check(referenceCount > 0) { "Encoded payload storage is released." }
                retainedAllocatedByteCount
            }

        fun tryRetain(): Boolean = synchronized(this) {
            if (referenceCount == 0) return@synchronized false
            check(referenceCount < Int.MAX_VALUE) { "Encoded payload reference count exhausted." }
            referenceCount += 1
            true
        }

        fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
            val currentStorage = synchronized(this) {
                check(referenceCount > 0) { "Encoded payload storage is released." }
                checkNotNull(storage)
            }
            checkDestinationRange(destination, destinationOffset, logicalByteCount)
            currentStorage.copyTo(destination, destinationOffset)
            return logicalByteCount
        }

        fun release() {
            val releasedAllocatedByteCount = synchronized(this) {
                check(referenceCount > 0) { "Encoded payload reference count underflow." }
                referenceCount -= 1
                if (referenceCount == 0) {
                    storage = null
                    retainedAllocatedByteCount.also { retainedAllocatedByteCount = 0 }
                } else {
                    null
                }
            }
            if (releasedAllocatedByteCount != null) releaseObserver.onReleased(releasedAllocatedByteCount)
        }
    }

    internal companion object {
        internal fun create(
            storage: EncodedPayloadAccumulator,
            byteCount: Int,
            allocatedByteCount: Int,
            releaseObserver: EncodedPayloadReleaseObserver,
        ): EncodedPayloadReference = EncodedPayloadReference(
            Owner(
                storage = storage,
                byteCount = byteCount,
                allocatedByteCount = allocatedByteCount,
                releaseObserver = releaseObserver,
            ),
        )
    }
}

/** Mutable tentative storage whose segment representation never escapes this file. */
internal class EncodedPayloadAccumulator {
    private val segments = ArrayList<EncodedPayloadSegment>()
    private var allocationByteCount = 0
    private var writeSegmentIndex = 0
    private var frozen = false

    internal val allocatedByteCount: Int
        get() = allocationByteCount

    internal val tailWritableByteCount: Int
        get() = segments.lastOrNull()?.let { segment -> segment.bytes.size - segment.byteCount } ?: 0

    internal fun appendAllocations(allocations: List<ByteArray>) {
        check(!frozen) { "Encoded payload storage is immutable after transfer." }
        if (allocations.isEmpty()) return
        segments.ensureCapacity(segments.size + allocations.size)
        allocations.forEach { bytes ->
            segments += EncodedPayloadSegment(bytes)
            allocationByteCount += bytes.size
        }
    }

    internal fun write(
        byteCount: Int,
        copy: (destination: ByteArray, destinationOffset: Int, length: Int, sourceOffset: Int) -> Unit,
    ) {
        check(!frozen) { "Encoded payload storage is immutable after transfer." }
        var sourceOffset = 0
        var remaining = byteCount
        while (remaining > 0) {
            val segment = segments[writeSegmentIndex]
            val writable = minOf(remaining, segment.bytes.size - segment.byteCount)
            if (writable > 0) {
                copy(segment.bytes, segment.byteCount, writable, sourceOffset)
                segment.byteCount += writable
                sourceOffset += writable
                remaining -= writable
            }
            if (segment.byteCount == segment.bytes.size) writeSegmentIndex += 1
        }
    }

    internal fun freeze(byteCount: Int): EncodedPayloadAccumulator {
        check(!frozen) { "Encoded payload storage was already transferred." }
        require(byteCount > 0) { "An immutable encoded payload must be non-empty." }
        require(segments.isNotEmpty()) { "An immutable encoded payload must own at least one segment." }
        require(segments.all { segment -> segment.byteCount > 0 }) {
            "Immutable encoded payload segments must be non-empty."
        }
        require(segments.sumOf { segment -> segment.byteCount } == byteCount) {
            "Accepted encoded bytes must equal the transferred segment bytes."
        }
        require(segments.sumOf { segment -> segment.bytes.size } == allocatedByteCount) {
            "Encoded segment capacity must equal the retained allocation charge."
        }
        frozen = true
        return this
    }

    internal fun copyTo(destination: ByteArray, destinationOffset: Int) {
        check(frozen) { "Tentative encoded payload storage cannot be copied." }
        var outputOffset = destinationOffset
        segments.forEach { segment ->
            segment.bytes.copyInto(
                destination = destination,
                destinationOffset = outputOffset,
                startIndex = 0,
                endIndex = segment.byteCount,
            )
            outputOffset += segment.byteCount
        }
    }
}

private class EncodedPayloadSegment(
    val bytes: ByteArray,
    byteCount: Int = 0,
) {
    var byteCount: Int = byteCount
        set(value) {
            require(value in 0..bytes.size) { "Encoded segment byte count must fit its storage." }
            field = value
        }

    init {
        require(bytes.isNotEmpty()) { "Encoded segment storage must be non-empty." }
        this.byteCount = byteCount
    }
}

private fun checkDestinationRange(destination: ByteArray, destinationOffset: Int, byteCount: Int) {
    val endOffset = destinationOffset.toLong() + byteCount.toLong()
    if (destinationOffset < 0 || endOffset > destination.size.toLong()) {
        throw IndexOutOfBoundsException("Encoded payload does not fit the destination range.")
    }
}
