package io.screenstream.engine.internal.jpeg

import android.graphics.Bitmap
import android.os.Build
import io.screenstream.engine.internal.settlement.OperationEntryResult
import kotlin.concurrent.withLock

internal fun executeResourceCreation(occurrence: FrameworkResourceCreationOccurrence) {
    val entryResult = occurrence.operation.tryEnter()
    if (entryResult != OperationEntryResult.Entered) {
        if (entryResult == OperationEntryResult.InvalidDeadline) {
            occurrence.ownerBag.candidateOwner?.jpegRuntimeOwner?.jpegIoSettlementSignal?.signal()
        }
        return
    }

    val candidate = checkNotNull(occurrence.ownerBag.candidateOwner)
    candidate.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
    val evidence = occurrence.operation.returnCell.evidence
    val bitmap = try {
        Bitmap.createBitmap(candidate.imageSize.widthPx, candidate.imageSize.heightPx, Bitmap.Config.ARGB_8888)
    } catch (allocationFailure: OutOfMemoryError) {
        publishCreationFailure(occurrence, FrameworkResourceCreationResult.ResourceExhausted, allocationFailure)
        return
    } catch (failure: Exception) {
        publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, failure)
        return
    } catch (fatal: Error) {
        publishCreationFatalAndRethrow(occurrence, fatal)
    }

    val adopted = occurrence.operation.settlementGate.withLock {
        if (!candidate.adoptBitmap(bitmap)) return@withLock false
        evidence.returnedOwner = candidate
        true
    }
    if (!adopted) {
        publishCreationFailure(
            occurrence,
            FrameworkResourceCreationResult.InternalFailure,
            FrameworkJpegOwner.BITMAP_ADOPTION_FAILED,
        )
        return
    }

    var rowScratchAllocationEntered = false
    try {
        val actualWidth = bitmap.width
        val actualHeight = bitmap.height
        val actualConfig = bitmap.config
        val actualMutable = bitmap.isMutable
        val actualRecycled = bitmap.isRecycled
        val actualRowByteCount = bitmap.rowBytes
        val actualBitmapByteCount = bitmap.byteCount

        evidence.actualWidth = actualWidth
        evidence.actualHeight = actualHeight
        evidence.actualRowByteCount = actualRowByteCount
        evidence.actualBitmapByteCount = actualBitmapByteCount

        val rowStorageLong = actualRowByteCount.toLong() * actualHeight.toLong()
        val commonMetadataValid = actualWidth == candidate.imageSize.widthPx &&
                actualHeight == candidate.imageSize.heightPx && actualConfig == Bitmap.Config.ARGB_8888 &&
                actualMutable && !actualRecycled && actualRowByteCount >= candidate.rowByteCount &&
                actualBitmapByteCount >= candidate.pixelByteCount &&
                actualRowByteCount > 0 && actualBitmapByteCount > 0 &&
                actualHeight > 0 && rowStorageLong in 1L..actualBitmapByteCount.toLong()
        val apiMetadataValid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bitmap.config != Bitmap.Config.HARDWARE && bitmap.colorSpace?.isSrgb == true
        } else {
            true
        }
        val metadataRecorded = occurrence.operation.settlementGate.withLock {
            candidate.recordActualMetadata(
                width = actualWidth,
                height = actualHeight,
                config = actualConfig,
                mutable = actualMutable,
                recycled = actualRecycled,
                rowByteCount = actualRowByteCount,
                bitmapByteCount = actualBitmapByteCount,
                apiMetadataValid = apiMetadataValid,
            )
        }
        if (!metadataRecorded) {
            publishCreationFailure(
                occurrence,
                FrameworkResourceCreationResult.InternalFailure,
                FrameworkJpegOwner.BITMAP_RESOURCE_COMPLETION_FAILED,
            )
            return
        }
        if (!commonMetadataValid || !apiMetadataValid) {
            publishCreationFailure(
                occurrence,
                FrameworkResourceCreationResult.InternalFailure,
                FrameworkJpegOwner.MALFORMED_BITMAP_METADATA,
            )
            return
        }

        val transferMode = if (actualRowByteCount == candidate.rowByteCount && actualBitmapByteCount == candidate.pixelByteCount) {
            FrameworkTransferMode.TightBufferCopy
        } else {
            FrameworkTransferMode.PortableRowCopy
        }
        val rowScratch = if (transferMode == FrameworkTransferMode.PortableRowCopy) {
            rowScratchAllocationEntered = true
            val scratch = IntArray(candidate.imageSize.widthPx)
            rowScratchAllocationEntered = false
            scratch
        } else {
            null
        }
        if (!occurrence.operation.settlementGate.withLock { candidate.completeResources(transferMode, rowScratch) }) {
            publishCreationFailure(
                occurrence,
                FrameworkResourceCreationResult.InternalFailure,
                FrameworkJpegOwner.BITMAP_RESOURCE_COMPLETION_FAILED,
            )
            return
        }

        evidence.transferMode = transferMode
        evidence.result = FrameworkResourceCreationResult.Complete
        if (occurrence.operation.publishNormalReturn()) candidate.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
    } catch (allocationFailure: OutOfMemoryError) {
        if (rowScratchAllocationEntered) {
            publishCreationFailure(occurrence, FrameworkResourceCreationResult.ResourceExhausted, allocationFailure)
        } else {
            publishCreationFatalAndRethrow(occurrence, allocationFailure)
        }
    } catch (failure: Exception) {
        publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, failure)
    } catch (fatal: Error) {
        publishCreationFatalAndRethrow(occurrence, fatal)
    }
}

private fun publishCreationFailure(
    occurrence: FrameworkResourceCreationOccurrence,
    result: FrameworkResourceCreationResult,
    failure: Throwable,
) {
    val evidence = occurrence.operation.returnCell.evidence
    evidence.result = result
    evidence.failureCause = failure
    if (occurrence.operation.publishThrownReturn(failure)) {
        occurrence.ownerBag.candidateOwner?.jpegRuntimeOwner?.jpegIoSettlementSignal?.signal()
    }
}

private fun publishCreationFatalAndRethrow(occurrence: FrameworkResourceCreationOccurrence, fatal: Error): Nothing {
    try {
        publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, fatal)
    } finally {
        throw fatal
    }
}
