package io.screenstream.engine.internal.jpeg

import android.graphics.Bitmap
import android.os.Build
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class BitmapReturnedOwner internal constructor() : OperationReturnedOwner {
    private enum class State {
        Empty,
        BitmapOwned,
        CompleteUninstalled,
        Installed,
        Recycling,
        Recycled,
    }

    private val gate: ReentrantLock = ReentrantLock(false)

    private var state: State = State.Empty
    private var bitmap: Bitmap? = null
    private var rowScratch: IntArray? = null
    private var selectedTransferMode: FrameworkTransferMode? = null
    private var actualWidth: Int = 0
    private var actualHeight: Int = 0
    private var actualConfig: Bitmap.Config? = null
    private var actualMutable: Boolean = false
    private var actualRecycled: Boolean = true
    private var actualRowByteCount: Int = 0
    private var actualBitmapByteCount: Int = 0
    private var actualApiMetadataValid: Boolean = false
    private var admissionOpen: Boolean = false
    private var activeEncode: FrameworkEncodeOccurrence? = null
    private var bitmapUses: Int = 0
    private var recycleOccurrence: FrameworkBitmapRecycleOccurrence? = null

    internal fun adoptBitmap(returnedBitmap: Bitmap): Boolean = gate.withLock {
        if (state != State.Empty || bitmap != null) return@withLock false

        bitmap = returnedBitmap
        state = State.BitmapOwned
        true
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
    ): Boolean = gate.withLock {
        if (state != State.BitmapOwned || bitmap == null || actualConfig != null || actualWidth != 0 ||
            actualHeight != 0 || actualRowByteCount != 0 || actualBitmapByteCount != 0
        ) {
            return@withLock false
        }

        actualWidth = width
        actualHeight = height
        actualConfig = config
        actualMutable = mutable
        actualRecycled = recycled
        actualRowByteCount = rowByteCount
        actualBitmapByteCount = bitmapByteCount
        actualApiMetadataValid = apiMetadataValid
        true
    }

    internal fun completeResources(transferMode: FrameworkTransferMode, scratch: IntArray?): Boolean = gate.withLock {
        if (state != State.BitmapOwned || bitmap == null || selectedTransferMode != null || rowScratch != null ||
            (transferMode == FrameworkTransferMode.TightBufferCopy && scratch != null) ||
            (transferMode == FrameworkTransferMode.PortableRowCopy && scratch == null) || actualWidth <= 0 ||
            actualHeight <= 0 || actualConfig != Bitmap.Config.ARGB_8888 || !actualMutable || actualRecycled ||
            actualRowByteCount <= 0 || actualBitmapByteCount <= 0 || !actualApiMetadataValid
        ) {
            return@withLock false
        }

        selectedTransferMode = transferMode
        rowScratch = scratch
        state = State.CompleteUninstalled
        true
    }

    internal fun install(): Boolean = gate.withLock {
        if (state != State.CompleteUninstalled || bitmap == null || selectedTransferMode == null ||
            actualWidth <= 0 || actualHeight <= 0 || actualConfig != Bitmap.Config.ARGB_8888 || !actualMutable ||
            actualRecycled || actualRowByteCount <= 0 || actualBitmapByteCount <= 0 || !actualApiMetadataValid ||
            recycleOccurrence != null
        ) {
            return@withLock false
        }

        state = State.Installed
        admissionOpen = true
        true
    }

    internal fun transferMode(): FrameworkTransferMode? = gate.withLock {
        if (state != State.Installed) null else selectedTransferMode
    }

    internal fun isInstalledAndHealthy(): Boolean = gate.withLock {
        state == State.Installed && bitmap != null && selectedTransferMode != null && recycleOccurrence == null
    }

    internal fun admitEncode(occurrence: FrameworkEncodeOccurrence): Boolean = gate.withLock {
        if (state != State.Installed || !admissionOpen || bitmap == null || activeEncode != null || recycleOccurrence != null) {
            return@withLock false
        }

        activeEncode = occurrence
        true
    }

    internal fun cancelEncodeAdmission(occurrence: FrameworkEncodeOccurrence): Boolean = gate.withLock {
        if (activeEncode !== occurrence || bitmapUses != 0) return@withLock false

        activeEncode = null
        true
    }

    internal fun enterBitmapUse(occurrence: FrameworkEncodeOccurrence): Bitmap? = gate.withLock {
        if (state != State.Installed || activeEncode !== occurrence || bitmapUses != 0 || recycleOccurrence != null) {
            return@withLock null
        }

        val currentBitmap = bitmap ?: return@withLock null
        bitmapUses = 1
        currentBitmap
    }

    internal fun exitBitmapUse(occurrence: FrameworkEncodeOccurrence): Boolean = gate.withLock {
        if (activeEncode !== occurrence || bitmapUses != 1) return@withLock false

        bitmapUses = 0
        true
    }

    internal fun completeEncode(occurrence: FrameworkEncodeOccurrence): Boolean = gate.withLock {
        if (activeEncode !== occurrence || bitmapUses != 0) return@withLock false

        activeEncode = null
        true
    }

    internal fun closeAdmissionAndCheckDrained(): Boolean = gate.withLock {
        if (state != State.Installed) return@withLock false

        admissionOpen = false
        activeEncode == null && bitmapUses == 0
    }

    internal fun hasNoBitmap(): Boolean = gate.withLock {
        state == State.Empty && bitmap == null
    }

    internal fun ownsBitmapForCleanup(): Boolean = gate.withLock {
        (state == State.BitmapOwned || state == State.CompleteUninstalled) && bitmap != null &&
                activeEncode == null && bitmapUses == 0 && recycleOccurrence == null
    }

    internal fun admitRecycle(occurrence: FrameworkBitmapRecycleOccurrence, installedOwner: Boolean): Boolean = gate.withLock {
        val ready = if (installedOwner) {
            state == State.Installed && !admissionOpen && activeEncode == null && bitmapUses == 0
        } else {
            (state == State.BitmapOwned || state == State.CompleteUninstalled) && activeEncode == null && bitmapUses == 0
        }
        if (!ready || bitmap == null || recycleOccurrence != null) return@withLock false

        recycleOccurrence = occurrence
        state = State.Recycling
        true
    }

    internal fun bitmapForRecycle(occurrence: FrameworkBitmapRecycleOccurrence): Bitmap? = gate.withLock {
        if (state != State.Recycling || recycleOccurrence !== occurrence) null else bitmap
    }

    internal fun completeRecycle(occurrence: FrameworkBitmapRecycleOccurrence): Boolean = gate.withLock {
        if (state != State.Recycling || recycleOccurrence !== occurrence || bitmap == null) return@withLock false

        bitmap = null
        rowScratch = null
        selectedTransferMode = null
        actualWidth = 0
        actualHeight = 0
        actualConfig = null
        actualMutable = false
        actualRecycled = true
        actualRowByteCount = 0
        actualBitmapByteCount = 0
        actualApiMetadataValid = false
        admissionOpen = false
        recycleOccurrence = null
        state = State.Recycled
        true
    }

    internal fun bitmapForTransfer(): Bitmap? = gate.withLock {
        if (state != State.Installed || bitmapUses != 1 || recycleOccurrence != null) null else bitmap
    }

    internal fun rowScratchForTransfer(): IntArray? = gate.withLock {
        if (state != State.Installed || bitmapUses != 1 || recycleOccurrence != null) null else rowScratch
    }
}

internal fun executeResourceCreation(occurrence: FrameworkResourceCreationOccurrence) {
    val entryResult = occurrence.operation.tryEnter()
    if (entryResult != OperationEntryResult.Entered) {
        if (entryResult == OperationEntryResult.InvalidDeadline) {
            occurrence.ownerBag.candidateOwner?.jpegRuntimeOwner?.jpegIoSettlementSignal?.signal()
        }
        return
    }

    val candidate = checkNotNull(occurrence.ownerBag.candidateOwner)
    val evidence = occurrence.operation.returnCell.evidence
    val bitmap = try {
        Bitmap.createBitmap(candidate.imageSize.widthPx, candidate.imageSize.heightPx, Bitmap.Config.ARGB_8888)
    } catch (allocationFailure: OutOfMemoryError) {
        publishCreationFailure(occurrence, FrameworkResourceCreationResult.ResourceExhausted, allocationFailure)
        return
    } catch (failure: Exception) {
        publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, failure)
        return
    }

    if (!candidate.bitmapOwner.adoptBitmap(bitmap)) {
        publishCreationFailure(
            occurrence,
            FrameworkResourceCreationResult.InternalFailure,
            FrameworkJpegOwner.BITMAP_ADOPTION_FAILED,
        )
        return
    }
    evidence.returnedOwner = candidate.bitmapOwner

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
        val apiMetadataValid = when {
            occurrence.sdkInt < Build.VERSION_CODES.O -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                bitmap.config != Bitmap.Config.HARDWARE && bitmap.colorSpace?.isSrgb == true

            else -> false
        }
        if (!candidate.bitmapOwner.recordActualMetadata(
                width = actualWidth,
                height = actualHeight,
                config = actualConfig,
                mutable = actualMutable,
                recycled = actualRecycled,
                rowByteCount = actualRowByteCount,
                bitmapByteCount = actualBitmapByteCount,
                apiMetadataValid = apiMetadataValid,
            )
        ) {
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
            IntArray(candidate.imageSize.widthPx)
        } else {
            null
        }
        if (!candidate.bitmapOwner.completeResources(transferMode, rowScratch)) {
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
        publishCreationFailure(occurrence, FrameworkResourceCreationResult.ResourceExhausted, allocationFailure)
    } catch (failure: Exception) {
        publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, failure)
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
