package io.screenstream.engine.internal.metrics

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.CaptureMetricsSubscription
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal fun fixedDisplayCaptureMetricsSource(
    context: Context,
    display: Display,
): CaptureMetricsSource = BuiltInCaptureMetricsSource.fixed(context, display)

internal fun defaultDisplayCaptureMetricsSource(context: Context): BuiltInCaptureMetricsSource =
    BuiltInCaptureMetricsSource.default(context)

/** Immutable policy. Every subscription creates a wholly independent observation. */
internal class BuiltInCaptureMetricsSource private constructor(
    internal val applicationContext: Context,
    internal val displayManager: DisplayManager,
    internal val fixedDisplay: Display?,
    internal val selectedDisplayId: Int,
    internal val platform: BuiltInCaptureMetricsPlatform,
) : CaptureMetricsSource {
    override fun subscribe(observer: CaptureMetricsObserver): CaptureMetricsSubscription {
        val observation = newObservation(
            sink = PublicCaptureMetricsSink(observer),
            dispatcher = DirectBuiltInMetricsDispatcher,
        )
        observation.start()
        return observation
    }

    internal fun newObservation(
        sink: BuiltInCaptureMetricsSink,
        dispatcher: BuiltInMetricsDispatcher,
    ): BuiltInCaptureMetricsObservation = BuiltInCaptureMetricsObservation(
        source = this,
        sink = sink,
        dispatcher = dispatcher,
    )

    internal fun resolveSelectedDisplay(): Display? =
        fixedDisplay ?: platform.getDisplay(displayManager, Display.DEFAULT_DISPLAY)

    internal fun selectionIsValid(display: Display): Boolean {
        val fixed = fixedDisplay
        if (fixed == null) return platform.isValid(display)
        if (display !== fixed || platform.displayId(fixed) != selectedDisplayId || !platform.isValid(fixed)) {
            return false
        }
        val managerEvidence = platform.getDisplay(displayManager, selectedDisplayId) ?: return false
        return platform.displayId(managerEvidence) == selectedDisplayId && platform.isValid(managerEvidence)
    }

    internal fun selectionStillMatches(display: Display): Boolean {
        val fixed = fixedDisplay
        if (fixed != null) return display === fixed && selectionIsValid(fixed)
        return platform.isValid(display) &&
                platform.getDisplay(displayManager, Display.DEFAULT_DISPLAY) === display
    }

    internal companion object {
        internal fun fixed(
            context: Context,
            display: Display,
            platform: BuiltInCaptureMetricsPlatform = AndroidBuiltInCaptureMetricsPlatform,
        ): BuiltInCaptureMetricsSource {
            val applicationContext = normalizedApplicationContext(context)
            val displayManager = displayManager(applicationContext)
            val displayId = platform.displayId(display)
            require(platform.isValid(display)) { "display must be valid" }
            val managerEvidence = platform.getDisplay(displayManager, displayId)
            require(managerEvidence != null && platform.isValid(managerEvidence)) {
                "display must be associated with the application DisplayManager"
            }
            return BuiltInCaptureMetricsSource(
                applicationContext = applicationContext,
                displayManager = displayManager,
                fixedDisplay = display,
                selectedDisplayId = displayId,
                platform = platform,
            )
        }

        internal fun default(
            context: Context,
            platform: BuiltInCaptureMetricsPlatform = AndroidBuiltInCaptureMetricsPlatform,
        ): BuiltInCaptureMetricsSource {
            val applicationContext = normalizedApplicationContext(context)
            return BuiltInCaptureMetricsSource(
                applicationContext = applicationContext,
                displayManager = displayManager(applicationContext),
                fixedDisplay = null,
                selectedDisplayId = Display.DEFAULT_DISPLAY,
                platform = platform,
            )
        }

        private fun normalizedApplicationContext(context: Context): Context =
            requireNotNull(context.applicationContext) { "context.applicationContext must be available" }

        private fun displayManager(applicationContext: Context): DisplayManager =
            requireNotNull(applicationContext.getSystemService(DisplayManager::class.java)) {
                "DisplayManager must be available"
            }
    }
}

internal fun interface BuiltInMetricsDispatcher {
    /** Must enqueue [task] for non-direct serial execution for this observation. */
    fun dispatch(task: Runnable)
}

private object DirectBuiltInMetricsDispatcher : BuiltInMetricsDispatcher {
    private val threadNumber = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(
        DIRECT_WORKER_COUNT,
        ThreadFactory { task ->
            Thread(
                task,
                "ScreenCaptureEngine-DirectMetrics-${threadNumber.incrementAndGet()}",
            ).apply { isDaemon = true }
        },
    )

    override fun dispatch(task: Runnable) {
        executor.execute(task)
    }

    private const val DIRECT_WORKER_COUNT = 2
}

private class PublicCaptureMetricsSink(
    private val observer: CaptureMetricsObserver,
) : BuiltInCaptureMetricsSink {
    override fun onMetricsChanged(
        metrics: CaptureMetrics?,
        display: Display?,
        epoch: BuiltInCaptureMetricsEpoch?,
    ) {
        observer.onMetricsChanged(metrics)
    }

    override fun onObservationFailure(cause: Exception) {
        observer.onFailure(cause)
    }

    override fun onCloseCompleted() = Unit

    override fun onCloseFailure(cause: Exception) = Unit
}
