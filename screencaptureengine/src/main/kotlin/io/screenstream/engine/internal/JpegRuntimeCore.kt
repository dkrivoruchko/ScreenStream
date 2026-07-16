package io.screenstream.engine.internal

import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.settlement.jpegEnteredOperationSafetyNanos
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class NativeJpegHealth {
    Enabled,
    Disabled,
}

internal sealed class JpegRuntimeProduct(
    internal val carrier: RgbaCarrier,
) {
    internal abstract val nativeHealth: NativeJpegHealth
    internal abstract val initialLease: RgbaCarrierLease

    internal class NativeEnabled private constructor(
        internal val nativeCarrier: NativeMallocCarrier,
    ) : JpegRuntimeProduct(nativeCarrier) {
        override val nativeHealth: NativeJpegHealth = NativeJpegHealth.Enabled
        override val initialLease: NativeMallocCarrierLease = NativeMallocCarrierLease.create(nativeCarrier)

        internal companion object {
            internal fun create(carrier: NativeMallocCarrier): NativeEnabled = NativeEnabled(carrier)
        }
    }

    internal class FrameworkOnNativeCarrier private constructor(
        internal val nativeCarrier: NativeMallocCarrier,
    ) : JpegRuntimeProduct(nativeCarrier) {
        override val nativeHealth: NativeJpegHealth = NativeJpegHealth.Disabled
        override val initialLease: NativeMallocCarrierLease = NativeMallocCarrierLease.create(nativeCarrier)

        internal companion object {
            internal fun create(carrier: NativeMallocCarrier): FrameworkOnNativeCarrier =
                FrameworkOnNativeCarrier(carrier)
        }
    }

    internal class FrameworkOnManagedCarrier private constructor(
        internal val managedCarrier: ManagedDirectCarrier,
    ) : JpegRuntimeProduct(managedCarrier) {
        override val nativeHealth: NativeJpegHealth = NativeJpegHealth.Disabled
        override val initialLease: ManagedDirectCarrierLease = ManagedDirectCarrierLease.create(managedCarrier)

        internal companion object {
            internal fun create(carrier: ManagedDirectCarrier): FrameworkOnManagedCarrier =
                FrameworkOnManagedCarrier(carrier)
        }
    }
}

internal sealed class RgbaCarrier(
    internal val byteCount: Int,
) : OperationReturnedOwner {
    private enum class AttachmentState {
        Empty,
        Attached,
        Ready,
        Rejected,
    }

    private class AttachmentShell {
        var state: AttachmentState = AttachmentState.Empty
        var buffer: ByteBuffer? = null
    }

    private val attachment: AttachmentShell = AttachmentShell()
    private val leaseGate: ReentrantLock = ReentrantLock(false)

    private var installedProduct: JpegRuntimeProduct? = null
    private var attachedLease: RgbaCarrierLease? = null
    private var retainedOperationLease: RgbaCarrierLease? = null
    private var enteredUses: Int = 0
    private var admissionOpen: Boolean = false
    private var detached: Boolean = false
    private var admittedFreeOccurrence: NativeCarrierFreeOccurrence? = null

    internal fun attachReturnedBufferLocked(settlementGate: ReentrantLock, returnedBuffer: ByteBuffer): Boolean {
        check(settlementGate.isHeldByCurrentThread)
        if (attachment.state != AttachmentState.Empty) return false

        attachment.buffer = returnedBuffer
        attachment.state = AttachmentState.Attached
        return true
    }

    internal fun completeAttachmentLocked(settlementGate: ReentrantLock, ready: Boolean): Boolean {
        check(settlementGate.isHeldByCurrentThread)
        if (attachment.state != AttachmentState.Attached) return false

        attachment.state = if (ready) AttachmentState.Ready else AttachmentState.Rejected
        return true
    }

    internal fun install(product: JpegRuntimeProduct): Boolean = leaseGate.withLock {
        if (product.carrier !== this || attachment.state != AttachmentState.Ready || detached || retainedOperationLease != null) {
            return@withLock false
        }
        if (installedProduct != null && installedProduct !== product) return@withLock false

        installedProduct = product
        admissionOpen = true
        true
    }

    internal fun rebindInstalledProductAndLease(
        expectedProduct: JpegRuntimeProduct,
        replacementProduct: JpegRuntimeProduct,
        expectedLease: RgbaCarrierLease,
    ): Boolean = leaseGate.withLock {
        if (!admissionOpen || expectedProduct.carrier !== this || replacementProduct.carrier !== this ||
            installedProduct !== expectedProduct || attachedLease !== expectedLease || detached ||
            retainedOperationLease != null || enteredUses != 0 ||
            !expectedLease.rebindAttachedProductLocked(expectedProduct, replacementProduct)
        ) {
            return@withLock false
        }

        installedProduct = replacementProduct
        true
    }

    internal fun acquireLease(expectedProduct: JpegRuntimeProduct, candidate: RgbaCarrierLease): Boolean = leaseGate.withLock {
        if (!admissionOpen || detached || installedProduct !== expectedProduct || candidate.carrier !== this ||
            attachedLease != null || !candidate.attachLocked(expectedProduct)
        ) {
            return@withLock false
        }

        attachedLease = candidate
        true
    }

    internal fun releaseLease(lease: RgbaCarrierLease): Boolean = leaseGate.withLock {
        if (attachedLease !== lease || retainedOperationLease != null || enteredUses != 0 || !lease.releaseLocked()) {
            return@withLock false
        }

        attachedLease = null
        true
    }

    internal fun retainForOperation(expectedProduct: JpegRuntimeProduct, lease: RgbaCarrierLease): Boolean = leaseGate.withLock {
        if (!admissionOpen || detached || installedProduct !== expectedProduct || attachedLease !== lease ||
            retainedOperationLease != null || !lease.matchesAttachedProductLocked(expectedProduct)
        ) {
            return@withLock false
        }

        retainedOperationLease = lease
        true
    }

    internal fun releaseFromOperation(lease: RgbaCarrierLease): Boolean = leaseGate.withLock {
        if (retainedOperationLease !== lease || enteredUses != 0) return@withLock false

        retainedOperationLease = null
        true
    }

    internal fun enterUse(lease: RgbaCarrierLease): ByteBuffer? {
        val buffer = leaseGate.withLock {
            if (!admissionOpen || detached || attachedLease !== lease || retainedOperationLease !== lease || !lease.isAttachedLocked()) {
                return@withLock null
            }

            val attachedBuffer = attachment.buffer ?: return@withLock null
            if (attachment.state != AttachmentState.Ready) return@withLock null

            enteredUses += 1
            attachedBuffer
        } ?: return null

        val exactRange = try {
            buffer.limit(byteCount)
            buffer.position(0)
            buffer.isDirect && !buffer.isReadOnly && buffer.capacity() == byteCount && buffer.position() == 0 &&
                    buffer.limit() == byteCount && buffer.remaining() == byteCount
        } catch (_: Exception) {
            false
        }
        if (exactRange) return buffer

        exitUse(lease)
        return null
    }

    internal fun exitUse(lease: RgbaCarrierLease): Boolean = leaseGate.withLock {
        if (attachedLease !== lease || retainedOperationLease !== lease || enteredUses <= 0) return@withLock false

        enteredUses -= 1
        true
    }

    internal fun closeAdmissionAndCheckDrained(expectedProduct: JpegRuntimeProduct): Boolean = leaseGate.withLock {
        if (installedProduct !== expectedProduct || detached) return@withLock false

        admissionOpen = false
        attachedLease == null && retainedOperationLease == null && enteredUses == 0
    }

    internal fun logicallyDetach(expectedProduct: JpegRuntimeProduct): Boolean = leaseGate.withLock {
        if (installedProduct !== expectedProduct || admissionOpen || attachedLease != null ||
            retainedOperationLease != null || enteredUses != 0 || detached
        ) {
            return@withLock false
        }

        detached = true
        installedProduct = null
        true
    }

    internal fun freeCandidate(expectedProduct: JpegRuntimeProduct): ByteBuffer? = leaseGate.withLock {
        if (installedProduct !== expectedProduct || admissionOpen || attachedLease != null || retainedOperationLease != null ||
            enteredUses != 0 || detached || admittedFreeOccurrence != null
        ) {
            return@withLock null
        }

        attachment.buffer
    }

    internal fun cleanupFreeCandidateLocked(settlementGate: ReentrantLock): ByteBuffer? {
        check(settlementGate.isHeldByCurrentThread)
        return when (attachment.state) {
            AttachmentState.Ready,
            AttachmentState.Rejected,
                -> attachment.buffer

            AttachmentState.Empty,
            AttachmentState.Attached,
                -> null
        }
    }

    internal fun hasNoReturnedBufferLocked(settlementGate: ReentrantLock): Boolean {
        check(settlementGate.isHeldByCurrentThread)
        return attachment.state == AttachmentState.Empty && attachment.buffer == null
    }

    internal fun markPhysicallyFreed(
        expectedBuffer: ByteBuffer,
        occurrence: NativeCarrierFreeOccurrence,
    ): Boolean = leaseGate.withLock {
        if (admittedFreeOccurrence !== occurrence || attachment.buffer !== expectedBuffer) return@withLock false
        if (detached) return@withLock installedProduct == null
        if (admissionOpen || attachedLease != null || retainedOperationLease != null || enteredUses != 0) {
            return@withLock false
        }

        detached = true
        installedProduct = null
        true
    }

    internal fun admitInstalledFree(
        expectedProduct: JpegRuntimeProduct,
        expectedBuffer: ByteBuffer,
        occurrence: NativeCarrierFreeOccurrence,
    ): Boolean = leaseGate.withLock {
        if (installedProduct !== expectedProduct || attachment.buffer !== expectedBuffer || admissionOpen ||
            attachedLease != null || retainedOperationLease != null || enteredUses != 0 || detached ||
            admittedFreeOccurrence != null
        ) {
            return@withLock false
        }

        admittedFreeOccurrence = occurrence
        true
    }

    internal fun admitUninstalledFree(
        expectedBuffer: ByteBuffer,
        occurrence: NativeCarrierFreeOccurrence,
    ): Boolean = leaseGate.withLock {
        if (installedProduct != null || attachment.buffer !== expectedBuffer || admissionOpen || attachedLease != null ||
            retainedOperationLease != null || enteredUses != 0 || detached || admittedFreeOccurrence != null
        ) {
            return@withLock false
        }

        admittedFreeOccurrence = occurrence
        true
    }

    internal fun logicallyDropUninstalled(): Boolean = leaseGate.withLock {
        if (installedProduct != null || admissionOpen || attachedLease != null || retainedOperationLease != null ||
            enteredUses != 0 || detached || admittedFreeOccurrence != null ||
            attachment.state != AttachmentState.Ready && attachment.state != AttachmentState.Rejected
        ) {
            return@withLock false
        }

        detached = true
        true
    }
}

internal class NativeMallocCarrier private constructor(byteCount: Int) : RgbaCarrier(byteCount) {
    internal companion object {
        internal fun create(byteCount: Int): NativeMallocCarrier = NativeMallocCarrier(byteCount)
    }
}

internal class ManagedDirectCarrier private constructor(byteCount: Int) : RgbaCarrier(byteCount) {
    internal companion object {
        internal fun create(byteCount: Int): ManagedDirectCarrier = ManagedDirectCarrier(byteCount)
    }
}

internal sealed class RgbaCarrierLease(
    internal val carrier: RgbaCarrier,
) {
    private enum class LeaseState {
        Prepared,
        Attached,
        Released,
    }

    private var state: LeaseState = LeaseState.Prepared
    private var product: JpegRuntimeProduct? = null

    internal fun attachLocked(expectedProduct: JpegRuntimeProduct): Boolean {
        if (state != LeaseState.Prepared || expectedProduct.carrier !== carrier) return false

        product = expectedProduct
        state = LeaseState.Attached
        return true
    }

    internal fun isAttachedLocked(): Boolean = state == LeaseState.Attached

    internal fun matchesAttachedProductLocked(expectedProduct: JpegRuntimeProduct): Boolean =
        state == LeaseState.Attached && product === expectedProduct

    internal fun rebindAttachedProductLocked(expectedProduct: JpegRuntimeProduct, replacementProduct: JpegRuntimeProduct): Boolean {
        if (state != LeaseState.Attached || product !== expectedProduct || replacementProduct.carrier !== carrier) {
            return false
        }

        product = replacementProduct
        return true
    }

    internal fun releaseLocked(): Boolean {
        if (state != LeaseState.Attached) return false

        state = LeaseState.Released
        return true
    }

    internal fun enterExactRange(): ByteBuffer? = carrier.enterUse(this)

    internal fun exitExactRange(): Boolean = carrier.exitUse(this)

    internal fun retainForOperation(expectedProduct: JpegRuntimeProduct): Boolean =
        carrier.retainForOperation(expectedProduct, this)

    internal fun releaseFromOperation(): Boolean = carrier.releaseFromOperation(this)

    internal fun release(): Boolean = carrier.releaseLease(this)
}

internal class NativeMallocCarrierLease private constructor(carrier: NativeMallocCarrier) : RgbaCarrierLease(carrier) {
    internal companion object {
        internal fun create(carrier: NativeMallocCarrier): NativeMallocCarrierLease = NativeMallocCarrierLease(carrier)
    }
}

internal class ManagedDirectCarrierLease private constructor(carrier: ManagedDirectCarrier) : RgbaCarrierLease(carrier) {
    internal companion object {
        internal fun create(carrier: ManagedDirectCarrier): ManagedDirectCarrierLease = ManagedDirectCarrierLease(carrier)
    }
}

internal class JpegFiniteOperationIdentity internal constructor(
    internal val operationIdentity: Long,
    internal val deadlineIdentity: Long,
    internal val initialWakeGeneration: Long,
    internal val timeoutCause: Throwable,
)

internal class NativeCarrierFreeReceipt internal constructor() : OperationReceipt

internal enum class NativeCarrierFreeSettlement {
    NotSettled,
    ReplacementAuthorized,
    CleanupCompleted,
    UnsafeResidue,
}

internal enum class NativeCarrierFreeOrigin {
    IncompatibleReplacement,
    ReturnedOwnerCleanup,
    TerminalRetirement,
}

internal class NativeCarrierFreeEvidence internal constructor() : OperationEvidence {
    internal val normalReceipt: NativeCarrierFreeReceipt = NativeCarrierFreeReceipt()

    override var receipt: OperationReceipt? = null
        internal set

    override var returnedOwner: OperationReturnedOwner? = null
        internal set
}

internal class NativeCarrierFreeOwnerBag internal constructor(
    internal var carrier: NativeMallocCarrier?,
    internal val buffer: ByteBuffer,
) : OperationOwnerBag

internal class NativeCarrierFreeOccurrence private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val expectedProduct: JpegRuntimeProduct,
    internal val origin: NativeCarrierFreeOrigin,
    internal val operation: OperationOccurrence<NativeCarrierFreeEvidence>,
    internal val ownerBag: NativeCarrierFreeOwnerBag,
    internal val ioOperation: JpegIoOperation<NativeCarrierFreeEvidence>,
) {
    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            expectedProduct: JpegRuntimeProduct,
            origin: NativeCarrierFreeOrigin,
            carrier: NativeMallocCarrier,
            buffer: ByteBuffer,
            identity: JpegFiniteOperationIdentity?,
            operationIdentity: Long,
            clock: EngineClock,
            signal: SettlementSignal,
            work: (NativeCarrierFreeOccurrence) -> Unit,
        ): NativeCarrierFreeOccurrence {
            val evidence = NativeCarrierFreeEvidence()
            val bag = NativeCarrierFreeOwnerBag(carrier = carrier, buffer = buffer)
            val operation = OperationOccurrence(
                identity = operationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(evidence),
                ownerBag = bag,
                deadlineIdentity = identity?.deadlineIdentity,
                deadlineDurationNanos = identity?.let { jpegEnteredOperationSafetyNanos },
                initialWakeGeneration = identity?.initialWakeGeneration ?: 0L,
                timeoutCause = identity?.timeoutCause,
                wakeSignal = identity?.let { signal },
            )
            lateinit var occurrence: NativeCarrierFreeOccurrence
            val ioOperation = JpegIoOperation(operation, Runnable { work(occurrence) })
            occurrence = NativeCarrierFreeOccurrence(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                expectedProduct = expectedProduct,
                origin = origin,
                operation = operation,
                ownerBag = bag,
                ioOperation = ioOperation,
            )
            return occurrence
        }
    }
}

internal class JpegIoOperation<E : OperationEvidence> internal constructor(
    internal val occurrence: OperationOccurrence<E>,
    internal val runnable: Runnable,
)
