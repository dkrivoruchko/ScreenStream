package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.runtime.ElapsedRealtimeClock
import io.screenstream.engine.internal.storage.EncodedStorageFailureKind
import io.screenstream.engine.internal.storage.EncodedTransactionState
import io.screenstream.engine.internal.storage.ImmutableEncodedPayload
import io.screenstream.engine.internal.storage.NativeEncodedTransaction
import java.nio.ByteBuffer

internal const val jpegEnteredOperationSafetyNanos: Long = 15_000_000_000L

/** One admitted Native call and every managed owner that the call may still access. */
internal class NativeJpegProduction internal constructor(
    internal val runtime: EncoderRuntime,
    internal val record: ProductionRecord,
    internal val carrierLease: RgbaCarrierLease,
    internal val transaction: NativeEncodedTransaction,
    internal val quality: Int,
    internal val healthCell: NativeHealthCell,
) {
    private var resultBlockSlot: ByteBuffer? = null

    internal val hasResultBlock: Boolean
        get() = resultBlockSlot != null

    internal val resultBlockOrNull: ByteBuffer?
        get() = resultBlockSlot

    /** JPEG-role entry only: adopt the allocation before the JNI call can retain it through nonreturn. */
    internal fun allocateResultBlockForEntry(): ByteBuffer {
        check(resultBlockSlot == null)
        return newNativeResultBlock().also { resultBlockSlot = it }
    }

    init {
        require(carrierLease.record === record)
        require(carrierLease.carrier === runtime.carrier)
        require(transaction.isFreshOpen)
        require(quality in 0..100)
    }
}

internal sealed interface NativeJpegResult {
    val record: ProductionRecord
    val carrierLease: RgbaCarrierLease
    val transaction: NativeEncodedTransaction
    val resultBlock: ByteBuffer?
    val encodeDurationNanos: Long?
    val healthCell: NativeHealthCell
}

internal class NativeJpegSuccess internal constructor(
    override val record: ProductionRecord,
    override val carrierLease: RgbaCarrierLease,
    override val transaction: NativeEncodedTransaction,
    override val resultBlock: ByteBuffer,
    override val encodeDurationNanos: Long,
    override val healthCell: NativeHealthCell,
    internal val payload: ImmutableEncodedPayload,
) : NativeJpegResult {
    init {
        require(encodeDurationNanos >= 0L)
        require(transaction.state == EncodedTransactionState.Committed)
        require(transaction.committedPayload() === payload)
    }
}

internal enum class NativeJpegFailureKind {
    SafeCompressorAllocationFailure,
    RequiredAllocationExhaustion,
    InternalFailure,
}

internal class NativeJpegFailure internal constructor(
    override val record: ProductionRecord,
    override val carrierLease: RgbaCarrierLease,
    override val transaction: NativeEncodedTransaction,
    override val resultBlock: ByteBuffer?,
    override val encodeDurationNanos: Long,
    override val healthCell: NativeHealthCell,
    internal val kind: NativeJpegFailureKind,
    internal val cause: Throwable?,
    internal val returnedEvidence: NativeReturnedResult?,
) : NativeJpegResult {
    init {
        require(encodeDurationNanos >= 0L)
        require(transaction.state == EncodedTransactionState.Aborted)
        require(
            when (kind) {
                NativeJpegFailureKind.SafeCompressorAllocationFailure ->
                    cause == null && returnedEvidence is NativeReturnedResult.SafeCompressorAllocationFailure

                NativeJpegFailureKind.RequiredAllocationExhaustion ->
                    (returnedEvidence == null && cause is OutOfMemoryError) ||
                            returnedEvidence is NativeReturnedResult.NativeOutOfMemory ||
                            returnedEvidence is NativeReturnedResult.RequiredAllocationExhaustion ||
                            returnedEvidence is NativeReturnedResult.TransferComplete

                NativeJpegFailureKind.InternalFailure -> true
            },
        )
    }
}

internal class NativeJpegSkipped internal constructor(
    production: NativeJpegProduction,
) : NativeJpegResult {
    override val record: ProductionRecord = production.record
    override val carrierLease: RgbaCarrierLease = production.carrierLease
    override val transaction: NativeEncodedTransaction = production.transaction
    override val resultBlock: ByteBuffer? = null
    override val encodeDurationNanos: Long? = null
    override val healthCell: NativeHealthCell = production.healthCell

    init {
        require(!production.hasResultBlock)
        require(transaction.state == EncodedTransactionState.Aborted)
    }
}

/** Executes exactly one Native compressor call and returns only after JNI and carrier use really returned. */
internal fun executeNativeJpeg(
    production: NativeJpegProduction,
    clock: ElapsedRealtimeClock,
): NativeJpegResult {
    val runtime = production.runtime
    val transaction = production.transaction
    val pixels = runtime.enterNativeUse(production) ?: run {
        check(runtime.releaseNativeReadyBeforeEntry(production))
        abortNativeTransaction(transaction)
        throw IllegalStateException("Native production did not own its exact ready carrier")
    }

    var primaryThrow: Throwable? = null
    try {
        val startedAtNanos = clock.nanos()
        try {
            checkExactNativeInputs(production, pixels)
        } catch (failure: Exception) {
            val finishedAtNanos = clock.nanos()
            return preCallFailure(
                production = production,
                durationNanos = nonnegativeNativeElapsed(startedAtNanos, finishedAtNanos),
                kind = NativeJpegFailureKind.InternalFailure,
                cause = failure,
            )
        }
        val resultBlock = try {
            production.allocateResultBlockForEntry()
        } catch (allocationFailure: OutOfMemoryError) {
            val finishedAtNanos = clock.nanos()
            return preCallFailure(
                production = production,
                durationNanos = nonnegativeNativeElapsed(startedAtNanos, finishedAtNanos),
                kind = NativeJpegFailureKind.RequiredAllocationExhaustion,
                cause = allocationFailure,
            )
        } catch (failure: Exception) {
            val finishedAtNanos = clock.nanos()
            return preCallFailure(
                production = production,
                durationNanos = nonnegativeNativeElapsed(startedAtNanos, finishedAtNanos),
                kind = NativeJpegFailureKind.InternalFailure,
                cause = failure,
            )
        }
        var pendingThrowable: Throwable? = null
        try {
            NativeJpegProcess.compress(
                pixels = pixels,
                pixelByteCount = runtime.layout.byteCount.toLong(),
                width = runtime.layout.widthPx,
                height = runtime.layout.heightPx,
                stride = runtime.layout.rowByteCount,
                format = ANDROID_BITMAP_FORMAT_RGBA_8888,
                flags = ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE,
                dataspace = ADATASPACE_SRGB,
                compressFormat = ANDROID_BITMAP_COMPRESS_FORMAT_JPEG,
                quality = production.quality,
                sink = transaction.segmentSink,
                resultBlock = resultBlock,
            )
        } catch (returnedThrowable: Throwable) {
            pendingThrowable = returnedThrowable
        }
        val finishedAtNanos = clock.nanos()
        val durationNanos = nonnegativeNativeElapsed(startedAtNanos, finishedAtNanos)

        val returned = try {
            decodeReturnedNativeResult(resultBlock, transaction, pendingThrowable)
        } catch (fatal: Throwable) {
            primaryThrow = fatal
            abortNativeTransactionForAbruptReturn(transaction)
            throw fatal
        }
        return closeReturnedNativeResult(production, returned, durationNanos)
    } catch (fatal: Throwable) {
        primaryThrow = fatal
        abortNativeTransactionForAbruptReturn(transaction)
        throw fatal
    } finally {
        val releaseFailure = try {
            if (runtime.releaseNativeUseAfterReturn(production)) null
            else IllegalStateException("Native carrier use did not return exactly once")
        } catch (failure: Throwable) {
            failure
        }
        if (primaryThrow == null && releaseFailure != null) throw releaseFailure
    }
}

private fun closeReturnedNativeResult(
    production: NativeJpegProduction,
    returned: NativeReturnedResult,
    durationNanos: Long,
): NativeJpegResult = when (returned) {
    is NativeReturnedResult.TransferComplete -> closeNativeSuccess(production, returned, durationNanos)
    is NativeReturnedResult.SafeCompressorAllocationFailure -> nativeFailure(
        production,
        returned,
        durationNanos,
        NativeJpegFailureKind.SafeCompressorAllocationFailure,
        cause = null,
    )

    is NativeReturnedResult.NativeOutOfMemory -> nativeFailure(
        production,
        returned,
        durationNanos,
        NativeJpegFailureKind.RequiredAllocationExhaustion,
        cause = null,
    )

    is NativeReturnedResult.RequiredAllocationExhaustion -> nativeFailure(
        production,
        returned,
        durationNanos,
        NativeJpegFailureKind.RequiredAllocationExhaustion,
        returned.cause,
    )

    is NativeReturnedResult.InternalFailure -> nativeFailure(
        production,
        returned,
        durationNanos,
        NativeJpegFailureKind.InternalFailure,
        returned.cause,
    )
}

private fun closeNativeSuccess(
    production: NativeJpegProduction,
    returned: NativeReturnedResult.TransferComplete,
    durationNanos: Long,
): NativeJpegResult {
    val transaction = production.transaction
    transaction.closeNativeProducer()
    if (transaction.byteCount != returned.producedByteCount) {
        return nativeFailure(
            production,
            NativeReturnedResult.InternalFailure(
                cause = IllegalStateException("native and managed JPEG byte counts diverged before commit"),
                wireEvidence = NativeWireEvidence(0L, returned.producedByteCount.toLong(), transaction.byteCount),
                malformed = null,
            ),
            durationNanos,
            NativeJpegFailureKind.InternalFailure,
            IllegalStateException("native and managed JPEG byte counts diverged before commit"),
        )
    }
    if (!transaction.commit(production.runtime.layout.widthPx, production.runtime.layout.heightPx)) {
        val kind = checkNotNull(transaction.failureKind)
        val cause = checkNotNull(transaction.failureCause)
        val mechanical = when (kind) {
            EncodedStorageFailureKind.ResourceExhausted -> NativeJpegFailureKind.RequiredAllocationExhaustion
            EncodedStorageFailureKind.InternalFailure -> NativeJpegFailureKind.InternalFailure
        }
        return nativeFailure(
            production,
            returned,
            durationNanos,
            mechanical,
            cause,
        )
    }
    val payload = checkNotNull(transaction.committedPayload())
    return NativeJpegSuccess(
        record = production.record,
        carrierLease = production.carrierLease,
        transaction = transaction,
        resultBlock = checkNotNull(production.resultBlockOrNull),
        encodeDurationNanos = durationNanos,
        healthCell = production.healthCell,
        payload = payload,
    )
}

private fun nativeFailure(
    production: NativeJpegProduction,
    returned: NativeReturnedResult,
    durationNanos: Long,
    kind: NativeJpegFailureKind,
    cause: Throwable?,
): NativeJpegFailure {
    abortNativeTransaction(production.transaction)
    return NativeJpegFailure(
        record = production.record,
        carrierLease = production.carrierLease,
        transaction = production.transaction,
        resultBlock = checkNotNull(production.resultBlockOrNull),
        encodeDurationNanos = durationNanos,
        healthCell = production.healthCell,
        kind = kind,
        cause = cause,
        returnedEvidence = returned,
    )
}

private fun preCallFailure(
    production: NativeJpegProduction,
    durationNanos: Long,
    kind: NativeJpegFailureKind,
    cause: Throwable,
): NativeJpegFailure {
    abortNativeTransaction(production.transaction)
    return NativeJpegFailure(
        record = production.record,
        carrierLease = production.carrierLease,
        transaction = production.transaction,
        resultBlock = production.resultBlockOrNull,
        encodeDurationNanos = durationNanos,
        healthCell = production.healthCell,
        kind = kind,
        cause = cause,
        returnedEvidence = null,
    )
}

private fun checkExactNativeInputs(production: NativeJpegProduction, pixels: ByteBuffer) {
    val layout = production.runtime.layout
    check(production.runtime.backendProduct is NativeEnabled)
    check(production.healthCell.value == NativeHealth.Enabled)
    check(pixels.isDirect && !pixels.isReadOnly)
    check(pixels.position() == 0 && pixels.limit() == layout.byteCount && pixels.capacity() == layout.byteCount)
    check(production.quality in 0..100)
}

private fun abortNativeTransaction(transaction: NativeEncodedTransaction) {
    when (transaction.state) {
        EncodedTransactionState.Open,
        EncodedTransactionState.ProducerClosed,
        EncodedTransactionState.Faulted,
            -> check(transaction.abort())

        EncodedTransactionState.Aborted -> Unit
        EncodedTransactionState.Committed -> error("cannot abort a committed Native transaction")
    }
}

private fun abortNativeTransactionForAbruptReturn(transaction: NativeEncodedTransaction) {
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

private fun nonnegativeNativeElapsed(startedAtNanos: Long, finishedAtNanos: Long): Long {
    require(startedAtNanos >= 0L)
    require(finishedAtNanos >= startedAtNanos)
    return Math.subtractExact(finishedAtNanos, startedAtNanos)
}

// Frozen NDK enum values from android/bitmap.h and android/data_space.h.
private const val ANDROID_BITMAP_FORMAT_RGBA_8888: Int = 1
private const val ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE: Long = 1L
private const val ADATASPACE_SRGB: Int = 142_671_872
private const val ANDROID_BITMAP_COMPRESS_FORMAT_JPEG: Int = 0
