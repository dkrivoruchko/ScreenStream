package io.screenstream.capture.internal.metrics

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.CaptureMetricsSource
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import java.lang.AutoCloseable

internal class BuiltInCaptureMetricsSource private constructor(
    internal val applicationContext: Context,
    internal val displayManager: DisplayManager,
    internal val fixedDisplay: Display?,
    internal val selectedDisplayId: Int,
    internal val platform: BuiltInCaptureMetricsPlatform,
    private val workerDispatcher: NonInlineDispatcher,
) : CaptureMetricsSource {

    private class PublicCaptureMetricsSink(private val observer: CaptureMetricsSource.Observer) : BuiltInCaptureMetricsSink {

        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            observer.onMetricsChanged(metrics)
        }

        override fun onObservationFailure(cause: Exception) {
            observer.onFailure(cause)
        }
    }

    override fun subscribe(observer: CaptureMetricsSource.Observer): AutoCloseable {
        val observation = BuiltInCaptureMetricsObservation(
            source = this,
            sink = PublicCaptureMetricsSink(observer),
            workerDispatcher = workerDispatcher,
        )
        observation.start()
        return observation
    }

    internal fun resolveSelectedDisplay(): Display? =
        fixedDisplay ?: platform.getDisplay(displayManager, Display.DEFAULT_DISPLAY)

    internal fun isSelectedDisplayValid(display: Display): Boolean =
        fixedDisplay?.let { fixed -> (display === fixed) && isFixedSelectionValid(fixed) }
            ?: isDefaultSelectionValid(display)

    private fun isFixedSelectionValid(display: Display): Boolean {
        if ((platform.displayId(display) != selectedDisplayId) || !platform.isValid(display)) return false
        val associatedDisplay = platform.getDisplay(displayManager, selectedDisplayId) ?: return false
        return (platform.displayId(associatedDisplay) == selectedDisplayId) && platform.isValid(associatedDisplay)
    }

    private fun isDefaultSelectionValid(display: Display): Boolean {
        if ((platform.displayId(display) != Display.DEFAULT_DISPLAY) || !platform.isValid(display)) return false
        val associatedDisplay = platform.getDisplay(displayManager, Display.DEFAULT_DISPLAY) ?: return false
        return (platform.displayId(associatedDisplay) == Display.DEFAULT_DISPLAY) && platform.isValid(associatedDisplay)
    }

    internal companion object {
        internal fun forFixedDisplay(
            context: Context,
            display: Display,
            workerDispatcher: NonInlineDispatcher,
            platform: BuiltInCaptureMetricsPlatform = AndroidBuiltInCaptureMetricsPlatform,
        ): BuiltInCaptureMetricsSource {
            val applicationContext = requireApplicationContext(context)
            val displayManager = requireDisplayManager(applicationContext)
            val displayId = platform.displayId(display)
            require(platform.isValid(display)) { "display must be valid" }
            val associatedDisplay = platform.getDisplay(displayManager, displayId)
            require((associatedDisplay != null) && (platform.displayId(associatedDisplay) == displayId) && platform.isValid(associatedDisplay)) {
                "display must be associated with the application DisplayManager"
            }
            return BuiltInCaptureMetricsSource(
                applicationContext = applicationContext,
                displayManager = displayManager,
                fixedDisplay = display,
                selectedDisplayId = displayId,
                platform = platform,
                workerDispatcher = workerDispatcher,
            )
        }

        internal fun forDefaultDisplay(
            context: Context,
            workerDispatcher: NonInlineDispatcher,
            platform: BuiltInCaptureMetricsPlatform = AndroidBuiltInCaptureMetricsPlatform,
        ): BuiltInCaptureMetricsSource {
            val applicationContext = requireApplicationContext(context)
            val displayManager = requireDisplayManager(applicationContext)
            return BuiltInCaptureMetricsSource(
                applicationContext = applicationContext,
                displayManager = displayManager,
                fixedDisplay = null,
                selectedDisplayId = Display.DEFAULT_DISPLAY,
                platform = platform,
                workerDispatcher = workerDispatcher,
            )
        }

        private fun requireApplicationContext(context: Context): Context =
            requireNotNull(context.applicationContext) { "context.applicationContext must be available" }

        private fun requireDisplayManager(applicationContext: Context): DisplayManager =
            requireNotNull(applicationContext.getSystemService(DisplayManager::class.java)) { "DisplayManager must be available" }
    }
}
