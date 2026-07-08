package dev.dmkr.screencaptureengine.internal.encoding.provider

import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.JpegImageEncoderProvider
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

internal const val ENCODER_CREATE_TIMEOUT_MS: Long = 3_000L
internal const val MAX_QUARANTINED_PROVIDER_WORKERS: Int = 2

/**
 * Isolates synchronous provider construction from lifecycle-critical and GL/encoder lanes.
 *
 * Admission is bounded, each create call is fenced by a provider-preparation token, and stale or
 * late encoder results are closed on the isolated cleanup executor. Cancellation attempts are
 * best-effort; lifecycle rollback does not wait for app/provider code to return.
 */
internal interface ProviderEncoderCleanup {
    fun closeEncoderAsync(encoder: ImageEncoder)
}

internal class ProviderPreparationContext internal constructor(
    private val maxProviderWorkers: Int = MAX_QUARANTINED_PROVIDER_WORKERS,
    private val threadNamePrefix: String = "screen-capture-provider-prep",
    private val cleanupThreadNamePrefix: String = "screen-capture-provider-cleanup",
    private val lateFailureThreadNamePrefix: String = "screen-capture-provider-diagnostics",
    private val lateFailureDiagnostics: (Throwable) -> Unit = {},
    private val cleanupFailureDiagnostics: (Throwable) -> Unit = {},
    private val beforeClaim: (() -> Unit)? = null,
    private val beforeProviderWorkerStart: (() -> Unit)? = null,
    private val workerThreadFactory: ThreadFactory? = null,
    private val beforeExecutorExecute: (() -> Unit)? = null,
) : ProviderEncoderCleanup, AutoCloseable {
    private val admissionLock = Any()
    private val reservedProviderWorkers = AtomicInteger(0)
    private val workerThreadIds = AtomicInteger(0)
    private val cleanupThreadIds = AtomicInteger(0)
    private val lateFailureThreadIds = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val lateFailureDiagnosticInFlight = AtomicBoolean(false)
    private val records = Collections.synchronizedSet(mutableSetOf<ProviderPrepareRecord>())
    private val cleanupExecutor = Executors.newSingleThreadExecutor(namedThreadFactory(cleanupThreadNamePrefix, cleanupThreadIds))
    private val lateFailureExecutor =
        Executors.newSingleThreadExecutor(namedThreadFactory(lateFailureThreadNamePrefix, lateFailureThreadIds))

    init {
        require(maxProviderWorkers > 0) { "maxProviderWorkers must be positive, was $maxProviderWorkers" }
    }

    internal fun newToken(): ProviderPreparationToken = ProviderPreparationToken()

    internal suspend fun createEncoder(
        token: ProviderPreparationToken,
        provider: ImageEncoderProvider,
        request: ImageEncoderRequest,
        timeoutMs: Long = ENCODER_CREATE_TIMEOUT_MS,
        validateEncoder: (ImageEncoder) -> ProviderCreateEncoderResult,
    ): ProviderCreateEncoderResult {
        require(timeoutMs > 0L) { "timeoutMs must be positive, was $timeoutMs" }
        val record = synchronized(admissionLock) {
            if (closed.get() || !token.isCurrent) {
                return providerUnavailable("ImageEncoderProvider.createEncoder admission is unavailable.")
            }
            if (!reserveProviderWorker()) {
                return providerUnavailable("ImageEncoderProvider.createEncoder admission exhausted.")
            }

            var admittedRecord: ProviderPrepareRecord? = null
            var tokenAttached = false
            try {
                val executor = Executors.newSingleThreadExecutor(workerThreadFactory ?: namedThreadFactory(threadNamePrefix, workerThreadIds))
                admittedRecord = ProviderPrepareRecord(
                    token = token,
                    executor = executor,
                    provider = provider,
                    request = request,
                    validateEncoder = validateEncoder,
                    beforeProviderWorkerStart = beforeProviderWorkerStart,
                )
                records += admittedRecord
                if (!token.attach(admittedRecord)) {
                    records -= admittedRecord
                    admittedRecord.abandon()
                    return providerUnavailable("ImageEncoderProvider.createEncoder transaction is stale.")
                }
                tokenAttached = true
                val submitted = admittedRecord.submit()
                if (submitted is ProviderCreateEncoderResult.Failure) {
                    token.detach(admittedRecord)
                    records -= admittedRecord
                    return submitted
                }
            } catch (throwable: Throwable) {
                val recordToCleanup = admittedRecord
                if (recordToCleanup != null) {
                    if (tokenAttached) {
                        token.detach(recordToCleanup)
                    }
                    records -= recordToCleanup
                    recordToCleanup.abandon()
                } else {
                    releaseProviderWorker()
                }
                return ProviderCreateEncoderResult.Failure(
                    kind = ScreenCaptureProblemKind.EncoderUnavailable,
                    message = throwable.message ?: "ImageEncoderProvider.createEncoder admission failed.",
                    cause = throwable,
                )
            }
            admittedRecord
        }
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                record.awaitResult()
            }
            if (result == null) {
                record.abandon()
                providerUnavailable("ImageEncoderProvider.createEncoder timed out after $timeoutMs ms")
            } else {
                when (result) {
                    is ProviderCreateEncoderResult.Success -> {
                        beforeClaim?.invoke()
                        if (token.isCurrent && record.claim(result.encoder)) {
                            result
                        } else {
                            record.rejectSuccess(result.encoder)?.let(::closeEncoderAsync)
                            providerUnavailable("ImageEncoderProvider.createEncoder transaction is stale.")
                        }
                    }

                    is ProviderCreateEncoderResult.Failure -> result
                }
            }
        } catch (cancellation: CancellationException) {
            record.abandon()
            throw cancellation
        } finally {
            token.detach(record)
            records -= record
        }
    }

    override fun closeEncoderAsync(encoder: ImageEncoder) {
        val cleanup = Runnable {
            closeEncoderCatching(encoder)
        }
        try {
            cleanupExecutor.execute(cleanup)
        } catch (_: RejectedExecutionException) {
            namedThreadFactory(cleanupThreadNamePrefix, cleanupThreadIds).newThread(cleanup).start()
        }
    }

    override fun close() {
        val snapshot = synchronized(admissionLock) {
            if (!closed.compareAndSet(false, true)) return
            synchronized(records) { records.toList() }
        }
        snapshot.forEach { it.abandon() }
        shutdownCleanupIfIdle()
        lateFailureExecutor.shutdownNow()
    }

    private fun reserveProviderWorker(): Boolean {
        while (true) {
            val current = reservedProviderWorkers.get()
            if (current >= maxProviderWorkers) return false
            if (reservedProviderWorkers.compareAndSet(current, current + 1)) return true
        }
    }

    private fun releaseProviderWorker() {
        reservedProviderWorkers.decrementAndGet()
    }

    private fun shutdownCleanupIfIdle() {
        if (closed.get() && reservedProviderWorkers.get() == 0) {
            cleanupExecutor.shutdown()
        }
    }

    private fun closeEncoderCatching(encoder: ImageEncoder) {
        try {
            encoder.close()
        } catch (throwable: Throwable) {
            cleanupFailureDiagnostics(throwable)
        }
    }

    private fun providerUnavailable(message: String): ProviderCreateEncoderResult.Failure =
        ProviderCreateEncoderResult.Failure(
            kind = ScreenCaptureProblemKind.EncoderUnavailable,
            message = message,
            cause = null,
        )

    private fun namedThreadFactory(prefix: String, ids: AtomicInteger): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${ids.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    private fun dispatchLateFailureDiagnostic(throwable: Throwable) {
        if (!lateFailureDiagnosticInFlight.compareAndSet(false, true)) return
        try {
            lateFailureExecutor.execute {
                try {
                    runCatching { lateFailureDiagnostics(throwable) }
                } finally {
                    lateFailureDiagnosticInFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            lateFailureDiagnosticInFlight.set(false)
        }
    }

    private inner class ProviderPrepareRecord(
        private val token: ProviderPreparationToken,
        private val executor: ExecutorService,
        private val provider: ImageEncoderProvider,
        private val request: ImageEncoderRequest,
        private val validateEncoder: (ImageEncoder) -> ProviderCreateEncoderResult,
        private val beforeProviderWorkerStart: (() -> Unit)?,
    ) : ProviderPrepareRecordAccess {
        private val result = CompletableFuture<ProviderCreateEncoderResult>()
        private val started = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private val lock = Any()
        private var stale = false
        private var deliveredEncoder: ImageEncoder? = null
        private var future: Future<*>? = null
        private var submittedToExecutor = false

        fun submit(): ProviderCreateEncoderResult? {
            val submitted = object : FutureTask<Unit>(
                Callable {
                    started.set(true)
                    runProviderWork()
                },
            ) {
                override fun done() {
                    if (isCancelled && !started.get()) {
                        finishWorker()
                    }
                }
            }
            synchronized(lock) {
                if (stale) {
                    return ProviderCreateEncoderResult.Failure(
                        kind = ScreenCaptureProblemKind.EncoderUnavailable,
                        message = "ImageEncoderProvider.createEncoder transaction is stale.",
                        cause = null,
                    )
                }
                submittedToExecutor = true
            }
            try {
                beforeExecutorExecute?.invoke()
                executor.execute(submitted)
            } catch (throwable: Throwable) {
                finishWorker()
                return ProviderCreateEncoderResult.Failure(
                    kind = ScreenCaptureProblemKind.EncoderUnavailable,
                    message = throwable.message ?: "ImageEncoderProvider.createEncoder admission failed.",
                    cause = throwable,
                )
            }
            synchronized(lock) {
                future = submitted
                if (stale) {
                    submitted.cancel(true)
                }
            }
            return null
        }

        private fun runProviderWork() {
            beforeProviderWorkerStart?.invoke()
            try {
                completeValidatedEncoder(provider.createEncoder(request))
            } catch (throwable: Throwable) {
                completeFailure(throwable)
            } finally {
                finishWorker()
            }
        }

        private fun completeValidatedEncoder(encoder: ImageEncoder) {
            try {
                when (val validated = validateEncoder(encoder)) {
                    is ProviderCreateEncoderResult.Success -> completeSuccess(validated)
                    is ProviderCreateEncoderResult.Failure -> {
                        closeEncoderAsync(encoder)
                        completeValidationFailure(validated)
                    }
                }
            } catch (throwable: Throwable) {
                closeEncoderAsync(encoder)
                completeValidationFailure(
                    ProviderCreateEncoderResult.Failure(
                        kind = ScreenCaptureProblemKind.EncoderValidationFailed,
                        message = throwable.message ?: "ImageEncoder validation failed.",
                        cause = throwable,
                    ),
                )
            }
        }

        suspend fun awaitResult(): ProviderCreateEncoderResult =
            suspendCancellableCoroutine { continuation ->
                result.whenComplete { value, _ ->
                    if (continuation.isActive) {
                        continuation.resume(value)
                    }
                }
                continuation.invokeOnCancellation {
                    abandon()
                }
            }

        fun claim(encoder: ImageEncoder): Boolean = synchronized(lock) {
            if (closed.get() || stale || deliveredEncoder !== encoder) {
                false
            } else {
                deliveredEncoder = null
                true
            }
        }

        fun rejectSuccess(encoder: ImageEncoder): ImageEncoder? = synchronized(lock) {
            if (stale || deliveredEncoder !== encoder) {
                null
            } else {
                stale = true
                deliveredEncoder = null
                encoder
            }
        }

        override fun abandonRecord() {
            abandon()
        }

        fun abandon() {
            var encoderToClose: ImageEncoder? = null
            var futureToCancel: Future<*>? = null
            var taskSubmitted = true
            var alreadyStale = false
            synchronized(lock) {
                if (stale) {
                    alreadyStale = true
                } else {
                    stale = true
                    encoderToClose = deliveredEncoder
                    deliveredEncoder = null
                    futureToCancel = future
                    taskSubmitted = submittedToExecutor
                }
            }
            if (alreadyStale) {
                completeStaleFailure()
                return
            }
            futureToCancel?.cancel(true)
            if (futureToCancel == null && !taskSubmitted) {
                finishWorker()
            }
            completeStaleFailure()
            encoderToClose?.let(::closeEncoderAsync)
            executor.shutdownNow()
        }

        private fun completeSuccess(success: ProviderCreateEncoderResult.Success) {
            val shouldClose: Boolean
            val shouldComplete: Boolean
            synchronized(lock) {
                if (stale || !token.isCurrent) {
                    stale = true
                    shouldClose = true
                    shouldComplete = false
                } else {
                    deliveredEncoder = success.encoder
                    shouldClose = false
                    shouldComplete = true
                }
            }
            if (shouldClose) {
                closeEncoderAsync(success.encoder)
            }
            if (shouldComplete) {
                result.complete(success)
            } else {
                completeStaleFailure()
            }
        }

        private fun completeValidationFailure(failure: ProviderCreateEncoderResult.Failure) {
            val shouldComplete = synchronized(lock) {
                if (stale || !token.isCurrent) {
                    stale = true
                    false
                } else {
                    true
                }
            }
            if (shouldComplete) {
                result.complete(failure)
            } else {
                completeStaleFailure()
                failure.cause?.let(::reportLateFailure)
            }
        }

        private fun completeFailure(throwable: Throwable) {
            val shouldComplete = synchronized(lock) {
                if (stale || !token.isCurrent) {
                    stale = true
                    false
                } else {
                    true
                }
            }
            if (shouldComplete) {
                result.complete(
                    ProviderCreateEncoderResult.Failure(
                        kind = provider.classifyCreateEncoderFailure(request, throwable),
                        message = throwable.message ?: "ImageEncoderProvider.createEncoder failed.",
                        cause = throwable,
                    ),
                )
            } else {
                completeStaleFailure()
                reportLateFailure(throwable)
            }
        }

        private fun reportLateFailure(throwable: Throwable) {
            finishWorker()
            dispatchLateFailureDiagnostic(throwable)
        }

        private fun completeStaleFailure() {
            result.complete(
                ProviderCreateEncoderResult.Failure(
                    kind = ScreenCaptureProblemKind.EncoderUnavailable,
                    message = "ImageEncoderProvider.createEncoder transaction is stale.",
                    cause = null,
                ),
            )
        }

        private fun finishWorker() {
            if (finished.compareAndSet(false, true)) {
                executor.shutdown()
                if (released.compareAndSet(false, true)) {
                    releaseProviderWorker()
                    shutdownCleanupIfIdle()
                }
            }
        }
    }
}

private fun ImageEncoderProvider.classifyCreateEncoderFailure(
    request: ImageEncoderRequest,
    throwable: Throwable,
): ScreenCaptureProblemKind =
    if (this is JpegImageEncoderProvider && request.isBuiltInFrameworkJpegAllocationFailureRequest(throwable)) {
        ScreenCaptureProblemKind.AllocationFailed
    } else {
        ScreenCaptureProblemKind.EncoderUnavailable
    }

private fun ImageEncoderRequest.isBuiltInFrameworkJpegAllocationFailureRequest(throwable: Throwable): Boolean =
    !hasImpossibleFrameworkJpegRawSpan() && throwable.hasOutOfMemoryCause()

private fun ImageEncoderRequest.hasImpossibleFrameworkJpegRawSpan(): Boolean {
    if (inputFormat != ImageEncoderInputFormat.Rgba8888SrgbOpaque) return false
    if (rowStrideBytes.toLong() < width.toLong() * RGBA_8888_BYTES_PER_PIXEL) return false
    val requiredByteCount = checkedRequiredByteCount(
        height = height,
        rowStrideBytes = rowStrideBytes,
        width = width,
    ) ?: return true
    return requiredByteCount > Int.MAX_VALUE
}

private fun Throwable.hasOutOfMemoryCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is OutOfMemoryError) return true
        current = current.cause
    }
    return false
}

private fun checkedRequiredByteCount(height: Int, rowStrideBytes: Int, width: Int): Long? {
    val lastRowOffset = checkedMultiplyLong((height - 1).toLong(), rowStrideBytes.toLong()) ?: return null
    val rowPayloadBytes = checkedMultiplyLong(width.toLong(), RGBA_8888_BYTES_PER_PIXEL) ?: return null
    return checkedAddLong(lastRowOffset, rowPayloadBytes)
}

private fun checkedMultiplyLong(left: Long, right: Long): Long? =
    try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

private fun checkedAddLong(left: Long, right: Long): Long? =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

private const val RGBA_8888_BYTES_PER_PIXEL: Long = 4L

internal class ProviderPreparationToken internal constructor() {
    private val active = AtomicBoolean(true)
    private val records = Collections.synchronizedSet(mutableSetOf<ProviderPrepareRecordAccess>())

    internal val isCurrent: Boolean
        get() = active.get()

    internal fun cancel() {
        if (!active.compareAndSet(true, false)) return
        val snapshot = synchronized(records) { records.toList() }
        snapshot.forEach { record ->
            record.abandonRecord()
        }
    }

    internal fun attach(record: ProviderPrepareRecordAccess): Boolean {
        records += record
        if (!isCurrent) {
            records -= record
            return false
        }
        return true
    }

    internal fun detach(record: ProviderPrepareRecordAccess) {
        records -= record
    }
}

internal interface ProviderPrepareRecordAccess {
    fun abandonRecord()
}

internal sealed class ProviderCreateEncoderResult private constructor() {
    internal class Success internal constructor(
        internal val encoder: ImageEncoder,
        internal val info: ImageEncoderInfo,
    ) : ProviderCreateEncoderResult()

    internal class Failure internal constructor(
        internal val kind: ScreenCaptureProblemKind,
        internal val message: String,
        internal val cause: Throwable?,
    ) : ProviderCreateEncoderResult()
}
