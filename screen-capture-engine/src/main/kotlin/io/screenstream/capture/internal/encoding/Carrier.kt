package io.screenstream.capture.internal.encoding

import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.isExactWritableRgbaCarrier
import java.nio.ByteBuffer

internal sealed interface EncodingRetirement {
    data object Closed : EncodingRetirement
    class Retained(internal val cause: Throwable?) : EncodingRetirement
}

internal enum class CarrierDisposition { Filled, Discarded, }

/**
 * Linear owner of one exact writable RGBA backing range.
 *
 * [lend] mints the sole [EncodingInput] capability for one exact Capture loan. Only that exact identity can settle the
 * loan. A discarded Capture loan returns directly to idle. A filled loan becomes ready, where it may be discarded or
 * enter Encoding; an entered Encoding loan must return before idle. Cancellation, terminal state, elapsed time, or
 * reference loss cannot fabricate ownership advancement. Native backing is detached only after its free call has
 * returned successfully.
 */
internal sealed class RgbaCarrier(
    val layout: Rgba8888Layout,
    initialBuffer: ByteBuffer?,
    pendingAllocation: Boolean,
) {
    private enum class CarrierOwnership { PendingAllocation, Idle, Capture, Ready, Encoding, RetainedAfterFreeEntry, Retired, }

    private var buffer: ByteBuffer? = initialBuffer
    private var ownership: CarrierOwnership = if (pendingAllocation) {
        check(initialBuffer == null)
        CarrierOwnership.PendingAllocation
    } else {
        checkNotNull(initialBuffer)
        CarrierOwnership.Idle
    }
    private var input: EncodingInput? = null
    private var freeFailure: Throwable? = null

    protected fun backingBuffer(): ByteBuffer = checkNotNull(buffer)

    protected fun attachReturnedBacking(returnedBuffer: ByteBuffer) {
        buffer = returnedBuffer
        ownership = CarrierOwnership.Idle
    }

    protected fun closePendingWithoutAllocation() {
        check((ownership == CarrierOwnership.PendingAllocation) && (buffer == null) && (input == null))
        ownership = CarrierOwnership.Retired
    }

    protected fun detachBackingAfterProvenRelease() {
        val ownershipAllowsDetach = (ownership == CarrierOwnership.Idle) || (ownership == CarrierOwnership.RetainedAfterFreeEntry)
        check((ownershipAllowsDetach && (input == null)))
        buffer = null
        ownership = CarrierOwnership.Retired
    }

    protected fun retainBackingForFree() {
        check((ownership == CarrierOwnership.Idle && input == null))
        ownership = CarrierOwnership.RetainedAfterFreeEntry
    }

    protected fun recordFreeFailure(cause: Exception) {
        check((ownership == CarrierOwnership.RetainedAfterFreeEntry && input == null))
        check(freeFailure == null)
        freeFailure = cause
    }

    @Synchronized
    fun lend(owner: EncodingOwner, returnPort: EncodingProductionReturnPort): EncodingInput? {
        if (ownership != CarrierOwnership.Idle || input != null || buffer == null) return null
        val writableView = backingBuffer()
        writableView.clear()
        val exactInput = EncodingInput(owner, returnPort, this, writableView, layout.byteCount)
        input = exactInput
        ownership = CarrierOwnership.Capture
        return exactInput
    }

    @Synchronized
    fun settle(expectedInput: EncodingInput, disposition: CarrierDisposition): EncodingInput? {
        if (!owns(expectedInput, CarrierOwnership.Capture)) return null
        if (disposition === CarrierDisposition.Filled) {
            backingBuffer().clear()
            ownership = CarrierOwnership.Ready
        } else {
            releaseToIdle()
        }
        return expectedInput
    }

    @Synchronized
    fun enterEncoding(expectedInput: EncodingInput): ByteBuffer? {
        if (!owns(expectedInput, CarrierOwnership.Ready)) return null
        val exactBuffer = backingBuffer()
        exactBuffer.clear()
        ownership = CarrierOwnership.Encoding
        return exactBuffer
    }

    @Synchronized
    fun releaseAfterEncodingReturn(expectedInput: EncodingInput): Boolean {
        if (!owns(expectedInput, CarrierOwnership.Encoding)) return false
        releaseToIdle()
        return true
    }

    @Synchronized
    fun discardReady(expectedInput: EncodingInput): EncodingInput? {
        if (!owns(expectedInput, CarrierOwnership.Ready)) return null
        releaseToIdle()
        return expectedInput
    }

    @Synchronized
    fun ownsCaptureLoan(expectedInput: EncodingInput): Boolean =
        owns(expectedInput, CarrierOwnership.Capture)

    @Synchronized
    fun ownsReadyLoan(expectedInput: EncodingInput): Boolean =
        owns(expectedInput, CarrierOwnership.Ready)

    @get:Synchronized
    val isIdle: Boolean
        get() = ownership == CarrierOwnership.Idle && input == null && buffer != null

    @Synchronized
    fun retireIfIdle(): EncodingRetirement {
        if (!isIdle) {
            return EncodingRetirement.Retained(freeFailure)
        }
        return retireBacking()
    }

    protected abstract fun retireBacking(): EncodingRetirement

    private fun owns(expectedInput: EncodingInput, expectedOwnership: CarrierOwnership): Boolean =
        ownership == expectedOwnership && input === expectedInput && expectedInput.carrier === this &&
                expectedInput.writableView === buffer && expectedInput.byteCount == layout.byteCount

    private fun releaseToIdle() {
        backingBuffer().clear()
        input = null
        ownership = CarrierOwnership.Idle
    }
}

internal class ManagedDirectCarrier(layout: Rgba8888Layout) : RgbaCarrier(layout, initialBuffer = null, pendingAllocation = true) {
    internal sealed interface Creation {
        class Created(
            internal val carrier: ManagedDirectCarrier,
        ) : Creation

        class Failed(
            internal val problem: ScreenCaptureProblem,
            internal val cause: Throwable,
            internal val retainedCarrier: ManagedDirectCarrier?,
        ) : Creation
    }

    private var allocationAttempted: Boolean = false

    internal fun allocateIntoPendingOwner(
        allocateDirectBuffer: (Int) -> ByteBuffer = { byteCount -> ByteBuffer.allocateDirect(byteCount) },
    ): Creation {
        check(!allocationAttempted)
        allocationAttempted = true
        val returnedBuffer = try {
            allocateDirectBuffer(layout.byteCount)
        } catch (allocationFailure: OutOfMemoryError) {
            closePendingWithoutAllocation()
            return Creation.Failed(problem = ScreenCaptureProblem.ResourceExhausted, cause = allocationFailure, retainedCarrier = null)
        } catch (failure: Exception) {
            closePendingWithoutAllocation()
            return Creation.Failed(problem = ScreenCaptureProblem.InternalFailure, cause = failure, retainedCarrier = null)
        }

        attachReturnedBacking(returnedBuffer)
        try {
            returnedBuffer.clear()
            check(returnedBuffer.isExactWritableRgbaCarrier(layout.byteCount))
        } catch (failure: Exception) {
            return Creation.Failed(problem = ScreenCaptureProblem.InternalFailure, cause = failure, retainedCarrier = this)
        }
        return Creation.Created(this)
    }

    override fun retireBacking(): EncodingRetirement {
        detachBackingAfterProvenRelease()
        return EncodingRetirement.Closed
    }
}

internal class NativeMallocCarrier(
    layout: Rgba8888Layout,
    private val nativeJpeg: NativeJpegFacade,
) : RgbaCarrier(layout, initialBuffer = null, pendingAllocation = true) {
    internal sealed interface Creation {
        class Created(internal val carrier: NativeMallocCarrier) : Creation

        class Failed(
            internal val problem: ScreenCaptureProblem,
            internal val cause: Throwable,
            internal val retainedCarrier: NativeMallocCarrier?,
        ) : Creation
    }

    private var allocationAttempted: Boolean = false

    internal fun allocateIntoPendingOwner(): Creation {
        check(!allocationAttempted)
        allocationAttempted = true
        val returnedBuffer = try {
            nativeJpeg.allocateCarrier(layout.byteCount.toLong())
        } catch (allocationFailure: OutOfMemoryError) {
            closePendingWithoutAllocation()
            return Creation.Failed(problem = ScreenCaptureProblem.ResourceExhausted, cause = allocationFailure, retainedCarrier = null)
        } catch (failure: Exception) {
            closePendingWithoutAllocation()
            return Creation.Failed(problem = ScreenCaptureProblem.InternalFailure, cause = failure, retainedCarrier = null)
        }

        attachReturnedBacking(returnedBuffer)
        val validationFailure = try {
            returnedBuffer.clear()
            check(returnedBuffer.isExactWritableRgbaCarrier(layout.byteCount))
            null
        } catch (failure: Exception) {
            failure
        }
        if (validationFailure == null) return Creation.Created(this)

        return Creation.Failed(problem = ScreenCaptureProblem.InternalFailure, cause = validationFailure, retainedCarrier = this)
    }

    override fun retireBacking(): EncodingRetirement {
        val exactBuffer = backingBuffer()
        retainBackingForFree()
        return try {
            nativeJpeg.freeCarrier(exactBuffer)
            detachBackingAfterProvenRelease()
            EncodingRetirement.Closed
        } catch (failure: Exception) {
            recordFreeFailure(failure)
            EncodingRetirement.Retained(failure)
        }
    }
}
