package io.screenstream.engine.internal.jpeg

import android.graphics.Bitmap
import io.screenstream.engine.ImageSize
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import kotlin.concurrent.withLock

internal class FrameworkJpegOwner private constructor(
    internal val imageSize: ImageSize,
    internal val rowByteCount: Int,
    internal val pixelByteCount: Int,
    internal val jpegRuntimeOwner: JpegRuntimeOwner,
    internal val clock: EngineClock,
) : OperationReturnedOwner {
    private var bitmapSlot: Bitmap? = null
    private var rowScratchSlot: IntArray? = null
    private var selectedTransferMode: FrameworkTransferMode? = null
    private var actualWidth: Int = 0
    private var actualHeight: Int = 0
    private var actualConfig: Bitmap.Config? = null
    private var actualMutable: Boolean = false
    private var actualRecycled: Boolean = true
    private var actualRowByteCount: Int = 0
    private var actualBitmapByteCount: Int = 0
    private var actualApiMetadataValid: Boolean = false
    private var activeCreation: FrameworkResourceCreationOccurrence? = null
    private var activeEncode: FrameworkEncodeOccurrence? = null
    private var activeRecycle: FrameworkBitmapRecycleOccurrence? = null
    private var bitmapUseCount: Int = 0

    internal val transferMode: FrameworkTransferMode
        get() = checkNotNull(selectedTransferMode)

    internal fun isShapeCompatible(expectedImageSize: ImageSize, expectedCarrierByteCount: Int): Boolean =
        expectedCarrierByteCount == pixelByteCount && expectedImageSize == imageSize && hasCompleteResources()

    internal fun adoptBitmap(returnedBitmap: Bitmap): Boolean {
        if (bitmapSlot != null) return false
        bitmapSlot = returnedBitmap
        return true
    }

    internal fun recordActualMetadata(
        width: Int,
        height: Int,
        config: Bitmap.Config?,
        mutable: Boolean,
        recycled: Boolean,
        rowByteCount: Int,
        bitmapByteCount: Int,
        apiMetadataValid: Boolean,
    ): Boolean {
        if (bitmapSlot == null || actualConfig != null || actualWidth != 0 || actualHeight != 0 || actualRowByteCount != 0 || actualBitmapByteCount != 0) {
            return false
        }

        actualWidth = width
        actualHeight = height
        actualConfig = config
        actualMutable = mutable
        actualRecycled = recycled
        actualRowByteCount = rowByteCount
        actualBitmapByteCount = bitmapByteCount
        actualApiMetadataValid = apiMetadataValid
        return true
    }

    internal fun completeResources(transferMode: FrameworkTransferMode, scratch: IntArray?): Boolean {
        if (bitmapSlot == null || selectedTransferMode != null || rowScratchSlot != null ||
            (transferMode == FrameworkTransferMode.TightBufferCopy && scratch != null) ||
            (transferMode == FrameworkTransferMode.PortableRowCopy && scratch == null) || actualWidth <= 0 ||
            actualHeight <= 0 || actualConfig != Bitmap.Config.ARGB_8888 || !actualMutable || actualRecycled ||
            actualRowByteCount <= 0 || actualBitmapByteCount <= 0 || !actualApiMetadataValid
        ) {
            return false
        }

        selectedTransferMode = transferMode
        rowScratchSlot = scratch
        return true
    }

    internal fun hasCompleteResources(): Boolean =
        bitmapSlot != null && selectedTransferMode != null && actualWidth > 0 && actualHeight > 0 &&
                actualConfig == Bitmap.Config.ARGB_8888 && actualMutable && !actualRecycled &&
                actualRowByteCount > 0 && actualBitmapByteCount > 0 && actualApiMetadataValid &&
                (selectedTransferMode != FrameworkTransferMode.TightBufferCopy || rowScratchSlot == null) &&
                (selectedTransferMode != FrameworkTransferMode.PortableRowCopy || rowScratchSlot != null)

    internal fun bitmapForUse(): Bitmap? = if (hasCompleteResources()) bitmapSlot else null

    internal fun rowScratchForUse(): IntArray? = if (hasCompleteResources()) rowScratchSlot else null

    internal fun bitmapForRecycle(): Bitmap? = bitmapSlot

    internal fun clearRecycledReferences(): Boolean {
        if (bitmapSlot == null) return false
        bitmapSlot = null
        rowScratchSlot = null
        return true
    }

    internal fun hasNoBitmap(): Boolean = bitmapSlot == null

    internal fun ownsBitmapForCleanup(): Boolean = bitmapSlot != null

    internal fun reserveCreation(occurrence: FrameworkResourceCreationOccurrence): Boolean {
        if (activeCreation != null) return false
        activeCreation = occurrence
        return true
    }

    internal fun ownsCreation(occurrence: FrameworkResourceCreationOccurrence): Boolean = activeCreation === occurrence

    internal fun clearCreation(occurrence: FrameworkResourceCreationOccurrence): Boolean {
        if (activeCreation !== occurrence) return false
        activeCreation = null
        return true
    }

    internal fun reserveEncodeUse(occurrence: FrameworkEncodeOccurrence): Boolean {
        if (activeCreation != null || activeEncode != null || activeRecycle != null || bitmapUseCount != 0 ||
            !hasCompleteResources()
        ) {
            return false
        }
        activeEncode = occurrence
        bitmapUseCount = 1
        return true
    }

    internal fun ownsEncode(occurrence: FrameworkEncodeOccurrence): Boolean = activeEncode === occurrence

    internal fun releaseEncodeUse(occurrence: FrameworkEncodeOccurrence): Boolean {
        if (activeEncode !== occurrence || bitmapUseCount != 1) return false
        bitmapUseCount = 0
        return true
    }

    internal fun clearEncode(occurrence: FrameworkEncodeOccurrence): Boolean {
        if (activeEncode !== occurrence || bitmapUseCount != 0) return false
        activeEncode = null
        return true
    }

    internal fun reserveRecycle(occurrence: FrameworkBitmapRecycleOccurrence): Boolean {
        if (activeCreation != null || activeRecycle != null || activeEncode != null || bitmapUseCount != 0) return false
        activeRecycle = occurrence
        return true
    }

    internal fun reserveCreationRecycle(
        creation: FrameworkResourceCreationOccurrence,
        recycle: FrameworkBitmapRecycleOccurrence,
    ): Boolean {
        if (activeCreation !== creation || activeRecycle != null || activeEncode != null || bitmapUseCount != 0) {
            return false
        }
        activeRecycle = recycle
        return true
    }

    internal fun ownsRecycle(occurrence: FrameworkBitmapRecycleOccurrence): Boolean = activeRecycle === occurrence

    internal fun clearRecycle(occurrence: FrameworkBitmapRecycleOccurrence): Boolean {
        if (activeRecycle !== occurrence || bitmapUseCount != 0) return false
        activeRecycle = null
        return true
    }

    internal fun beginEncode(
        expectedProduct: JpegRuntimeProduct.FrameworkOnNativeCarrier,
        expectedLease: NativeMallocCarrierLease,
        storage: EncodedStorageOwner,
        effectiveParameters: ScreenCaptureEffectiveParameters,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): FrameworkEncodeOccurrence? = beginFrameworkEncode(
        owner = this,
        expectedProduct = expectedProduct,
        expectedLease = expectedLease,
        storage = storage,
        effectiveParameters = effectiveParameters,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        identity = identity,
    )

    internal fun beginEncode(
        expectedProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier,
        expectedLease: ManagedDirectCarrierLease,
        storage: EncodedStorageOwner,
        effectiveParameters: ScreenCaptureEffectiveParameters,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): FrameworkEncodeOccurrence? = beginFrameworkEncode(
        owner = this,
        expectedProduct = expectedProduct,
        expectedLease = expectedLease,
        storage = storage,
        effectiveParameters = effectiveParameters,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        identity = identity,
    )

    internal fun claimEncode(
        occurrence: FrameworkEncodeOccurrence,
    ): FrameworkEncodeClaimFact? = claimFrameworkEncode(
        owner = this,
        occurrence = occurrence,
    )

    internal fun beginIncompatibleRecycle(
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): FrameworkBitmapRecycleOccurrence? = beginIncompatibleFrameworkRecycle(
        owner = this,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        identity = identity,
    )

    internal fun beginTerminalRecycle(
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        operationIdentity: Long,
    ): FrameworkBitmapRecycleOccurrence? = beginTerminalFrameworkRecycle(
        owner = this,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        operationIdentity = operationIdentity,
    )

    internal fun settleRecycle(occurrence: FrameworkBitmapRecycleOccurrence): FrameworkBitmapRecycleSettlement =
        settleFrameworkRecycle(owner = this, occurrence = occurrence)

    internal companion object {
        internal fun beginResourceCreation(
            jpegRuntimeOwner: JpegRuntimeOwner,
            clock: EngineClock,
            expectedProduct: JpegRuntimeProduct.FrameworkOnNativeCarrier,
            imageSize: ImageSize,
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkResourceCreationOccurrence? = beginResourceCreationCommon(
            jpegRuntimeOwner = jpegRuntimeOwner,
            clock = clock,
            expectedProduct = expectedProduct,
            imageSize = imageSize,
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            identity = identity,
        )

        internal fun beginResourceCreation(
            jpegRuntimeOwner: JpegRuntimeOwner,
            clock: EngineClock,
            expectedProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier,
            imageSize: ImageSize,
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkResourceCreationOccurrence? = beginResourceCreationCommon(
            jpegRuntimeOwner = jpegRuntimeOwner,
            clock = clock,
            expectedProduct = expectedProduct,
            imageSize = imageSize,
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            identity = identity,
        )

        internal fun settleResourceCreation(
            occurrence: FrameworkResourceCreationOccurrence,
            installAllowed: Boolean,
        ): FrameworkJpegOwner? {
            val gate = occurrence.operation.settlementGate
            val runtimeOwner = gate.withLock { occurrence.ownerBag.candidateOwner?.jpegRuntimeOwner } ?: return null
            if (!runtimeOwner.releaseJpegIoOperation(occurrence)) return null
            return gate.withLock {
                val exactCandidate = occurrence.ownerBag.candidateOwner ?: return@withLock null
                if (!exactCandidate.ownsCreation(occurrence)) return@withLock null
                if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
                val timelyComplete = occurrence.operation.returnCell.use == OperationReturnUse.Timely &&
                        occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal &&
                        occurrence.operation.returnCell.evidence.result == FrameworkResourceCreationResult.Complete
                if (!installAllowed || !timelyComplete) return@withLock null

                if (occurrence.operation.returnCell.evidence.returnedOwner !== exactCandidate || !exactCandidate.hasCompleteResources()) {
                    return@withLock null
                }
                if (!occurrence.clearSettledReferencesLocked(exactCandidate)) return@withLock null
                check(exactCandidate.clearCreation(occurrence))
                exactCandidate
            }
        }

        internal fun settleResourceCreationWithoutReturnedBitmap(
            occurrence: FrameworkResourceCreationOccurrence,
        ): Boolean = settleFrameworkResourceCreationWithoutReturnedBitmap(occurrence)

        internal fun beginReturnedOwnerRecycle(
            occurrence: FrameworkResourceCreationOccurrence,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkBitmapRecycleOccurrence? = beginReturnedOwnerFrameworkRecycle(occurrence, identity)

        internal fun beginTerminalReturnedOwnerRecycle(
            occurrence: FrameworkResourceCreationOccurrence,
            operationIdentity: Long,
        ): FrameworkBitmapRecycleOccurrence? = beginTerminalReturnedOwnerFrameworkRecycle(occurrence, operationIdentity)

        private fun beginResourceCreationCommon(
            jpegRuntimeOwner: JpegRuntimeOwner,
            clock: EngineClock,
            expectedProduct: JpegRuntimeProduct,
            imageSize: ImageSize,
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkResourceCreationOccurrence? {
            val width = imageSize.widthPx
            val height = imageSize.heightPx
            val topology = jpegRuntimeOwner.stableTopologySnapshot() ?: return null
            if (width <= 0 || height <= 0 || topology.product !== expectedProduct) return null

            val rowLong = 4L * width.toLong()
            if (rowLong !in 1L..Int.MAX_VALUE.toLong() || rowLong > Long.MAX_VALUE / height.toLong()) return null
            val pixelLong = rowLong * height.toLong()
            if (pixelLong !in 1L..Int.MAX_VALUE.toLong() || expectedProduct.carrier.byteCount.toLong() != pixelLong) {
                return null
            }

            val candidateOwner = FrameworkJpegOwner(
                imageSize = imageSize,
                rowByteCount = rowLong.toInt(),
                pixelByteCount = pixelLong.toInt(),
                jpegRuntimeOwner = jpegRuntimeOwner,
                clock = clock,
            )
            val occurrence = FrameworkResourceCreationOccurrence.create(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                expectedProduct = expectedProduct,
                identity = identity,
                candidateOwner = candidateOwner,
                clock = clock,
                signal = jpegRuntimeOwner.jpegIoSettlementSignal,
                endpoint = jpegRuntimeOwner.jpegExecutorEndpoint,
                work = ::executeResourceCreation,
            )
            if (!candidateOwner.reserveCreation(occurrence)) return null
            if (!jpegRuntimeOwner.submitJpegIoOperation(occurrence.executorOperation) &&
                occurrence.operation.submissionDisposition == OperationSubmissionDisposition.None
            ) {
                check(occurrence.operation.settlementGate.withLock {
                    occurrence.clearSettledReferencesLocked(candidateOwner)
                })
                check(candidateOwner.clearCreation(occurrence))
                throw FRAMEWORK_RESOURCE_CREATION_ADMISSION_FAILED
            }
            return occurrence
        }

        internal val BITMAP_ADOPTION_FAILED: IllegalStateException =
            IllegalStateException("Framework Bitmap ownership could not be adopted")
        internal val MALFORMED_BITMAP_METADATA: IllegalStateException =
            IllegalStateException("Framework Bitmap returned malformed metadata")
        internal val BITMAP_RESOURCE_COMPLETION_FAILED: IllegalStateException =
            IllegalStateException("Framework Bitmap resources could not be completed")
        internal val BITMAP_USE_NOT_OPERATIONAL: IllegalStateException =
            IllegalStateException("Framework Bitmap use is not operational")
        internal val BITMAP_USE_RESOLUTION_FAILED: IllegalStateException =
            IllegalStateException("Framework Bitmap use did not resolve")
        internal val CARRIER_USE_RESOLUTION_FAILED: IllegalStateException =
            IllegalStateException("Framework carrier use did not resolve")
        internal val RGBA_TRANSFER_REJECTED: IllegalStateException =
            IllegalStateException("Framework RGBA transfer rejected its exact input")
        internal val MALFORMED_FRAMEWORK_TRANSACTION: IllegalStateException =
            IllegalStateException("Framework JPEG transaction returned malformed evidence")
        internal val FRAMEWORK_ENCODE_ADMISSION_FAILED: IllegalStateException =
            IllegalStateException("Framework JPEG encode admission could not be unwound")
        internal val FRAMEWORK_RESOURCE_CREATION_ADMISSION_FAILED: IllegalStateException =
            IllegalStateException("Framework JPEG resource creation was not submitted")
        internal val BITMAP_RECYCLE_OWNER_MISSING: IllegalStateException =
            IllegalStateException("Framework Bitmap recycle owner is missing")
        internal val BITMAP_RECYCLE_ADMISSION_UNWIND_FAILED: IllegalStateException =
            IllegalStateException("Framework Bitmap recycle admission could not be unwound")
    }
}
