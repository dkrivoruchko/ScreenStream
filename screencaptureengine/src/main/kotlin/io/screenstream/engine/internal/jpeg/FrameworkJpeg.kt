package io.screenstream.engine.internal.jpeg

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.internal.runtime.ElapsedRealtimeClock
import io.screenstream.engine.internal.storage.EncodedStorageFailureKind
import io.screenstream.engine.internal.storage.EncodedTransactionState
import io.screenstream.engine.internal.storage.FrameworkEncodedTransaction
import io.screenstream.engine.internal.storage.ImmutableEncodedPayload
import java.nio.ByteBuffer

internal enum class BitmapTransferMode {
    TightRgbaCopy,
    RgbaRowConversion,
}

internal class FrameworkBitmapMetadata internal constructor(
    internal val widthPx: Int,
    internal val heightPx: Int,
    internal val rowByteCount: Int,
    internal val byteCount: Int,
    internal val allocationByteCount: Int,
    internal val config: Bitmap.Config,
    internal val isMutable: Boolean,
    internal val isSrgb: Boolean,
    internal val transferMode: BitmapTransferMode,
)

internal sealed interface FrameworkBitmapCreation {
    class Created internal constructor(
        internal val owner: FrameworkBitmapOwner,
    ) : FrameworkBitmapCreation

    class Failed internal constructor(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable,
        internal val ownerResidue: FrameworkBitmapOwner?,
    ) : FrameworkBitmapCreation
}

internal sealed interface FrameworkBitmapRetirement {
    data object Closed : FrameworkBitmapRetirement

    class Retained internal constructor(
        internal val cause: Throwable?,
    ) : FrameworkBitmapRetirement
}

/** Exact owner of the sole reusable Framework Bitmap and, only when required, one row scratch. */
internal class FrameworkBitmapOwner private constructor(
    internal val layout: RgbaLayout,
) {
    private var bitmap: Bitmap? = null
    private var metadataSlot: FrameworkBitmapMetadata? = null
    private var rowScratch: IntArray? = null
    private var useCount: Int = 0
    private var recycleAttempted: Boolean = false
    private var recycleFailure: Throwable? = null

    private fun adoptBitmap(candidate: Bitmap) {
        check(bitmap == null && metadataSlot == null && !recycleAttempted)
        bitmap = candidate
    }

    internal val metadata: FrameworkBitmapMetadata
        get() = checkNotNull(metadataSlot)

    internal fun isComplete(): Boolean {
        if (bitmap == null) return false
        val exactMetadata = metadataSlot ?: return false
        return !recycleAttempted &&
                exactMetadata.widthPx == layout.widthPx && exactMetadata.heightPx == layout.heightPx &&
                when (exactMetadata.transferMode) {
                    BitmapTransferMode.TightRgbaCopy -> rowScratch == null
                    BitmapTransferMode.RgbaRowConversion -> rowScratch?.size == layout.widthPx
                }
    }

    internal fun isInUse(): Boolean = useCount != 0

    internal fun beginUse(): Boolean {
        if (useCount != 0 || !isComplete()) return false
        useCount = 1
        return true
    }

    /** Performs the only RGBA-to-Bitmap transfer for an admitted production. */
    internal fun transferExactRgba(source: ByteBuffer) {
        check(useCount == 1)
        check(source.isDirect && !source.isReadOnly)
        check(source.position() == 0 && source.limit() == layout.byteCount && source.capacity() == layout.byteCount)
        val exactBitmap = checkNotNull(bitmap)
        when (metadata.transferMode) {
            BitmapTransferMode.TightRgbaCopy -> exactBitmap.copyPixelsFromBuffer(source)
            BitmapTransferMode.RgbaRowConversion -> {
                val row = checkNotNull(rowScratch)
                for (y in 0 until layout.heightPx) {
                    val sourceRowOffset = Math.multiplyExact(y, layout.rowByteCount)
                    for (x in 0 until layout.widthPx) {
                        val pixelOffset = sourceRowOffset + x * RGBA_BYTES_PER_PIXEL
                        val red = source[pixelOffset].toInt() and 0xFF
                        val green = source[pixelOffset + 1].toInt() and 0xFF
                        val blue = source[pixelOffset + 2].toInt() and 0xFF
                        row[x] = OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
                    }
                    exactBitmap.setPixels(row, 0, layout.widthPx, 0, y, layout.widthPx, 1)
                }
            }
        }
    }

    /** Invokes Bitmap.compress exactly once and writes directly into the attached transaction. */
    internal fun compressOnce(quality: Int, transaction: FrameworkEncodedTransaction): Boolean {
        check(useCount == 1)
        require(quality in JPEG_QUALITY_RANGE)
        return checkNotNull(bitmap).compress(Bitmap.CompressFormat.JPEG, quality, transaction.outputStream)
    }

    internal fun finishUse(): Boolean {
        if (useCount != 1) return false
        useCount = 0
        return true
    }

    /** Calls Bitmap.recycle at most once and clears the owner only after that call really returns. */
    internal fun retireIfIdle(): FrameworkBitmapRetirement {
        if (useCount != 0) return FrameworkBitmapRetirement.Retained(null)
        val exactBitmap = bitmap ?: return FrameworkBitmapRetirement.Closed
        if (recycleAttempted) return FrameworkBitmapRetirement.Retained(recycleFailure)
        recycleAttempted = true
        try {
            exactBitmap.recycle()
            if (!exactBitmap.isRecycled) {
                val failure = IllegalStateException("Bitmap.recycle returned without recycled evidence")
                recycleFailure = failure
                return FrameworkBitmapRetirement.Retained(failure)
            }
        } catch (failure: Exception) {
            recycleFailure = failure
            return FrameworkBitmapRetirement.Retained(failure)
        } catch (fatal: Throwable) {
            recycleFailure = fatal
            throw fatal
        }
        bitmap = null
        metadataSlot = null
        rowScratch = null
        return FrameworkBitmapRetirement.Closed
    }

    private fun inspectAndComplete() {
        check(metadataSlot == null && rowScratch == null && useCount == 0 && !recycleAttempted)
        val exactBitmap = checkNotNull(bitmap)
        val actualWidth = exactBitmap.width
        val actualHeight = exactBitmap.height
        val actualConfig = checkNotNull(exactBitmap.config)
        val actualMutable = exactBitmap.isMutable
        val actualRowByteCount = exactBitmap.rowBytes
        val actualByteCount = exactBitmap.byteCount
        val actualAllocationByteCount = exactBitmap.allocationByteCount
        val actualIsSrgb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            actualConfig != Bitmap.Config.HARDWARE &&
                    exactBitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
        } else {
            true
        }

        check(actualWidth == layout.widthPx)
        check(actualHeight == layout.heightPx)
        check(actualConfig == Bitmap.Config.ARGB_8888)
        check(actualMutable)
        check(!exactBitmap.isRecycled)
        check(actualIsSrgb)
        check(actualRowByteCount >= 0)
        check(actualByteCount >= 0)
        check(actualAllocationByteCount >= 0)
        check(actualRowByteCount >= layout.rowByteCount)
        val checkedBitmapByteCount = Math.multiplyExact(actualRowByteCount.toLong(), actualHeight.toLong())
        check(checkedBitmapByteCount <= Int.MAX_VALUE.toLong())
        check(checkedBitmapByteCount == actualByteCount.toLong())
        check(actualByteCount <= actualAllocationByteCount)

        val transferMode = if (actualRowByteCount == layout.rowByteCount) {
            BitmapTransferMode.TightRgbaCopy
        } else {
            BitmapTransferMode.RgbaRowConversion
        }
        val exactRowScratch = if (transferMode == BitmapTransferMode.RgbaRowConversion) {
            IntArray(layout.widthPx)
        } else {
            null
        }
        metadataSlot = FrameworkBitmapMetadata(
            widthPx = actualWidth,
            heightPx = actualHeight,
            rowByteCount = actualRowByteCount,
            byteCount = actualByteCount,
            allocationByteCount = actualAllocationByteCount,
            config = actualConfig,
            isMutable = actualMutable,
            isSrgb = actualIsSrgb,
            transferMode = transferMode,
        )
        rowScratch = exactRowScratch
    }

    internal companion object {
        internal fun create(layout: RgbaLayout): FrameworkBitmapCreation {
            val owner = FrameworkBitmapOwner(layout)
            return try {
                val bitmap = Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888)
                owner.adoptBitmap(bitmap)
                owner.inspectAndComplete()
                FrameworkBitmapCreation.Created(owner)
            } catch (allocationFailure: OutOfMemoryError) {
                FrameworkBitmapCreation.Failed(
                    problem = ScreenCaptureProblem.ResourceExhausted,
                    cause = allocationFailure,
                    ownerResidue = owner.takeIf { it.bitmap != null },
                )
            } catch (failure: Exception) {
                FrameworkBitmapCreation.Failed(
                    problem = ScreenCaptureProblem.InternalFailure,
                    cause = failure,
                    ownerResidue = owner.takeIf { it.bitmap != null },
                )
            }
        }

        private const val RGBA_BYTES_PER_PIXEL: Int = 4
        private const val OPAQUE_ALPHA: Int = -0x1000000
        private val JPEG_QUALITY_RANGE: IntRange = 0..100
    }
}

internal enum class FrameworkJpegProblem {
    CompressionRejected,
    ResourceExhausted,
    InternalFailure,
    SkippedBeforeEntry,
}

internal sealed interface FrameworkJpegResult {
    val configRevision: Long
    val productionId: Long
    val transaction: FrameworkEncodedTransaction
}

internal class FrameworkJpegSuccess internal constructor(
    record: ProductionRecord,
    override val transaction: FrameworkEncodedTransaction,
    internal val payload: ImmutableEncodedPayload,
    internal val encodeDurationNanos: Long,
) : FrameworkJpegResult {
    override val configRevision: Long = record.configRevision
    override val productionId: Long = record.productionId

    init {
        require(encodeDurationNanos >= 0L)
        require(transaction.state == EncodedTransactionState.Committed)
        require(transaction.committedPayload() === payload)
    }
}

internal class FrameworkJpegFailure internal constructor(
    record: ProductionRecord,
    override val transaction: FrameworkEncodedTransaction,
    internal val jpegProblem: FrameworkJpegProblem,
    internal val problem: ScreenCaptureProblem?,
    internal val cause: Throwable?,
) : FrameworkJpegResult {
    override val configRevision: Long = record.configRevision
    override val productionId: Long = record.productionId

    init {
        require(jpegProblem != FrameworkJpegProblem.SkippedBeforeEntry)
        require(
            when (jpegProblem) {
                FrameworkJpegProblem.CompressionRejected -> problem == null && cause == null
                FrameworkJpegProblem.ResourceExhausted -> problem == ScreenCaptureProblem.ResourceExhausted
                FrameworkJpegProblem.InternalFailure -> problem == ScreenCaptureProblem.InternalFailure
                FrameworkJpegProblem.SkippedBeforeEntry -> false
            },
        )
        require(transaction.state == EncodedTransactionState.Aborted)
    }
}

internal class FrameworkJpegSkipped internal constructor(
    record: ProductionRecord,
    override val transaction: FrameworkEncodedTransaction,
) : FrameworkJpegResult {
    override val configRevision: Long = record.configRevision
    override val productionId: Long = record.productionId
    internal val jpegProblem: FrameworkJpegProblem = FrameworkJpegProblem.SkippedBeforeEntry

    init {
        require(transaction.state == EncodedTransactionState.Aborted)
    }
}

/** Runs one already-admitted Framework production; it owns no policy or session state. */
internal fun executeFrameworkJpeg(
    production: FrameworkProduction,
    clock: ElapsedRealtimeClock,
): FrameworkJpegResult {
    val runtime = production.runtime
    val transaction = production.transaction
    val carrierBuffer = try {
        runtime.enterFrameworkUse(production)
    } catch (failure: Exception) {
        check(runtime.releaseReadyAfterRejectedAdmission(production))
        return internalFailure(production, failure)
    }
        ?: run {
            check(runtime.releaseReadyAfterRejectedAdmission(production))
            return internalFailure(
                production,
                IllegalStateException("Framework production did not own its exact ready carrier"),
            )
        }
    var bitmapUseStarted = false
    var primaryThrow: Throwable? = null

    try {
        val bitmapUseAcquired = try {
            runtime.bitmapOwner.beginUse()
        } catch (failure: Exception) {
            return internalFailure(production, failure)
        }
        if (!bitmapUseAcquired) {
            return internalFailure(
                production,
                IllegalStateException("Framework Bitmap was not available for the admitted production"),
            )
        }
        bitmapUseStarted = true
        val startedAtNanos = try {
            clock.nanos()
        } catch (failure: Exception) {
            return internalFailure(production, failure)
        }

        try {
            runtime.bitmapOwner.transferExactRgba(carrierBuffer)
        } catch (allocationFailure: OutOfMemoryError) {
            abortForAbruptReturn(transaction)
            throw allocationFailure
        } catch (failure: Exception) {
            return internalFailure(production, failure)
        }

        val compressed = try {
            runtime.bitmapOwner.compressOnce(production.quality, transaction)
        } catch (allocationFailure: OutOfMemoryError) {
            return transactionFailureOr(
                production = production,
                defaultProblem = ScreenCaptureProblem.ResourceExhausted,
                defaultCause = allocationFailure,
            )
        } catch (failure: Exception) {
            return transactionFailureOr(
                production = production,
                defaultProblem = ScreenCaptureProblem.InternalFailure,
                defaultCause = failure,
            )
        }

        try {
            transaction.outputStream.close()
        } catch (allocationFailure: OutOfMemoryError) {
            return transactionFailureOr(production, ScreenCaptureProblem.ResourceExhausted, allocationFailure)
        } catch (failure: Exception) {
            return transactionFailureOr(production, ScreenCaptureProblem.InternalFailure, failure)
        }

        if (transaction.state == EncodedTransactionState.Faulted) return transactionFailure(production)
        if (!compressed) {
            check(transaction.abort())
            return FrameworkJpegFailure(
                record = production.record,
                transaction = transaction,
                jpegProblem = FrameworkJpegProblem.CompressionRejected,
                problem = null,
                cause = null,
            )
        }

        val finishedAtNanos = try {
            clock.nanos()
        } catch (failure: Exception) {
            return internalFailure(production, failure)
        }
        val durationNanos = try {
            nonnegativeElapsed(startedAtNanos, finishedAtNanos)
        } catch (failure: Exception) {
            return internalFailure(production, failure)
        }

        if (!transaction.commit(runtime.layout.widthPx, runtime.layout.heightPx)) {
            return transactionFailure(production)
        }
        val payload = checkNotNull(transaction.committedPayload())
        return FrameworkJpegSuccess(production.record, transaction, payload, durationNanos)
    } catch (fatal: Throwable) {
        primaryThrow = fatal
        abortForAbruptReturn(transaction)
        throw fatal
    } finally {
        var cleanupFailure: Throwable? = null
        if (bitmapUseStarted) {
            try {
                if (!runtime.bitmapOwner.finishUse()) {
                    cleanupFailure = IllegalStateException("Framework Bitmap use did not return exactly once")
                }
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
        }
        try {
            if (!runtime.releaseFrameworkUseAfterReturn(production) && cleanupFailure == null) {
                cleanupFailure = IllegalStateException("Framework carrier use did not return exactly once")
            }
        } catch (failure: Throwable) {
            if (cleanupFailure == null) cleanupFailure = failure
        }
        if (primaryThrow == null && cleanupFailure != null) throw cleanupFailure
    }
}

private fun transactionFailureOr(
    production: FrameworkProduction,
    defaultProblem: ScreenCaptureProblem,
    defaultCause: Throwable,
): FrameworkJpegFailure {
    if (production.transaction.state == EncodedTransactionState.Faulted) return transactionFailure(production)
    abortOpenTransaction(production.transaction)
    return failure(production, defaultProblem, defaultCause)
}

private fun transactionFailure(production: FrameworkProduction): FrameworkJpegFailure {
    val transaction = production.transaction
    val failureKind = checkNotNull(transaction.failureKind)
    val cause = checkNotNull(transaction.failureCause)
    check(transaction.abort())
    return when (failureKind) {
        EncodedStorageFailureKind.ResourceExhausted ->
            failure(production, ScreenCaptureProblem.ResourceExhausted, cause)

        EncodedStorageFailureKind.InternalFailure ->
            failure(production, ScreenCaptureProblem.InternalFailure, cause)
    }
}

private fun internalFailure(production: FrameworkProduction, cause: Throwable): FrameworkJpegFailure {
    abortOpenTransaction(production.transaction)
    return failure(production, ScreenCaptureProblem.InternalFailure, cause)
}

private fun failure(
    production: FrameworkProduction,
    problem: ScreenCaptureProblem,
    cause: Throwable,
): FrameworkJpegFailure = FrameworkJpegFailure(
    record = production.record,
    transaction = production.transaction,
    jpegProblem = when (problem) {
        ScreenCaptureProblem.ResourceExhausted -> FrameworkJpegProblem.ResourceExhausted
        else -> FrameworkJpegProblem.InternalFailure
    },
    problem = problem,
    cause = cause,
)

private fun abortOpenTransaction(transaction: FrameworkEncodedTransaction) {
    when (transaction.state) {
        EncodedTransactionState.Open,
        EncodedTransactionState.ProducerClosed,
        EncodedTransactionState.Faulted,
            -> check(transaction.abort())

        EncodedTransactionState.Aborted -> Unit
        EncodedTransactionState.Committed -> error("cannot abort a committed Framework transaction")
    }
}

private fun abortForAbruptReturn(transaction: FrameworkEncodedTransaction) {
    when (transaction.state) {
        EncodedTransactionState.Open,
        EncodedTransactionState.ProducerClosed,
        EncodedTransactionState.Faulted,
            -> transaction.abort()

        EncodedTransactionState.Aborted,
        EncodedTransactionState.Committed,
            -> Unit
    }
}

private fun nonnegativeElapsed(startedAtNanos: Long, finishedAtNanos: Long): Long {
    require(startedAtNanos >= 0L)
    require(finishedAtNanos >= startedAtNanos)
    return Math.subtractExact(finishedAtNanos, startedAtNanos)
}
