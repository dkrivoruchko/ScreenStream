package io.screenstream.capture.internal.encoding

import io.screenstream.capture.internal.storage.ImmutableEncodedPayload

internal sealed class ManagedEncodedTransaction {
    internal enum class State { Open, ProducerClosed, Faulted, Committed, Aborted, }

    internal enum class FailureKind { ResourceExhausted, InternalFailure, }

    private val tentativeSegments: ArrayList<ByteArray> = ArrayList()

    internal var state: State = State.Open
        private set

    internal var byteCount: Int = 0
        private set

    internal var failureKind: FailureKind? = null
        private set

    private var failureCauseSlot: Throwable? = null
    private var committedPayloadSlot: ImmutableEncodedPayload? = null

    internal val committedPayload: ImmutableEncodedPayload?
        get() = if (state == State.Committed) committedPayloadSlot else null

    internal val isFreshOpen: Boolean
        get() = (state == State.Open) && (byteCount == 0)

    internal fun hasFaultedResourceExhaustionCause(expectedCause: OutOfMemoryError): Boolean =
        ((state == State.Faulted) && (failureKind == FailureKind.ResourceExhausted) && (failureCauseSlot === expectedCause))

    internal fun commit(): Boolean {
        when (state) {
            State.Open -> {
                recordFault(kind = FailureKind.InternalFailure)
                return false
            }

            State.ProducerClosed -> Unit
            State.Faulted -> return false
            State.Committed, State.Aborted -> throw encodingFailureSignal
        }
        if (byteCount <= 0) {
            recordFault(kind = FailureKind.InternalFailure)
            return false
        }

        val frozenSegments = try {
            freezeExactSegments(tentativeSegments)
        } catch (failure: Exception) {
            recordFault(FailureKind.InternalFailure, failure)
            return false
        } ?: return false

        val payload = try {
            ImmutableEncodedPayload(segments = frozenSegments, byteCount = byteCount)
        } catch (allocationFailure: OutOfMemoryError) {
            recordFault(FailureKind.ResourceExhausted, allocationFailure)
            return false
        } catch (failure: Exception) {
            recordFault(FailureKind.InternalFailure, failure)
            return false
        }

        committedPayloadSlot = payload
        tentativeSegments.clear()
        releaseProducerReferences()
        state = State.Committed
        return true
    }

    internal fun transferCommittedPayload(expectedPayload: ImmutableEncodedPayload): Boolean {
        if (state != State.Committed || committedPayloadSlot !== expectedPayload) {
            return false
        }
        committedPayloadSlot = null
        return true
    }

    internal fun abort(): Boolean {
        when (state) {
            State.Committed -> throw encodingFailureSignal
            State.Aborted -> return false
            State.Open, State.ProducerClosed, State.Faulted -> Unit
        }

        tentativeSegments.clear()
        releaseProducerReferences()
        failureCauseSlot = null
        state = State.Aborted
        return true
    }

    protected fun requireOpenProducer() {
        when (state) {
            State.Open -> Unit
            State.Faulted -> throw checkNotNull(failureCauseSlot)
            State.ProducerClosed -> recordFaultAndThrow(kind = FailureKind.InternalFailure)
            State.Committed, State.Aborted -> throw encodingFailureSignal
        }
    }

    protected fun closeProducer() {
        when (state) {
            State.Open -> state = State.ProducerClosed
            State.ProducerClosed -> Unit
            State.Faulted -> Unit
            State.Committed, State.Aborted -> throw encodingFailureSignal
        }
    }

    protected fun checkedTotalAfter(additionalByteCount: Int): Int {
        if (additionalByteCount < 0) {
            recordFaultAndThrow(kind = FailureKind.InternalFailure)
        }
        if (additionalByteCount > Int.MAX_VALUE - byteCount) {
            recordFaultAndThrow(kind = FailureKind.ResourceExhausted)
        }
        return byteCount + additionalByteCount
    }

    protected fun recordAcceptedByteCount(newByteCount: Int) {
        check(newByteCount in byteCount..Int.MAX_VALUE)
        byteCount = newByteCount
    }

    protected fun appendSegment(segment: ByteArray) {
        check(segment.isNotEmpty())
        tentativeSegments.add(segment)
    }

    protected fun recordFaultAndThrow(kind: FailureKind, cause: Throwable = encodingFailureSignal): Nothing {
        recordFault(kind, cause)
        throw checkNotNull(failureCauseSlot)
    }

    protected abstract fun freezeExactSegments(segments: List<ByteArray>): Array<ByteArray>?

    protected open fun releaseProducerReferences() = Unit

    protected fun recordFault(kind: FailureKind, cause: Throwable = encodingFailureSignal) {
        if (state == State.Faulted) return
        check(state == State.Open || state == State.ProducerClosed)
        failureKind = kind
        failureCauseSlot = cause
        state = State.Faulted
    }

    private companion object {
        private val encodingFailureSignal: Throwable = object : RuntimeException("encoded assembly failure", null, false, false) {}
    }
}
