package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.ImageSize
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import kotlin.concurrent.withLock

internal class FrameworkJpegOwner private constructor(
    internal val imageSize: ImageSize,
    internal val rowByteCount: Int,
    internal val pixelByteCount: Int,
    internal val jpegRuntimeOwner: JpegRuntimeOwner,
    internal val clock: EngineClock,
    internal val bitmapOwner: BitmapReturnedOwner,
) {
    internal val transferMode: FrameworkTransferMode
        get() = checkNotNull(bitmapOwner.transferMode())

    internal fun isShapeCompatible(expectedImageSize: ImageSize, expectedCarrierByteCount: Int): Boolean =
        expectedCarrierByteCount == pixelByteCount && expectedImageSize == imageSize && bitmapOwner.isInstalledAndHealthy()

    internal fun beginEncode(
        expectedProduct: JpegRuntimeProduct.FrameworkOnNativeCarrier,
        expectedLease: NativeMallocCarrierLease,
        storage: EncodedStorageOwner,
        quality: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): FrameworkEncodeOccurrence? = beginFrameworkEncode(
        owner = this,
        expectedProduct = expectedProduct,
        expectedLease = expectedLease,
        storage = storage,
        quality = quality,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        identity = identity,
    )

    internal fun beginEncode(
        expectedProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier,
        expectedLease: ManagedDirectCarrierLease,
        storage: EncodedStorageOwner,
        quality: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): FrameworkEncodeOccurrence? = beginFrameworkEncode(
        owner = this,
        expectedProduct = expectedProduct,
        expectedLease = expectedLease,
        storage = storage,
        quality = quality,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        identity = identity,
    )

    internal fun settleEncode(
        occurrence: FrameworkEncodeOccurrence,
        storage: EncodedStorageOwner,
        retainCommittedFrame: Boolean,
    ): FrameworkEncodeSettlement = settleFrameworkEncode(
        owner = this,
        occurrence = occurrence,
        storage = storage,
        retainCommittedFrame = retainCommittedFrame,
    )

    internal fun closeAdmissionAndCheckDrained(): Boolean = bitmapOwner.closeAdmissionAndCheckDrained()

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
            sdkInt: Int,
            expectedProduct: JpegRuntimeProduct.FrameworkOnNativeCarrier,
            imageSize: ImageSize,
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkResourceCreationOccurrence? = beginResourceCreationCommon(
            jpegRuntimeOwner = jpegRuntimeOwner,
            clock = clock,
            sdkInt = sdkInt,
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
            sdkInt: Int,
            expectedProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier,
            imageSize: ImageSize,
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkResourceCreationOccurrence? = beginResourceCreationCommon(
            jpegRuntimeOwner = jpegRuntimeOwner,
            clock = clock,
            sdkInt = sdkInt,
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
            return gate.withLock {
                if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
                val timelyComplete = occurrence.operation.returnCell.use == OperationReturnUse.Timely &&
                        occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal &&
                        occurrence.operation.returnCell.evidence.result == FrameworkResourceCreationResult.Complete
                if (!installAllowed || !timelyComplete) return@withLock null

                val exactCandidate = occurrence.ownerBag.candidateOwner ?: return@withLock null
                if (!exactCandidate.bitmapOwner.install()) return@withLock null
                occurrence.ownerBag.candidateOwner = null
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
            sdkInt: Int,
            expectedProduct: JpegRuntimeProduct,
            imageSize: ImageSize,
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkResourceCreationOccurrence? {
            val width = imageSize.widthPx
            val height = imageSize.heightPx
            if (width <= 0 || height <= 0 || jpegRuntimeOwner.product !== expectedProduct) return null

            val rowLong = 4L * width.toLong()
            if (rowLong !in 1L..Int.MAX_VALUE.toLong() || rowLong > Long.MAX_VALUE / height.toLong()) return null
            val pixelLong = rowLong * height.toLong()
            if (pixelLong !in 1L..Int.MAX_VALUE.toLong() || expectedProduct.carrier.byteCount.toLong() != pixelLong) {
                return null
            }

            val bitmapReturnedOwner = BitmapReturnedOwner()
            val candidateOwner = FrameworkJpegOwner(
                imageSize = imageSize,
                rowByteCount = rowLong.toInt(),
                pixelByteCount = pixelLong.toInt(),
                jpegRuntimeOwner = jpegRuntimeOwner,
                clock = clock,
                bitmapOwner = bitmapReturnedOwner,
            )
            val occurrence = FrameworkResourceCreationOccurrence.create(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                expectedProduct = expectedProduct,
                sdkInt = sdkInt,
                identity = identity,
                candidateOwner = candidateOwner,
                clock = clock,
                signal = jpegRuntimeOwner.jpegIoSettlementSignal,
                work = ::executeResourceCreation,
            )
            jpegRuntimeOwner.submitJpegIoOperation(occurrence.ioOperation)
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
        internal val FRAMEWORK_STORAGE_OUT_OF_MEMORY: OutOfMemoryError =
            OutOfMemoryError("Framework JPEG transaction exhausted storage")
        internal val FRAMEWORK_ENCODE_ADMISSION_FAILED: IllegalStateException =
            IllegalStateException("Framework JPEG encode admission could not be unwound")
        internal val BITMAP_RECYCLE_OWNER_MISSING: IllegalStateException =
            IllegalStateException("Framework Bitmap recycle owner is missing")
    }
}
