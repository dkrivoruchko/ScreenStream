package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.JpegBackendPolicy
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.internal.capture.CapturePlan
import io.screenstream.engine.internal.capture.FrameReady
import io.screenstream.engine.internal.capture.NoCurrentSource
import io.screenstream.engine.internal.capture.ReadFailed
import io.screenstream.engine.internal.capture.ReadFrame
import io.screenstream.engine.internal.capture.ReadFrameResult
import io.screenstream.engine.internal.capture.ReadSkippedBeforeEntry
import io.screenstream.engine.internal.storage.EncodedTransactionState
import io.screenstream.engine.internal.storage.FrameworkEncodedTransaction
import io.screenstream.engine.internal.storage.NativeEncodedTransaction
import java.nio.ByteBuffer

internal sealed interface NativeHealth {
    data object Enabled : NativeHealth
    data object Disabled : NativeHealth
}

/** Identity named by returned Native facts; Control alone may authorize its monotone transition. */
internal class NativeHealthCell internal constructor(initial: NativeHealth) {
    internal var value: NativeHealth = initial
        private set

    internal fun disable(): Boolean {
        if (value == NativeHealth.Disabled) return false
        value = NativeHealth.Disabled
        return true
    }
}

internal sealed interface EncoderBackendProduct {
    val carrier: RgbaCarrier
    val nativeHealthCell: NativeHealthCell
}

internal class NativeEnabled internal constructor(
    override val carrier: NativeMallocCarrier,
    override val nativeHealthCell: NativeHealthCell,
) : EncoderBackendProduct {
    init {
        check(nativeHealthCell.value == NativeHealth.Enabled)
    }
}

internal class FrameworkOnNativeCarrier internal constructor(
    override val carrier: NativeMallocCarrier,
    override val nativeHealthCell: NativeHealthCell,
) : EncoderBackendProduct {
    init {
        check(nativeHealthCell.value == NativeHealth.Disabled)
    }
}

internal class FrameworkOnManagedCarrier internal constructor(
    override val carrier: ManagedDirectCarrier,
    override val nativeHealthCell: NativeHealthCell,
) : EncoderBackendProduct {
    init {
        check(nativeHealthCell.value == NativeHealth.Disabled)
    }
}

/** Closed result of the one process-wide preparation stage; it owns no carrier or Session decision. */
internal sealed interface EncoderBackendPreparation {
    val nativeHealthCell: NativeHealthCell?

    class NativeCarrier internal constructor(
        override val nativeHealthCell: NativeHealthCell,
    ) : EncoderBackendPreparation {
        private var consumed: Boolean = false

        internal fun consume() {
            check(!consumed)
            consumed = true
        }
    }

    class ManagedCarrier internal constructor(
        override val nativeHealthCell: NativeHealthCell,
        internal val cleanUnavailableCause: Throwable,
    ) : EncoderBackendPreparation {
        private var consumed: Boolean = false

        internal fun consume() {
            check(!consumed)
            consumed = true
        }
    }

    class Failed internal constructor(
        override val nativeHealthCell: NativeHealthCell?,
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable,
    ) : EncoderBackendPreparation
}

internal sealed interface EncoderFrameworkPreparation {
    val runtime: EncoderRuntime

    class Prepared internal constructor(
        override val runtime: EncoderRuntime,
        internal val owner: FrameworkBitmapOwner,
    ) : EncoderFrameworkPreparation

    class Failed internal constructor(
        override val runtime: EncoderRuntime,
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable,
        internal val ownerResidue: FrameworkBitmapOwner?,
    ) : EncoderFrameworkPreparation
}

/** One recycle stage for a returned Framework candidate that was never installed in its runtime. */
internal fun retireUninstalledFrameworkOwner(
    preparation: EncoderFrameworkPreparation,
): FrameworkBitmapRetirement = when (preparation) {
    is EncoderFrameworkPreparation.Prepared -> preparation.owner.retireIfIdle()
    is EncoderFrameworkPreparation.Failed ->
        preparation.ownerResidue?.retireIfIdle() ?: FrameworkBitmapRetirement.Closed
}

internal sealed interface EncoderRuntimeCreation {
    class Created internal constructor(
        internal val runtime: EncoderRuntime,
    ) : EncoderRuntimeCreation

    class Failed internal constructor(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable,
        /** Non-null whenever an adopted candidate still requires a distinct retirement stage. */
        internal val retainedRuntime: EncoderRuntime?,
        /** Earlier mechanical evidence retained when ownership ambiguity takes precedence. */
        internal val competingCause: Throwable? = null,
    ) : EncoderRuntimeCreation
}

internal sealed interface EncoderRuntimeRetirement {
    data object Closed : EncoderRuntimeRetirement

    class Retained internal constructor(
        internal val runtime: EncoderRuntime,
        internal val cause: Throwable?,
    ) : EncoderRuntimeRetirement
}

internal sealed interface EncoderFrameworkRetirement {
    data object Closed : EncoderFrameworkRetirement

    class Retained internal constructor(
        internal val runtime: EncoderRuntime,
        internal val cause: Throwable?,
    ) : EncoderFrameworkRetirement
}

/** Exact terminal settlement of one Capture-owned carrier loan. */
internal sealed interface EncoderTerminalCaptureSettlement {
    class Settled internal constructor(
        internal val read: FrameworkCaptureRead,
        internal val result: ReadFrameResult,
        internal val successor: EncoderRuntimeRetirementTask,
    ) : EncoderTerminalCaptureSettlement {
        init {
            require(result.command === read.command)
            require(successor.runtime === read.runtime)
        }
    }

    data object IdentityMismatch : EncoderTerminalCaptureSettlement
}

/** The exact direct-carrier command transferred to Capture for one readback. */
internal class FrameworkCaptureRead internal constructor(
    internal val runtime: EncoderRuntime,
    internal val lease: RgbaCarrierLease,
    internal val command: ReadFrame,
)

/** One legal carrier/backend/health product plus its optional Framework physical owner. */
internal class EncoderRuntime private constructor(
    internal val layout: RgbaLayout,
    initialProduct: EncoderBackendProduct,
    initialBitmapOwner: FrameworkBitmapOwner?,
    private val usable: Boolean,
) {
    private var productSlot: EncoderBackendProduct = initialProduct
    private var bitmapOwnerSlot: FrameworkBitmapOwner? = initialBitmapOwner

    internal val backendProduct: EncoderBackendProduct
        get() = productSlot

    internal val carrier: RgbaCarrier
        get() = productSlot.carrier

    internal val bitmapOwner: FrameworkBitmapOwner
        get() = checkNotNull(bitmapOwnerSlot)

    internal val nativeHealthCell: NativeHealthCell
        get() = productSlot.nativeHealthCell

    internal fun isCompatible(plan: CapturePlan): Boolean = usable && backendIsReady() &&
            layout.hasShape(plan.outputWidthPx, plan.outputHeightPx, plan.rgbaCarrierByteCount)

    internal fun borrowCarrier(record: ProductionRecord): RgbaCarrierLease? =
        if (usable && backendIsReady()) carrier.borrowForCapture(record) else null

    internal fun newCaptureRead(record: ProductionRecord, plan: CapturePlan): FrameworkCaptureRead? {
        if (!isCompatible(plan)) return null
        val lease = borrowCarrier(record) ?: return null
        val writableCarrier = writableCarrier(lease)
        if (writableCarrier == null) {
            check(releaseAfterCaptureReturn(lease))
            return null
        }
        val command = try {
            ReadFrame(
                configRevision = record.configRevision,
                productionId = record.productionId,
                plan = plan,
                writableCarrier = writableCarrier,
                byteCount = layout.byteCount,
            )
        } catch (failure: Throwable) {
            check(releaseAfterCaptureReturn(lease))
            throw failure
        }
        return FrameworkCaptureRead(this, lease, command)
    }

    internal fun writableCarrier(expectedLease: RgbaCarrierLease): ByteBuffer? =
        if (expectedLease.carrier === carrier) carrier.writableBuffer(expectedLease) else null

    internal fun markFrameReady(expectedLease: RgbaCarrierLease): Boolean =
        expectedLease.carrier === carrier && carrier.markFrameReady(expectedLease)

    /** Settles every closed result only for the exact command that borrowed this carrier. */
    internal fun acceptCaptureResult(expectedRead: FrameworkCaptureRead, result: ReadFrameResult): Boolean {
        if (expectedRead.runtime !== this || result.command !== expectedRead.command) return false
        return when (result) {
            is FrameReady -> result.command.writableCarrier === writableCarrier(expectedRead.lease) &&
                    markFrameReady(expectedRead.lease)

            is NoCurrentSource,
            is ReadSkippedBeforeEntry,
            is ReadFailed,
                -> releaseAfterCaptureReturn(expectedRead.lease)
        }
    }

    /**
     * Terminal-only Capture return. The exact result is accepted first: complete pixels move from
     * Capture to Ready and are then discarded to Idle, while every other outcome moves directly from
     * Capture to Idle. No encoding is formed, and the settled runtime yields one retirement successor.
     */
    internal fun settleTerminalCaptureReturn(
        expectedRead: FrameworkCaptureRead,
        result: ReadFrameResult,
    ): EncoderTerminalCaptureSettlement {
        if (!acceptCaptureResult(expectedRead, result)) {
            return EncoderTerminalCaptureSettlement.IdentityMismatch
        }
        if (result is FrameReady && !settleReadyWithoutEncoding(expectedRead)) {
            return EncoderTerminalCaptureSettlement.IdentityMismatch
        }
        check(carrier.isIdle())
        val successor = EncoderRuntimeRetirementTask(this)
        return EncoderTerminalCaptureSettlement.Settled(expectedRead, result, successor)
    }

    private fun releaseAfterCaptureReturn(expectedLease: RgbaCarrierLease): Boolean =
        expectedLease.carrier === carrier && carrier.releaseAfterCaptureReturn(expectedLease)

    internal fun newFrameworkProduction(
        record: ProductionRecord,
        expectedLease: RgbaCarrierLease,
        transaction: FrameworkEncodedTransaction,
        quality: Int,
    ): FrameworkProduction? {
        if (!usable || productSlot is NativeEnabled || bitmapOwnerSlot?.isComplete() != true ||
            quality !in JPEG_QUALITY_RANGE ||
            expectedLease.carrier !== carrier || expectedLease.record !== record ||
            !carrier.ownsReadyLease(expectedLease) || !transaction.isFreshOpen
        ) {
            return null
        }
        return FrameworkProduction(this, record, expectedLease, transaction, quality)
    }

    internal fun newFrameworkProduction(
        expectedRead: FrameworkCaptureRead,
        transaction: FrameworkEncodedTransaction,
        quality: Int,
    ): FrameworkProduction? {
        if (expectedRead.runtime !== this) return null
        return newFrameworkProduction(expectedRead.lease.record, expectedRead.lease, transaction, quality)
    }

    internal fun enterFrameworkUse(production: FrameworkProduction): ByteBuffer? {
        if (!names(production) || !usable || productSlot is NativeEnabled ||
            bitmapOwnerSlot?.isComplete() != true
        ) return null
        return carrier.enterEncoding(production.carrierLease)
    }

    internal fun releaseFrameworkUseAfterReturn(production: FrameworkProduction): Boolean =
        names(production) && carrier.releaseAfterEncodingReturn(production.carrierLease)

    internal fun releaseReadyAfterRejectedAdmission(production: FrameworkProduction): Boolean =
        names(production) && carrier.releaseReadyBeforeEntry(production.carrierLease)

    /** Discards an exact complete readback before any JPEG transaction has been formed. */
    internal fun settleReadyWithoutEncoding(expectedRead: FrameworkCaptureRead): Boolean =
        expectedRead.runtime === this && expectedRead.lease.carrier === carrier &&
                carrier.releaseReadyBeforeEntry(expectedRead.lease)

    internal fun skipBeforeEntry(production: FrameworkProduction): FrameworkJpegSkipped? {
        if (!names(production)) return null
        when (production.transaction.state) {
            EncodedTransactionState.Open,
            EncodedTransactionState.ProducerClosed,
            EncodedTransactionState.Faulted,
                -> Unit

            EncodedTransactionState.Aborted,
            EncodedTransactionState.Committed,
                -> return null
        }
        if (!carrier.releaseReadyBeforeEntry(production.carrierLease)) return null
        check(production.transaction.abort())
        return FrameworkJpegSkipped(production.record, production.transaction)
    }

    internal fun newNativeProduction(
        expectedRead: FrameworkCaptureRead,
        transaction: NativeEncodedTransaction,
        quality: Int,
    ): NativeJpegProduction? {
        val product = productSlot as? NativeEnabled ?: return null
        if (!usable || expectedRead.runtime !== this || expectedRead.lease.carrier !== product.carrier ||
            !product.carrier.ownsReadyLease(expectedRead.lease) || !transaction.isFreshOpen ||
            quality !in JPEG_QUALITY_RANGE
        ) {
            return null
        }
        return NativeJpegProduction(
            runtime = this,
            record = expectedRead.lease.record,
            carrierLease = expectedRead.lease,
            transaction = transaction,
            quality = quality,
            healthCell = product.nativeHealthCell,
        )
    }

    internal fun enterNativeUse(production: NativeJpegProduction): ByteBuffer? {
        val product = productSlot as? NativeEnabled ?: return null
        if (production.runtime !== this || production.carrierLease.carrier !== product.carrier ||
            production.healthCell !== product.nativeHealthCell || !usable
        ) return null
        return carrier.enterEncoding(production.carrierLease)
    }

    internal fun releaseNativeUseAfterReturn(production: NativeJpegProduction): Boolean =
        production.runtime === this && production.carrierLease.carrier === carrier &&
                carrier.releaseAfterEncodingReturn(production.carrierLease)

    internal fun releaseNativeReadyBeforeEntry(production: NativeJpegProduction): Boolean =
        production.runtime === this && production.carrierLease.carrier === carrier &&
                carrier.releaseReadyBeforeEntry(production.carrierLease)

    internal fun skipNativeBeforeEntry(production: NativeJpegProduction): NativeJpegSkipped? {
        if (production.runtime !== this || production.carrierLease.carrier !== carrier ||
            production.hasResultBlock
        ) return null
        when (production.transaction.state) {
            EncodedTransactionState.Open,
            EncodedTransactionState.ProducerClosed,
            EncodedTransactionState.Faulted,
                -> Unit

            EncodedTransactionState.Aborted,
            EncodedTransactionState.Committed,
                -> return null
        }
        if (!carrier.releaseReadyBeforeEntry(production.carrierLease)) return null
        check(production.transaction.abort())
        return NativeJpegSkipped(production)
    }

    /** Applies only an already-authorized health transition; it creates no fallback work. */
    internal fun disableNativeForLaterFrames(expectedCell: NativeHealthCell): Boolean {
        val product = productSlot as? NativeEnabled ?: return false
        if (product.nativeHealthCell !== expectedCell || !expectedCell.disable()) return false
        productSlot = FrameworkOnNativeCarrier(product.carrier, expectedCell)
        return true
    }

    /** Serial mechanical creation seam used only after policy has selected Framework. */
    internal fun prepareFrameworkOwner(): EncoderFrameworkPreparation {
        check(productSlot !is NativeEnabled)
        check(bitmapOwnerSlot == null)
        return when (val creation = FrameworkBitmapOwner.create(layout)) {
            is FrameworkBitmapCreation.Created -> EncoderFrameworkPreparation.Prepared(this, creation.owner)
            is FrameworkBitmapCreation.Failed -> EncoderFrameworkPreparation.Failed(
                runtime = this,
                problem = creation.problem,
                cause = creation.cause,
                ownerResidue = creation.ownerResidue,
            )
        }
    }

    /** Adopts only the exact returned candidate after external currentness arbitration. */
    internal fun installFrameworkOwner(preparation: EncoderFrameworkPreparation.Prepared): Boolean {
        if (preparation.runtime !== this || productSlot is NativeEnabled || bitmapOwnerSlot != null ||
            !preparation.owner.isComplete()
        ) return false
        bitmapOwnerSlot = preparation.owner
        return true
    }

    /** Roots a partially-created owner so the runtime can perform its exact retirement suffix. */
    internal fun retainFrameworkOwnerResidue(preparation: EncoderFrameworkPreparation.Failed): Boolean {
        val residue = preparation.ownerResidue ?: return false
        if (preparation.runtime !== this || bitmapOwnerSlot != null) return false
        bitmapOwnerSlot = residue
        return true
    }

    /** One entered Framework-owner recycle stage. Carrier retirement is deliberately separate. */
    internal fun retireFrameworkOwner(): EncoderFrameworkRetirement {
        val owner = bitmapOwnerSlot
            ?: return EncoderFrameworkRetirement.Closed
        if (!carrier.isIdle() || owner.isInUse()) return EncoderFrameworkRetirement.Retained(this, null)
        return when (val bitmapRetirement = owner.retireIfIdle()) {
            FrameworkBitmapRetirement.Closed -> {
                bitmapOwnerSlot = null
                EncoderFrameworkRetirement.Closed
            }

            is FrameworkBitmapRetirement.Retained ->
                EncoderFrameworkRetirement.Retained(this, bitmapRetirement.cause)
        }
    }

    /** One entered native-free or managed-reference-drop stage, admitted only after Bitmap retirement. */
    internal fun retireCarrier(): EncoderRuntimeRetirement {
        if (bitmapOwnerSlot != null || !carrier.isIdle()) return EncoderRuntimeRetirement.Retained(this, null)
        return when (val carrierRetirement = carrier.retireIfIdle()) {
            CarrierRetirement.Closed -> EncoderRuntimeRetirement.Closed
            is CarrierRetirement.Retained -> EncoderRuntimeRetirement.Retained(this, carrierRetirement.cause)
        }
    }

    internal fun isRetired(): Boolean = carrier.isRetired() && bitmapOwnerSlot == null

    private fun names(production: FrameworkProduction): Boolean =
        production.runtime === this && production.carrierLease.carrier === carrier

    private fun backendIsReady(): Boolean = when (productSlot) {
        is NativeEnabled -> bitmapOwnerSlot == null
        is FrameworkOnNativeCarrier,
        is FrameworkOnManagedCarrier,
            -> bitmapOwnerSlot?.isComplete() == true
    }

    internal companion object {
        /** Complete Native process preparation; carrier and Framework allocations are separate later stages. */
        internal fun prepareBackend(
            backendPolicy: JpegBackendPolicy,
            existingHealthCell: NativeHealthCell?,
        ): EncoderBackendPreparation = when (val availability = NativeJpegProcess.ensureAvailable()) {
            NativeJpegProcess.Availability.Available -> {
                if (backendPolicy == JpegBackendPolicy.FrameworkOnly) {
                    if (existingHealthCell?.value == NativeHealth.Enabled) {
                        EncoderBackendPreparation.Failed(
                            nativeHealthCell = existingHealthCell,
                            problem = ScreenCaptureProblem.InternalFailure,
                            cause = IllegalStateException(
                                "FrameworkOnly contradicts enabled Session Native health",
                            ),
                        )
                    } else {
                        EncoderBackendPreparation.NativeCarrier(
                            existingHealthCell ?: NativeHealthCell(NativeHealth.Disabled),
                        )
                    }
                } else if (existingHealthCell != null) {
                    // The first preparation fixed this Session's monotone Native capability fact.
                    EncoderBackendPreparation.NativeCarrier(existingHealthCell)
                } else {
                    try {
                        val health = if (NativeJpegProcess.hasWeakCompressor()) {
                            NativeHealth.Enabled
                        } else {
                            NativeHealth.Disabled
                        }
                        EncoderBackendPreparation.NativeCarrier(
                            NativeHealthCell(health),
                        )
                    } catch (failure: Exception) {
                        EncoderBackendPreparation.Failed(
                            nativeHealthCell = null,
                            problem = ScreenCaptureProblem.InternalFailure,
                            cause = failure,
                        )
                    }
                }
            }

            is NativeJpegProcess.Availability.CleanUnavailable -> {
                if (existingHealthCell?.value == NativeHealth.Enabled) {
                    EncoderBackendPreparation.Failed(
                        nativeHealthCell = existingHealthCell,
                        problem = ScreenCaptureProblem.InternalFailure,
                        cause = IllegalStateException(
                            "enabled Session Native health contradicts sticky clean unavailability",
                        ),
                    )
                } else {
                    EncoderBackendPreparation.ManagedCarrier(
                        nativeHealthCell = existingHealthCell ?: NativeHealthCell(NativeHealth.Disabled),
                        cleanUnavailableCause = availability.cause,
                    )
                }
            }

            is NativeJpegProcess.Availability.LoadOome -> EncoderBackendPreparation.Failed(
                nativeHealthCell = existingHealthCell,
                problem = ScreenCaptureProblem.ResourceExhausted,
                cause = availability.cause,
            )

            is NativeJpegProcess.Availability.Poisoned -> EncoderBackendPreparation.Failed(
                nativeHealthCell = existingHealthCell,
                problem = ScreenCaptureProblem.InternalFailure,
                cause = availability.cause,
            )
        }

        /** Pure pre-entry construction; core stores this candidate in its allocation task before dispatch. */
        internal fun newNativeAllocationCandidate(layout: RgbaLayout): NativeMallocCarrier =
            NativeMallocCarrier.newPending(layout)

        /** One native-malloc allocation stage. */
        internal fun allocateNativeRuntime(
            layout: RgbaLayout,
            preparation: EncoderBackendPreparation.NativeCarrier,
            candidate: NativeMallocCarrier,
        ): EncoderRuntimeCreation {
            check(candidate.layout === layout)
            preparation.consume()
            val carrier = when (val creation = candidate.allocateIntoPendingOwner()) {
                is NativeCarrierCreation.Created -> creation.carrier
                is NativeCarrierCreation.Failed -> return EncoderRuntimeCreation.Failed(
                    problem = creation.problem,
                    cause = creation.cause,
                    retainedRuntime = creation.retainedCarrier?.let { retained ->
                        EncoderRuntime(
                            layout,
                            productForNativeCarrier(retained, preparation.nativeHealthCell),
                            initialBitmapOwner = null,
                            usable = false,
                        )
                    },
                )
            }
            return EncoderRuntimeCreation.Created(
                EncoderRuntime(
                    layout,
                    productForNativeCarrier(carrier, preparation.nativeHealthCell),
                    initialBitmapOwner = null,
                    usable = true,
                ),
            )
        }

        /** One managed-direct allocation stage, used only after clean own-DSO unavailability. */
        internal fun allocateManagedRuntime(
            layout: RgbaLayout,
            preparation: EncoderBackendPreparation.ManagedCarrier,
        ): EncoderRuntimeCreation {
            preparation.consume()
            val carrier = when (val creation = ManagedDirectCarrier.create(layout)) {
                is ManagedCarrierCreation.Created -> creation.carrier
                is ManagedCarrierCreation.Failed -> return EncoderRuntimeCreation.Failed(
                    creation.problem,
                    creation.cause,
                    null,
                )
            }
            return EncoderRuntimeCreation.Created(
                EncoderRuntime(
                    layout,
                    FrameworkOnManagedCarrier(carrier, preparation.nativeHealthCell),
                    initialBitmapOwner = null,
                    usable = true,
                ),
            )
        }

        /** Checked layout preflight used before either carrier allocation stage. */
        internal fun layoutFor(plan: CapturePlan): RgbaLayout {
            val layout = RgbaLayout.create(plan.outputWidthPx, plan.outputHeightPx)
            require(layout.byteCount == plan.rgbaCarrierByteCount) {
                "capture plan and RGBA layout byte counts differ"
            }
            return layout
        }

        private fun productForNativeCarrier(
            carrier: NativeMallocCarrier,
            healthCell: NativeHealthCell,
        ): EncoderBackendProduct = when (healthCell.value) {
            NativeHealth.Enabled -> NativeEnabled(carrier, healthCell)
            NativeHealth.Disabled -> FrameworkOnNativeCarrier(carrier, healthCell)
        }

        private val JPEG_QUALITY_RANGE: IntRange = 0..100
    }
}

/** Exact immutable carrier/transaction bundle for one production. */
internal class FrameworkProduction internal constructor(
    internal val runtime: EncoderRuntime,
    internal val record: ProductionRecord,
    internal val carrierLease: RgbaCarrierLease,
    internal val transaction: FrameworkEncodedTransaction,
    internal val quality: Int,
) {
    init {
        require(quality in 0..100)
        require(carrierLease.record === record)
        require(carrierLease.carrier === runtime.carrier)
        require(transaction.isFreshOpen)
    }
}
