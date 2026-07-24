package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.ScreenCaptureProblem
import java.nio.ByteBuffer

/** Exact tightly packed RGBA layout shared by capture readback and JPEG encoding. */
internal class RgbaLayout private constructor(
    internal val widthPx: Int,
    internal val heightPx: Int,
    internal val rowByteCount: Int,
    internal val byteCount: Int,
) {
    internal fun hasShape(widthPx: Int, heightPx: Int, byteCount: Int): Boolean =
        this.widthPx == widthPx && this.heightPx == heightPx && this.byteCount == byteCount

    internal companion object {
        internal fun create(widthPx: Int, heightPx: Int): RgbaLayout {
            require(widthPx > 0)
            require(heightPx > 0)
            val rowByteCount = Math.multiplyExact(RGBA_BYTES_PER_PIXEL.toLong(), widthPx.toLong())
            require(rowByteCount <= Int.MAX_VALUE.toLong())
            val byteCount = Math.multiplyExact(rowByteCount, heightPx.toLong())
            require(byteCount in 1..Int.MAX_VALUE.toLong())
            return RgbaLayout(widthPx, heightPx, rowByteCount.toInt(), byteCount.toInt())
        }

        private const val RGBA_BYTES_PER_PIXEL: Int = 4
    }
}

internal sealed interface ManagedCarrierCreation {
    class Created internal constructor(
        internal val carrier: ManagedDirectCarrier,
    ) : ManagedCarrierCreation

    class Failed internal constructor(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable,
    ) : ManagedCarrierCreation
}

internal sealed interface NativeCarrierCreation {
    class Created internal constructor(
        internal val carrier: NativeMallocCarrier,
    ) : NativeCarrierCreation

    class Failed internal constructor(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable,
        internal val retainedCarrier: NativeMallocCarrier?,
    ) : NativeCarrierCreation
}

internal sealed interface CarrierRetirement {
    data object Closed : CarrierRetirement

    class Retained internal constructor(
        internal val cause: Throwable?,
    ) : CarrierRetirement
}

/** Identity-bearing capture/encode loan for either legal raw-carrier implementation. */
internal class RgbaCarrierLease internal constructor(
    internal val carrier: RgbaCarrier,
    internal val record: ProductionRecord,
)

/** Physical raw-carrier mechanics only; policy and currentness remain outside this owner. */
internal sealed interface RgbaCarrier {
    val layout: RgbaLayout

    fun borrowForCapture(record: ProductionRecord): RgbaCarrierLease?

    fun writableBuffer(expectedLease: RgbaCarrierLease): ByteBuffer?

    fun markFrameReady(expectedLease: RgbaCarrierLease): Boolean

    fun releaseAfterCaptureReturn(expectedLease: RgbaCarrierLease): Boolean

    fun enterEncoding(expectedLease: RgbaCarrierLease): ByteBuffer?

    fun releaseAfterEncodingReturn(expectedLease: RgbaCarrierLease): Boolean

    fun releaseReadyBeforeEntry(expectedLease: RgbaCarrierLease): Boolean

    fun ownsReadyLease(expectedLease: RgbaCarrierLease): Boolean

    fun isIdle(): Boolean

    fun retireIfIdle(): CarrierRetirement

    fun isRetired(): Boolean
}

private enum class CarrierOwnership {
    PendingAllocation,
    Idle,
    Capture,
    Ready,
    Encoding,
    RetainedAfterFreeEntry,
    Retired,
}

/** Shared one-carrier/one-lease state machine; subclasses alone settle their backing storage. */
internal abstract class StatefulRgbaCarrier(
    final override val layout: RgbaLayout,
    initialBuffer: ByteBuffer?,
    pendingAllocation: Boolean,
) : RgbaCarrier {
    private var buffer: ByteBuffer? = initialBuffer
    private var ownership: CarrierOwnership = if (pendingAllocation) {
        check(initialBuffer == null)
        CarrierOwnership.PendingAllocation
    } else {
        checkNotNull(initialBuffer)
        CarrierOwnership.Idle
    }
    private var lease: RgbaCarrierLease? = null
    private var retainedFreeCause: Throwable? = null

    protected fun backingBuffer(): ByteBuffer = checkNotNull(buffer)

    /** First managed action after a normal allocation return; field mutation is allocation-free. */
    protected fun attachReturnedBacking(returnedBuffer: ByteBuffer) {
        check(ownership == CarrierOwnership.PendingAllocation && buffer == null && lease == null)
        buffer = returnedBuffer
        ownership = CarrierOwnership.Idle
    }

    /** A throwing allocation call returned no native owner under its frozen JNI contract. */
    protected fun closePendingWithoutAllocation() {
        check(ownership == CarrierOwnership.PendingAllocation && buffer == null && lease == null)
        ownership = CarrierOwnership.Retired
    }

    protected fun detachBackingAfterProvenRelease() {
        check(ownership == CarrierOwnership.Idle && lease == null)
        buffer = null
        ownership = CarrierOwnership.Retired
    }

    protected fun retainBackingAfterEnteredFree(cause: Throwable) {
        check(ownership == CarrierOwnership.Idle && lease == null)
        check(retainedFreeCause == null)
        retainedFreeCause = cause
        ownership = CarrierOwnership.RetainedAfterFreeEntry
    }

    @Synchronized
    final override fun borrowForCapture(record: ProductionRecord): RgbaCarrierLease? {
        if (ownership != CarrierOwnership.Idle || lease != null || buffer == null) return null
        val exactLease = RgbaCarrierLease(this, record)
        lease = exactLease
        ownership = CarrierOwnership.Capture
        exactBuffer().resetExactRange(layout.byteCount)
        return exactLease
    }

    @Synchronized
    final override fun writableBuffer(expectedLease: RgbaCarrierLease): ByteBuffer? {
        if (!owns(expectedLease, CarrierOwnership.Capture)) return null
        return exactBuffer().also { it.resetExactRange(layout.byteCount) }
    }

    @Synchronized
    final override fun markFrameReady(expectedLease: RgbaCarrierLease): Boolean {
        if (!owns(expectedLease, CarrierOwnership.Capture)) return false
        exactBuffer().resetExactRange(layout.byteCount)
        ownership = CarrierOwnership.Ready
        return true
    }

    @Synchronized
    final override fun releaseAfterCaptureReturn(expectedLease: RgbaCarrierLease): Boolean {
        if (!owns(expectedLease, CarrierOwnership.Capture)) return false
        releaseToIdle()
        return true
    }

    @Synchronized
    final override fun enterEncoding(expectedLease: RgbaCarrierLease): ByteBuffer? {
        if (!owns(expectedLease, CarrierOwnership.Ready)) return null
        val exactBuffer = exactBuffer().also { it.resetExactRange(layout.byteCount) }
        ownership = CarrierOwnership.Encoding
        return exactBuffer
    }

    @Synchronized
    final override fun releaseAfterEncodingReturn(expectedLease: RgbaCarrierLease): Boolean {
        if (!owns(expectedLease, CarrierOwnership.Encoding)) return false
        releaseToIdle()
        return true
    }

    @Synchronized
    final override fun releaseReadyBeforeEntry(expectedLease: RgbaCarrierLease): Boolean {
        if (!owns(expectedLease, CarrierOwnership.Ready)) return false
        releaseToIdle()
        return true
    }

    @Synchronized
    final override fun ownsReadyLease(expectedLease: RgbaCarrierLease): Boolean =
        owns(expectedLease, CarrierOwnership.Ready)

    @Synchronized
    final override fun isIdle(): Boolean =
        ownership == CarrierOwnership.Idle && lease == null && buffer != null

    @Synchronized
    final override fun retireIfIdle(): CarrierRetirement {
        if (!isIdle()) {
            return CarrierRetirement.Retained(retainedFreeCause)
        }
        return retireBacking()
    }

    @Synchronized
    final override fun isRetired(): Boolean =
        ownership == CarrierOwnership.Retired && lease == null && buffer == null

    protected abstract fun retireBacking(): CarrierRetirement

    private fun owns(expectedLease: RgbaCarrierLease, expectedOwnership: CarrierOwnership): Boolean =
        ownership == expectedOwnership && lease === expectedLease && expectedLease.carrier === this

    private fun exactBuffer(): ByteBuffer = backingBuffer()

    private fun releaseToIdle() {
        exactBuffer().resetExactRange(layout.byteCount)
        lease = null
        ownership = CarrierOwnership.Idle
    }
}

/** Managed direct carrier: retirement is the final engine-reference drop, never a physical-free claim. */
internal class ManagedDirectCarrier private constructor(
    layout: RgbaLayout,
    buffer: ByteBuffer,
) : StatefulRgbaCarrier(layout, buffer, pendingAllocation = false) {
    override fun retireBacking(): CarrierRetirement {
        detachBackingAfterProvenRelease()
        return CarrierRetirement.Closed
    }

    internal companion object {
        internal fun create(layout: RgbaLayout): ManagedCarrierCreation = try {
            val buffer = ByteBuffer.allocateDirect(layout.byteCount)
            checkExactCarrierView(buffer, layout.byteCount)
            buffer.resetExactRange(layout.byteCount)
            ManagedCarrierCreation.Created(ManagedDirectCarrier(layout, buffer))
        } catch (allocationFailure: OutOfMemoryError) {
            ManagedCarrierCreation.Failed(ScreenCaptureProblem.ResourceExhausted, allocationFailure)
        } catch (failure: Exception) {
            ManagedCarrierCreation.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }
    }
}

/**
 * Owns the exact native malloc allocation represented by [backingBuffer]. Only a normal
 * `nativeFreeCarrier` return permits detachment; every other outcome retains the view and authority evidence.
 */
internal class NativeMallocCarrier private constructor(
    layout: RgbaLayout,
) : StatefulRgbaCarrier(layout, initialBuffer = null, pendingAllocation = true) {
    private var allocationCalled: Boolean = false
    private var freeEntered: Boolean = false

    /**
     * Invokes the one native allocation from this already-rooted pending owner. A normal return is attached
     * before validation or creation of any returned result/runtime wrapper.
     */
    internal fun allocateIntoPendingOwner(): NativeCarrierCreation {
        check(!allocationCalled)
        allocationCalled = true
        val returnedBuffer = try {
            NativeJpegProcess.allocateCarrier(layout.byteCount.toLong())
        } catch (allocationFailure: OutOfMemoryError) {
            closePendingWithoutAllocation()
            return NativeCarrierCreation.Failed(
                ScreenCaptureProblem.ResourceExhausted,
                allocationFailure,
                retainedCarrier = null,
            )
        } catch (failure: Exception) {
            closePendingWithoutAllocation()
            return NativeCarrierCreation.Failed(
                ScreenCaptureProblem.InternalFailure,
                failure,
                retainedCarrier = null,
            )
        } catch (fatal: Throwable) {
            closePendingWithoutAllocation()
            throw fatal
        }

        attachReturnedBacking(returnedBuffer)
        val validationFailure = try {
            checkExactCarrierView(returnedBuffer, layout.byteCount)
            returnedBuffer.resetExactRange(layout.byteCount)
            null
        } catch (failure: Exception) {
            failure
        }
        if (validationFailure == null) return NativeCarrierCreation.Created(this)

        // The returned allocation stays attached. Free is a separate entered stage.
        return NativeCarrierCreation.Failed(
            problem = ScreenCaptureProblem.InternalFailure,
            cause = validationFailure,
            retainedCarrier = this,
        )
    }

    override fun retireBacking(): CarrierRetirement {
        check(!freeEntered)
        freeEntered = true
        val exactBuffer = backingBuffer()
        return try {
            NativeJpegProcess.freeCarrier(exactBuffer)
            detachBackingAfterProvenRelease()
            CarrierRetirement.Closed
        } catch (failure: Exception) {
            retainBackingAfterEnteredFree(failure)
            CarrierRetirement.Retained(failure)
        } catch (fatal: Throwable) {
            retainBackingAfterEnteredFree(fatal)
            throw fatal
        }
    }

    internal companion object {
        /** Must be rooted by a concrete allocation task before JNI entry. */
        internal fun newPending(layout: RgbaLayout): NativeMallocCarrier = NativeMallocCarrier(layout)
    }
}

private fun checkExactCarrierView(buffer: ByteBuffer, byteCount: Int) {
    check(buffer.isDirect)
    check(!buffer.isReadOnly)
    check(buffer.capacity() == byteCount)
}

private fun ByteBuffer.resetExactRange(byteCount: Int) {
    clear()
    limit(byteCount)
    position(0)
}
