package dev.dmkr.screencaptureengine.internal.gl

import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal fun interface StartupCleanupScheduler {
    fun schedule(block: () -> Unit)
}

internal fun interface StartupCleanupFailureSink {
    fun onCleanupFailure(failure: Throwable)
}

internal object DefaultStartupCleanupScheduler : StartupCleanupScheduler {
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScreenCaptureStartupCleanup").apply { isDaemon = true }
    }

    override fun schedule(block: () -> Unit) {
        executor.execute(block)
    }
}

internal object DefaultStartupCleanupFailureSink : StartupCleanupFailureSink {
    override fun onCleanupFailure(failure: Throwable) {
        val currentThread = Thread.currentThread()
        val handler = currentThread.uncaughtExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler()
        if (handler != null) {
            handler.uncaughtException(currentThread, failure)
        } else {
            failure.printStackTrace()
        }
    }
}

/**
 * Schedules heavy cleanup and falls back to local execution if scheduling itself fails.
 *
 * Owned resources must not be dropped just because the cleanup scheduler rejects work. Scheduler
 * and cleanup failures are reported through [cleanupFailureSink], with reporting failures rethrown
 * to the caller.
 */
internal fun scheduleStartupCleanup(
    cleanupScheduler: StartupCleanupScheduler,
    cleanupFailureSink: StartupCleanupFailureSink,
    block: () -> Unit,
) {
    fun runCleanup() {
        runCatching(block).onFailure(cleanupFailureSink::onCleanupFailure)
    }

    try {
        cleanupScheduler.schedule(::runCleanup)
    } catch (cause: Throwable) {
        var reportingFailure: Throwable? = null
        try {
            cleanupFailureSink.onCleanupFailure(cause)
        } catch (reportCause: Throwable) {
            reportingFailure = reportCause
        }
        try {
            // Scheduler failure must not drop owned resources; this is the final local fallback.
            runCleanup()
        } catch (cleanupReportFailure: Throwable) {
            val existingReportingFailure = reportingFailure
            if (existingReportingFailure == null) {
                reportingFailure = cleanupReportFailure
            } else {
                existingReportingFailure.addSuppressed(cleanupReportFailure)
            }
        }
        reportingFailure?.let { throw it }
    }
}

internal fun Throwable?.collectFailure(cause: Throwable): Throwable =
    this?.also { existing -> existing.addSuppressed(cause) } ?: cause
