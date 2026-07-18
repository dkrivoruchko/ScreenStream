package io.screenstream.engine.internal

import io.screenstream.engine.ImageSize
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal class EncodedStorageOwner {
    private enum class TransactionState {
        Open,
        ProducerClosed,
        Faulted,
        Committed,
        Aborted,
    }

    internal enum class TransactionFailure {
        ResourceExhausted,
        InternalFailure,
    }

    internal abstract class SegmentedTransaction {
        private val segments: ArrayList<ByteArray> = ArrayList()

        private var state: TransactionState = TransactionState.Open
        private var acceptedByteCount: Int = 0
        private var committedPayload: UnpublishedEncodedPayload? = null
        private var transactionFailure: TransactionFailure? = null
        private var transactionFailureCause: Throwable? = null

        internal val byteCount: Int
            get() = acceptedByteCount

        internal val failure: TransactionFailure?
            get() = transactionFailure

        internal val failureCause: Throwable?
            get() = transactionFailureCause

        internal val isAttachable: Boolean
            get() = state == TransactionState.Open && acceptedByteCount == 0

        internal val isCommitted: Boolean
            get() = state == TransactionState.Committed

        internal val isAborted: Boolean
            get() = state == TransactionState.Aborted

        internal fun commit(imageSize: ImageSize): Boolean {
            when (state) {
                TransactionState.Faulted -> return false
                TransactionState.Open,
                TransactionState.ProducerClosed,
                    -> Unit

                TransactionState.Committed -> error("transaction is already committed")
                TransactionState.Aborted -> error("transaction is aborted")
            }

            if (imageSize.widthPx <= 0 || imageSize.heightPx <= 0) {
                recordFailure(failure = TransactionFailure.InternalFailure, cause = INVALID_IMAGE_SIZE)
                return false
            }
            if (acceptedByteCount == 0) {
                recordFailure(failure = TransactionFailure.InternalFailure, cause = EMPTY_ENCODED_PAYLOAD)
                return false
            }

            val unpublishedPayload: UnpublishedEncodedPayload = try {
                val immutableSegments = freezeSegmentsForCommit(segments)
                val payload = ImmutableEncodedPayload(segments = immutableSegments, byteCount = acceptedByteCount)
                UnpublishedEncodedPayload(payload = payload, imageSize = imageSize)
            } catch (allocationFailure: OutOfMemoryError) {
                recordFailure(TransactionFailure.ResourceExhausted, allocationFailure)
                return false
            } catch (failure: Exception) {
                recordFailure(TransactionFailure.InternalFailure, failure)
                return false
            }

            committedPayload = unpublishedPayload
            segments.clear()
            onCommitted()
            state = TransactionState.Committed
            return true
        }

        internal fun takeCommittedPayload(): UnpublishedEncodedPayload? {
            if (state != TransactionState.Committed) return null

            val payload = committedPayload ?: return null
            committedPayload = null
            return payload
        }

        internal fun abort(): Boolean {
            when (state) {
                TransactionState.Committed -> error("a committed transaction cannot be aborted")
                TransactionState.Aborted -> return false
                TransactionState.Open,
                TransactionState.ProducerClosed,
                TransactionState.Faulted,
                    -> Unit
            }

            segments.clear()
            onAborted()
            state = TransactionState.Aborted
            return true
        }

        protected fun requireWritable() {
            when (state) {
                TransactionState.Open -> Unit
                TransactionState.Faulted -> throw checkNotNull(transactionFailureCause)
                TransactionState.ProducerClosed -> failAndThrow(failure = TransactionFailure.InternalFailure, cause = PRODUCER_CLOSED)
                TransactionState.Committed -> error("transaction is committed")
                TransactionState.Aborted -> error("transaction is aborted")
            }
        }

        protected fun closeProducer() {
            when (state) {
                TransactionState.Open -> state = TransactionState.ProducerClosed
                TransactionState.ProducerClosed,
                TransactionState.Faulted,
                    -> Unit

                TransactionState.Committed -> error("transaction is committed")
                TransactionState.Aborted -> error("transaction is aborted")
            }
        }

        protected fun checkedNewTotal(additionalByteCount: Int): Int {
            if (additionalByteCount < 0 || additionalByteCount > Int.MAX_VALUE - acceptedByteCount) {
                failAndThrow(failure = TransactionFailure.ResourceExhausted, cause = ENCODED_BYTE_COUNT_EXHAUSTED)
            }
            return acceptedByteCount + additionalByteCount
        }

        protected fun acceptedByteCount(): Int = acceptedByteCount

        protected fun recordAcceptedByteCount(newByteCount: Int) {
            check(newByteCount in acceptedByteCount..Int.MAX_VALUE)
            acceptedByteCount = newByteCount
        }

        protected fun appendSegment(segment: ByteArray) {
            segments.add(segment)
        }

        protected fun failAndThrow(failure: TransactionFailure, cause: Throwable): Nothing {
            recordFailure(failure, cause)
            throw checkNotNull(transactionFailureCause)
        }

        protected abstract fun freezeSegmentsForCommit(segments: ArrayList<ByteArray>): Array<ByteArray>

        protected abstract fun onCommitted()

        protected abstract fun onAborted()

        private fun recordFailure(failure: TransactionFailure, cause: Throwable) {
            if (transactionFailure != null) return

            transactionFailure = failure
            transactionFailureCause = cause
            state = TransactionState.Faulted
        }
    }

    internal class FrameworkTransaction : SegmentedTransaction() {
        private var tail: ByteArray? = null
        private var tailByteCount: Int = 0

        internal var firstFatalWriteError: Error? = null
            private set

        internal val outputStream: OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                writeRetainingFatalError {
                    writeSingleByte(value)
                }
            }

            override fun write(source: ByteArray, offset: Int, byteCount: Int) {
                writeRetainingFatalError {
                    writeByteRange(source, offset, byteCount)
                }
            }

            override fun flush() {
                requireWritable()
            }

            override fun close() {
                closeProducer()
            }
        }

        private inline fun writeRetainingFatalError(writeAction: () -> Unit) {
            val retainedFatalWriteError = firstFatalWriteError
            if (retainedFatalWriteError != null) throw retainedFatalWriteError

            try {
                writeAction()
            } catch (writeError: Error) {
                if (writeError !is OutOfMemoryError && firstFatalWriteError == null) {
                    firstFatalWriteError = writeError
                }
                throw writeError
            }
        }

        private fun writeSingleByte(value: Int) {
            requireWritable()
            val newTotal = checkedNewTotal(1)

            var currentTail = tail
            if (currentTail == null || tailByteCount == currentTail.size) {
                val acceptedBeforeAllocation = acceptedByteCount()
                val availableLength = Int.MAX_VALUE - acceptedBeforeAllocation
                val capacity = minOf(maxOf(acceptedBeforeAllocation, 1), availableLength)

                currentTail = try {
                    ByteArray(capacity)
                } catch (allocationFailure: OutOfMemoryError) {
                    failAndThrow(TransactionFailure.ResourceExhausted, allocationFailure)
                }

                try {
                    appendSegment(currentTail)
                } catch (allocationFailure: OutOfMemoryError) {
                    failAndThrow(TransactionFailure.ResourceExhausted, allocationFailure)
                } catch (failure: Exception) {
                    failAndThrow(TransactionFailure.InternalFailure, failure)
                }

                tail = currentTail
                tailByteCount = 0
            }

            currentTail[tailByteCount] = value.toByte()
            tailByteCount += 1
            recordAcceptedByteCount(newTotal)
        }

        private fun writeByteRange(source: ByteArray, offset: Int, byteCount: Int) {
            requireWritable()
            if (offset < 0 || byteCount < 0 || offset > source.size - byteCount) {
                failAndThrow(failure = TransactionFailure.InternalFailure, cause = INVALID_SOURCE_RANGE)
            }

            val finalTotal = checkedNewTotal(byteCount)
            if (byteCount == 0) return

            var sourceOffset = offset
            var remainingByteCount = byteCount
            val currentTail = tail
            if (currentTail != null && tailByteCount < currentTail.size) {
                val copiedByteCount = minOf(remainingByteCount, currentTail.size - tailByteCount)
                try {
                    System.arraycopy(source, sourceOffset, currentTail, tailByteCount, copiedByteCount)
                } catch (allocationFailure: OutOfMemoryError) {
                    failAndThrow(TransactionFailure.ResourceExhausted, allocationFailure)
                } catch (failure: Exception) {
                    failAndThrow(TransactionFailure.InternalFailure, failure)
                }

                tailByteCount += copiedByteCount
                sourceOffset += copiedByteCount
                remainingByteCount -= copiedByteCount
                recordAcceptedByteCount(acceptedByteCount() + copiedByteCount)
            }

            if (remainingByteCount > 0) {
                val acceptedBeforeAllocation = acceptedByteCount()
                val availableLength = Int.MAX_VALUE - acceptedBeforeAllocation
                val capacity = minOf(
                    maxOf(acceptedBeforeAllocation, remainingByteCount),
                    availableLength,
                )
                val newTail = try {
                    ByteArray(capacity)
                } catch (allocationFailure: OutOfMemoryError) {
                    failAndThrow(TransactionFailure.ResourceExhausted, allocationFailure)
                }

                try {
                    System.arraycopy(source, sourceOffset, newTail, 0, remainingByteCount)
                    appendSegment(newTail)
                } catch (allocationFailure: OutOfMemoryError) {
                    failAndThrow(TransactionFailure.ResourceExhausted, allocationFailure)
                } catch (failure: Exception) {
                    failAndThrow(TransactionFailure.InternalFailure, failure)
                }

                tail = newTail
                tailByteCount = remainingByteCount
                recordAcceptedByteCount(finalTotal)
            }
        }

        override fun freezeSegmentsForCommit(segments: ArrayList<ByteArray>): Array<ByteArray> {
            val finalTail = checkNotNull(tail)
            check(segments.isNotEmpty() && segments.last() === finalTail)
            check(tailByteCount in 1..finalTail.size)

            val immutableSegments = segments.toTypedArray()
            if (tailByteCount < finalTail.size) {
                val trimmedTail = ByteArray(tailByteCount)
                System.arraycopy(finalTail, 0, trimmedTail, 0, tailByteCount)
                immutableSegments[immutableSegments.lastIndex] = trimmedTail
            }
            return immutableSegments
        }

        override fun onCommitted() {
            tail = null
            tailByteCount = 0
        }

        override fun onAborted() {
            tail = null
            tailByteCount = 0
        }
    }

    internal class NativeTransaction : SegmentedTransaction() {
        internal val segmentSink: NativeSegmentSink = NativeSegmentSink(this)

        internal fun adoptSegment(segment: ByteBuffer, byteCount: Int) {
            requireWritable()
            if (byteCount <= 0 || !segment.isDirect || segment.position() != 0 || segment.limit() != byteCount ||
                segment.remaining() != byteCount || segment.capacity() != byteCount
            ) {
                failAndThrow(failure = TransactionFailure.InternalFailure, cause = INVALID_NATIVE_SEGMENT)
            }

            val newTotal = checkedNewTotal(byteCount)
            val managedSegment = try {
                ByteArray(byteCount)
            } catch (allocationFailure: OutOfMemoryError) {
                failAndThrow(TransactionFailure.ResourceExhausted, allocationFailure)
            }

            try {
                segment.get(managedSegment)
                appendSegment(managedSegment)
            } catch (allocationFailure: OutOfMemoryError) {
                failAndThrow(TransactionFailure.ResourceExhausted, allocationFailure)
            } catch (failure: Exception) {
                failAndThrow(TransactionFailure.InternalFailure, failure)
            }

            recordAcceptedByteCount(newTotal)
        }

        override fun freezeSegmentsForCommit(segments: ArrayList<ByteArray>): Array<ByteArray> = segments.toTypedArray()

        override fun onCommitted() = Unit

        override fun onAborted() = Unit
    }

    internal class NativeSegmentSink internal constructor(
        private val transaction: NativeTransaction,
    ) {
        private fun adoptNativeSegment(segment: ByteBuffer, byteCount: Int) {
            transaction.adoptSegment(segment, byteCount)
        }
    }

    internal class ImmutableEncodedPayload internal constructor(
        private val segments: Array<ByteArray>,
        internal val byteCount: Int,
    ) {
        init {
            require(byteCount > 0)
            require(segments.isNotEmpty())

            var validatedByteCount = 0
            for (segment in segments) {
                require(segment.isNotEmpty())
                require(segment.size <= byteCount - validatedByteCount)
                validatedByteCount += segment.size
            }
            require(validatedByteCount == byteCount)
        }

        internal fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int {
            if (destinationOffset < 0 || destinationOffset > destination.size - byteCount) {
                throw IndexOutOfBoundsException("destinationOffset=$destinationOffset, byteCount=$byteCount, destinationSize=${destination.size}")
            }

            var writeOffset = destinationOffset
            for (segment in segments) {
                System.arraycopy(segment, 0, destination, writeOffset, segment.size)
                writeOffset += segment.size
            }
            return byteCount
        }

        internal fun copyBytes(): ByteArray {
            val destination = ByteArray(byteCount)
            copyTo(destination)
            return destination
        }
    }

    internal class UnpublishedEncodedPayload internal constructor(
        internal val payload: ImmutableEncodedPayload,
        internal val imageSize: ImageSize,
    ) {
        internal fun preparePublication(sequence: Long, timestampElapsedRealtimeNanos: Long): PublishedEncodedPayload =
            PublishedEncodedPayload(
                payload = payload,
                imageSize = imageSize,
                sequence = sequence,
                timestampElapsedRealtimeNanos = timestampElapsedRealtimeNanos,
            )
    }

    internal class PublishedEncodedPayload internal constructor(
        internal val payload: ImmutableEncodedPayload,
        internal val imageSize: ImageSize,
        internal val sequence: Long,
        internal val timestampElapsedRealtimeNanos: Long,
    ) {
        init {
            require(sequence > 0L)
            require(timestampElapsedRealtimeNanos >= 0L)
        }

        internal fun prepareRepeat(sequence: Long, timestampElapsedRealtimeNanos: Long): PublishedEncodedPayload =
            PublishedEncodedPayload(
                payload = payload,
                imageSize = imageSize,
                sequence = sequence,
                timestampElapsedRealtimeNanos = timestampElapsedRealtimeNanos,
            )
    }

    internal class EncodedPayloadLease internal constructor(
        publishedPayload: PublishedEncodedPayload,
    ) {
        private val released: AtomicBoolean = AtomicBoolean(false)
        private var publishedPayloadBacking: PublishedEncodedPayload? = publishedPayload

        internal val publishedPayload: PublishedEncodedPayload
            get() = checkNotNull(publishedPayloadBacking)

        internal val isReleased: Boolean
            get() = released.get()

        internal val byteCount: Int
            get() = publishedPayload.payload.byteCount

        internal val imageSize: ImageSize
            get() = publishedPayload.imageSize

        internal val sequence: Long
            get() = publishedPayload.sequence

        internal val timestampElapsedRealtimeNanos: Long
            get() = publishedPayload.timestampElapsedRealtimeNanos

        internal fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int =
            publishedPayload.payload.copyTo(destination, destinationOffset)

        internal fun copyBytes(): ByteArray = publishedPayload.payload.copyBytes()

        internal fun release(): Boolean = released.compareAndSet(false, true)

        internal fun clearConsumedPayload(expectedPayload: PublishedEncodedPayload): Boolean {
            if (!isReleased || publishedPayloadBacking !== expectedPayload) return false
            publishedPayloadBacking = null
            return true
        }
    }

    private var productionTransaction: SegmentedTransaction? = null
    private var unpublishedPayload: UnpublishedEncodedPayload? = null
    private var latestPublishedPayload: PublishedEncodedPayload? = null
    private var displacedLeasedPayload: PublishedEncodedPayload? = null
    private var activeLease: EncodedPayloadLease? = null

    internal val production: SegmentedTransaction?
        get() = productionTransaction

    internal val unpublished: UnpublishedEncodedPayload?
        get() = unpublishedPayload

    internal val latest: PublishedEncodedPayload?
        get() = latestPublishedPayload

    internal val displaced: PublishedEncodedPayload?
        get() = displacedLeasedPayload

    internal val lease: EncodedPayloadLease?
        get() = activeLease

    internal fun attachProduction(transaction: SegmentedTransaction): Boolean {
        if (productionTransaction != null || unpublishedPayload != null || !transaction.isAttachable) {
            return false
        }

        productionTransaction = transaction
        return true
    }

    internal fun replaceCommittedProduction(transaction: SegmentedTransaction): UnpublishedEncodedPayload? {
        if (productionTransaction !== transaction || !transaction.isCommitted || unpublishedPayload != null) {
            return null
        }

        val completedPayload = transaction.takeCommittedPayload() ?: return null
        productionTransaction = null
        unpublishedPayload = completedPayload
        return completedPayload
    }

    internal fun detachAbortedProduction(transaction: SegmentedTransaction): Boolean {
        if (productionTransaction !== transaction || !transaction.isAborted) return false

        productionTransaction = null
        return true
    }

    internal fun publishUnpublished(
        expectedUnpublished: UnpublishedEncodedPayload,
        expectedLatest: PublishedEncodedPayload?,
        publication: PublishedEncodedPayload,
    ): Boolean {
        if (unpublishedPayload !== expectedUnpublished || latestPublishedPayload !== expectedLatest || !publication.matches(expectedUnpublished)) {
            return false
        }

        displaceLatestIfLeased()
        unpublishedPayload = null
        latestPublishedPayload = publication
        return true
    }

    internal fun replaceLatest(expectedLatest: PublishedEncodedPayload, replacement: PublishedEncodedPayload): Boolean {
        if (latestPublishedPayload !== expectedLatest || !replacement.matches(expectedLatest)) {
            return false
        }

        displaceLatestIfLeased()
        latestPublishedPayload = replacement
        return true
    }

    internal fun retireUnpublished(expectedUnpublished: UnpublishedEncodedPayload): Boolean {
        if (unpublishedPayload !== expectedUnpublished) return false

        unpublishedPayload = null
        return true
    }

    internal fun retireLatest(expectedLatest: PublishedEncodedPayload): Boolean {
        if (latestPublishedPayload !== expectedLatest) return false

        displaceLatestIfLeased()
        latestPublishedPayload = null
        return true
    }

    internal fun attachLease(preparedLease: EncodedPayloadLease): Boolean {
        if (activeLease != null ||
            preparedLease.isReleased ||
            preparedLease.publishedPayload !== latestPublishedPayload
        ) {
            return false
        }

        activeLease = preparedLease
        return true
    }

    internal fun consumeReleasedLease(releasedLease: EncodedPayloadLease): Boolean {
        if (activeLease !== releasedLease || !releasedLease.isReleased) return false

        val consumedPayload = releasedLease.publishedPayload
        activeLease = null
        if (displacedLeasedPayload === consumedPayload) {
            displacedLeasedPayload = null
        }
        check(releasedLease.clearConsumedPayload(consumedPayload))
        return true
    }

    private fun displaceLatestIfLeased() {
        val currentLatest = latestPublishedPayload ?: return
        val currentLease = activeLease
        if (currentLease != null && currentLease.publishedPayload === currentLatest) {
            check(displacedLeasedPayload == null)
            displacedLeasedPayload = currentLatest
        }
    }

    private fun PublishedEncodedPayload.matches(unpublished: UnpublishedEncodedPayload): Boolean =
        payload === unpublished.payload && imageSize === unpublished.imageSize

    private fun PublishedEncodedPayload.matches(other: PublishedEncodedPayload): Boolean =
        payload === other.payload && imageSize === other.imageSize

    private companion object {
        private val INVALID_IMAGE_SIZE: IllegalArgumentException =
            IllegalArgumentException("imageSize must be positive")
        private val EMPTY_ENCODED_PAYLOAD: IllegalStateException =
            IllegalStateException("encoded payload must not be empty")
        private val PRODUCER_CLOSED: IllegalStateException =
            IllegalStateException("producer is closed")
        private val ENCODED_BYTE_COUNT_EXHAUSTED: OutOfMemoryError =
            OutOfMemoryError("encoded byte count exceeds Int.MAX_VALUE")
        private val INVALID_SOURCE_RANGE: IndexOutOfBoundsException =
            IndexOutOfBoundsException("encoded source range is invalid")
        private val INVALID_NATIVE_SEGMENT: IllegalArgumentException =
            IllegalArgumentException("native segment must be one exact positive direct range")
    }
}
