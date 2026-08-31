package io.screenstream.capture.internal.encoding

import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import java.nio.ByteBuffer

internal class NativeHealthCell(initial: State) {
    internal enum class State { Enabled, Disabled, }

    internal var state: State = initial
        private set

    internal fun disable(): Boolean {
        if (state == State.Disabled) return false
        state = State.Disabled
        return true
    }
}

internal sealed interface EncoderBackendState {
    val carrier: RgbaCarrier

    class NativeOnNativeCarrier(override val carrier: NativeMallocCarrier) : EncoderBackendState
    class Framework(override val carrier: RgbaCarrier) : EncoderBackendState
}

internal sealed interface EncoderBackendPreparation {
    val nativeHealthCell: NativeHealthCell?

    class NativeCarrier(override val nativeHealthCell: NativeHealthCell) : EncoderBackendPreparation

    class ManagedCarrier(override val nativeHealthCell: NativeHealthCell) : EncoderBackendPreparation

    class Failed(
        override val nativeHealthCell: NativeHealthCell?,
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable?,
    ) : EncoderBackendPreparation
}

internal sealed interface EncoderRuntimeCreation {
    class Created(internal val runtime: EncoderRuntime) : EncoderRuntimeCreation

    class Failed(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable?,
        internal val retainedRuntime: EncoderRuntime?,
    ) : EncoderRuntimeCreation
}

internal class EncoderRuntime private constructor(
    internal val layout: Rgba8888Layout,
    initialBackendState: EncoderBackendState,
    initialBitmapOwner: FrameworkBitmapOwner?,
    private val usable: Boolean,
) {
    internal var backendState: EncoderBackendState = initialBackendState
        private set

    private var bitmapOwnerSlot: FrameworkBitmapOwner? = initialBitmapOwner

    internal val carrier: RgbaCarrier
        get() = backendState.carrier

    internal fun requireBitmapOwner(): FrameworkBitmapOwner = checkNotNull(bitmapOwnerSlot)

    internal fun isCompatible(requiredLayout: Rgba8888Layout): Boolean =
        usable && isBackendReady && layout.hasSameDimensionsAs(requiredLayout)

    internal fun canPrepareFrameworkOwner(requiredLayout: Rgba8888Layout): Boolean =
        (usable) && (backendState !is EncoderBackendState.NativeOnNativeCarrier) &&
                (bitmapOwnerSlot == null) && (carrier.isIdle) && (layout.hasSameDimensionsAs(requiredLayout))

    internal fun lendCarrier(owner: EncodingOwner, returnPort: EncodingProductionReturnPort): EncodingInput? =
        if (usable && isBackendReady) carrier.lend(owner, returnPort) else null

    internal fun newFrameworkProduction(
        expectedInput: EncodingInput,
        transaction: FrameworkEncodedTransaction,
        jpegQuality: Int,
    ): FrameworkJpegProduction? {
        if ((!usable || backendState is EncoderBackendState.NativeOnNativeCarrier || jpegQuality !in ScreenCaptureParameters.JPEG_QUALITY_RANGE ||
                    expectedInput.carrier !== carrier)
        ) {
            return null
        }
        return FrameworkJpegProduction(this, expectedInput, transaction, jpegQuality)
    }

    internal fun enterFrameworkUse(production: FrameworkJpegProduction): ByteBuffer? {
        if (!ownsFrameworkProduction(production) || !usable || backendState is EncoderBackendState.NativeOnNativeCarrier
            || bitmapOwnerSlot?.isComplete != true || !production.transaction.isFreshOpen
        ) {
            return null
        }
        return carrier.enterEncoding(production.input)
    }

    internal fun releaseFrameworkUseAfterReturn(production: FrameworkJpegProduction): Boolean =
        ownsFrameworkProduction(production) && carrier.releaseAfterEncodingReturn(production.input)

    internal fun releaseReadyAfterRejectedAdmission(production: FrameworkJpegProduction): Boolean =
        ownsFrameworkProduction(production) && carrier.discardReady(production.input) === production.input

    internal fun skipFrameworkBeforeEntry(production: FrameworkJpegProduction): FrameworkJpegResult.Skipped? {
        if (!ownsFrameworkProduction(production)) return null
        when (production.transaction.state) {
            ManagedEncodedTransaction.State.Open,
            ManagedEncodedTransaction.State.ProducerClosed,
            ManagedEncodedTransaction.State.Faulted,
                -> Unit

            ManagedEncodedTransaction.State.Aborted, ManagedEncodedTransaction.State.Committed -> return null
        }
        if (carrier.discardReady(production.input) !== production.input) return null
        check(production.transaction.abort())
        return FrameworkJpegResult.Skipped(production.transaction)
    }

    internal fun newNativeProduction(
        expectedInput: EncodingInput,
        transaction: NativeEncodedTransaction,
        jpegQuality: Int,
        healthCell: NativeHealthCell,
        nativeJpeg: NativeJpegFacade,
    ): NativeJpegProduction? {
        val backend = backendState as? EncoderBackendState.NativeOnNativeCarrier ?: return null
        if (!usable || expectedInput.carrier !== backend.carrier || jpegQuality !in ScreenCaptureParameters.JPEG_QUALITY_RANGE) {
            return null
        }
        return NativeJpegProduction(this, expectedInput, transaction, jpegQuality, healthCell, nativeJpeg)
    }

    internal fun enterNativeUse(production: NativeJpegProduction): ByteBuffer? {
        val backend = backendState as? EncoderBackendState.NativeOnNativeCarrier ?: return null
        if (production.runtime !== this || production.input.carrier !== backend.carrier ||
            production.healthCell.state != NativeHealthCell.State.Enabled || !production.transaction.isFreshOpen || !usable
        ) return null
        return carrier.enterEncoding(production.input)
    }

    internal fun releaseNativeUseAfterReturn(production: NativeJpegProduction): Boolean =
        production.runtime === this && production.input.carrier === carrier &&
                carrier.releaseAfterEncodingReturn(production.input)

    internal fun releaseNativeReadyBeforeEntry(production: NativeJpegProduction): Boolean =
        production.runtime === this && production.input.carrier === carrier &&
                carrier.discardReady(production.input) === production.input

    internal fun skipNativeBeforeEntry(production: NativeJpegProduction): NativeJpegResult? {
        if (production.runtime !== this || production.input.carrier !== carrier || production.hasResultBlock) return null
        when (production.transaction.state) {
            ManagedEncodedTransaction.State.Open,
            ManagedEncodedTransaction.State.ProducerClosed,
            ManagedEncodedTransaction.State.Faulted,
                -> Unit

            ManagedEncodedTransaction.State.Aborted, ManagedEncodedTransaction.State.Committed -> return null
        }
        if (carrier.discardReady(production.input) !== production.input) return null
        check(production.transaction.abort())
        return production.recordSkippedBeforeEntry()
    }

    internal fun switchNativeToFramework(): Boolean {
        val backend = backendState as? EncoderBackendState.NativeOnNativeCarrier ?: return false
        backendState = EncoderBackendState.Framework(backend.carrier)
        return true
    }

    internal fun newFrameworkOwnerCandidate(): FrameworkBitmapOwner {
        check(backendState !is EncoderBackendState.NativeOnNativeCarrier)
        check(bitmapOwnerSlot == null)
        check(carrier.isIdle)
        return FrameworkBitmapOwner(layout)
    }

    internal fun prepareFrameworkOwner(candidate: FrameworkBitmapOwner): FrameworkBitmapOwner.Creation {
        check(backendState !is EncoderBackendState.NativeOnNativeCarrier)
        check(bitmapOwnerSlot == null)
        check(carrier.isIdle)
        check(candidate.layout === layout)
        return candidate.allocateIntoPendingOwner()
    }

    internal fun installFrameworkOwner(creation: FrameworkBitmapOwner.Creation.Created): Boolean {
        if (backendState is EncoderBackendState.NativeOnNativeCarrier || bitmapOwnerSlot != null || !creation.owner.isComplete) {
            return false
        }
        bitmapOwnerSlot = creation.owner
        return true
    }

    internal fun retainFrameworkOwnerResidue(creation: FrameworkBitmapOwner.Creation.Failed): Boolean {
        val residue = creation.ownerResidue ?: return false
        if (bitmapOwnerSlot != null) return false
        bitmapOwnerSlot = residue
        return true
    }

    internal fun retireFrameworkOwner(): EncodingRetirement {
        val owner = bitmapOwnerSlot ?: return EncodingRetirement.Closed
        if (!carrier.isIdle || owner.isInUse) return EncodingRetirement.Retained(null)
        return when (val bitmapRetirement = owner.retireIfIdle()) {
            EncodingRetirement.Closed -> {
                bitmapOwnerSlot = null
                EncodingRetirement.Closed
            }

            is EncodingRetirement.Retained -> bitmapRetirement
        }
    }

    internal fun retireCarrier(): EncodingRetirement {
        if (bitmapOwnerSlot != null || !carrier.isIdle) return EncodingRetirement.Retained(null)
        return carrier.retireIfIdle()
    }

    private fun ownsFrameworkProduction(production: FrameworkJpegProduction): Boolean =
        production.runtime === this && production.input.carrier === carrier

    private val isBackendReady: Boolean
        get() = when (backendState) {
            is EncoderBackendState.NativeOnNativeCarrier -> bitmapOwnerSlot == null
            is EncoderBackendState.Framework -> bitmapOwnerSlot?.isComplete == true
        }

    internal companion object {
        internal fun prepareBackend(
            backendPolicy: JpegBackendPolicy,
            existingHealthCell: NativeHealthCell?,
            nativeJpeg: NativeJpegFacade,
        ): EncoderBackendPreparation {
            if (backendPolicy == JpegBackendPolicy.FrameworkOnly) {
                return EncoderBackendPreparation.ManagedCarrier(existingHealthCell ?: NativeHealthCell(NativeHealthCell.State.Disabled))
            }
            return when (nativeJpeg.resolveAvailability()) {
                NativeJpegProcess.Availability.Available -> {
                    if (existingHealthCell != null) {
                        EncoderBackendPreparation.NativeCarrier(existingHealthCell)
                    } else {
                        try {
                            val healthState = if (nativeJpeg.hasWeakCompressor()) {
                                NativeHealthCell.State.Enabled
                            } else {
                                NativeHealthCell.State.Disabled
                            }
                            EncoderBackendPreparation.NativeCarrier(NativeHealthCell(healthState))
                        } catch (failure: Exception) {
                            EncoderBackendPreparation.Failed(
                                nativeHealthCell = null,
                                problem = ScreenCaptureProblem.InternalFailure,
                                cause = failure,
                            )
                        }
                    }
                }

                NativeJpegProcess.Availability.CleanUnavailable -> {
                    if (existingHealthCell?.state == NativeHealthCell.State.Enabled) {
                        EncoderBackendPreparation.Failed(
                            nativeHealthCell = existingHealthCell,
                            problem = ScreenCaptureProblem.InternalFailure,
                            cause = IllegalStateException("enabled Session Native health contradicts sticky clean unavailability"),
                        )
                    } else {
                        EncoderBackendPreparation.ManagedCarrier(
                            nativeHealthCell = existingHealthCell ?: NativeHealthCell(NativeHealthCell.State.Disabled),
                        )
                    }
                }

                NativeJpegProcess.Availability.Poisoned -> EncoderBackendPreparation.Failed(
                    nativeHealthCell = existingHealthCell,
                    problem = ScreenCaptureProblem.InternalFailure,
                    cause = null,
                )
            }
        }

        internal fun allocateNativeRuntime(
            layout: Rgba8888Layout,
            preparation: EncoderBackendPreparation.NativeCarrier,
            candidate: NativeMallocCarrier,
        ): EncoderRuntimeCreation {
            check(candidate.layout === layout)
            return when (val creation = candidate.allocateIntoPendingOwner()) {
                is NativeMallocCarrier.Creation.Created -> {
                    EncoderRuntimeCreation.Created(
                        EncoderRuntime(
                            layout = layout,
                            initialBackendState = when (preparation.nativeHealthCell.state) {
                                NativeHealthCell.State.Enabled -> EncoderBackendState.NativeOnNativeCarrier(creation.carrier)
                                NativeHealthCell.State.Disabled -> EncoderBackendState.Framework(creation.carrier)
                            },
                            initialBitmapOwner = null,
                            usable = true,
                        ),
                    )
                }

                is NativeMallocCarrier.Creation.Failed -> EncoderRuntimeCreation.Failed(
                    problem = creation.problem,
                    cause = creation.cause,
                    retainedRuntime = creation.retainedCarrier?.let { retained ->
                        EncoderRuntime(
                            layout = layout,
                            initialBackendState = when (preparation.nativeHealthCell.state) {
                                NativeHealthCell.State.Enabled -> EncoderBackendState.NativeOnNativeCarrier(retained)
                                NativeHealthCell.State.Disabled -> EncoderBackendState.Framework(retained)
                            },
                            initialBitmapOwner = null,
                            usable = false,
                        )
                    },
                )
            }
        }

        internal fun allocateManagedRuntime(
            layout: Rgba8888Layout,
            candidate: ManagedDirectCarrier,
        ): EncoderRuntimeCreation {
            check(candidate.layout === layout)
            return when (val creation = candidate.allocateIntoPendingOwner()) {
                is ManagedDirectCarrier.Creation.Created -> EncoderRuntimeCreation.Created(
                    EncoderRuntime(
                        layout = layout,
                        initialBackendState = EncoderBackendState.Framework(creation.carrier),
                        initialBitmapOwner = null,
                        usable = true,
                    ),
                )

                is ManagedDirectCarrier.Creation.Failed -> EncoderRuntimeCreation.Failed(
                    problem = creation.problem,
                    cause = creation.cause,
                    retainedRuntime = creation.retainedCarrier?.let { retained ->
                        EncoderRuntime(
                            layout = layout,
                            initialBackendState = EncoderBackendState.Framework(retained),
                            initialBitmapOwner = null,
                            usable = false,
                        )
                    },
                )
            }
        }
    }
}
