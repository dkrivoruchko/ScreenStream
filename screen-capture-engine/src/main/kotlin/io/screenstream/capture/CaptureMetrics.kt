package io.screenstream.capture

import android.content.Context
import android.view.Display
import io.screenstream.capture.internal.metrics.BuiltInCaptureMetricsSource
import io.screenstream.capture.internal.runtime.ProductionRuntime
import java.lang.AutoCloseable
import kotlin.Any
import kotlin.Boolean
import kotlin.Exception
import kotlin.IllegalArgumentException
import kotlin.Int
import kotlin.String
import kotlin.Throwable
import kotlin.require

/**
 * Supplies the dimensions and density used to configure capture geometry.
 *
 * Each [subscribe] call is an independent observation. Implementations may invoke the observer inline before
 * returning, reentrantly, concurrently, and on arbitrary source-owned threads. A session retains its selected custom
 * source by identity and invokes [subscribe] at most once. It adopts only the exact non-null handle returned normally
 * by that invocation and calls the adopted handle's [AutoCloseable.close] at most once.
 *
 * The source supplies geometry metrics; it does not select the content covered by projection consent. A custom
 * implementation is responsible for keeping its Activity, window, display, and lifecycle policy consistent with
 * that consent.
 */
public fun interface CaptureMetricsSource {
    /**
     * Starts one independent observation.
     *
     * [Observer.onMetricsChanged] reports the latest value; `null` means geometry is currently unavailable. For a
     * session attachment, normal completion or failure fences later callbacks. The returned handle must be non-null
     * and represents this exact observation. When a session invokes this function, a normally thrown [Exception] is
     * offered as [ScreenCaptureProblem.InternalFailure] while ordinary session admission remains open. An existing or
     * higher-priority terminal resolution can supersede it or make the evidence cleanup-only. Other uncontained
     * throwables retain ordinary Kotlin/JVM propagation.
     *
     * @param observer receiver that must tolerate inline, reentrant, concurrent, and arbitrary-thread calls.
     * @return the exact handle that closes this observation.
     */
    public fun subscribe(observer: Observer): AutoCloseable

    /** Receives latest-value lifecycle callbacks from one metrics subscription. */
    public interface Observer {
        /**
         * Replaces the latest metrics observation.
         *
         * @param metrics positive capture metrics, or `null` when geometry is currently unavailable.
         */
        public fun onMetricsChanged(metrics: CaptureMetrics?)

        /**
         * Reports normal completion and freezes the current availability for this subscription.
         *
         * Completion with no current positive metrics remains unavailable. Calls after completion are ignored by the
         * session attachment.
         */
        public fun onComplete()

        /**
         * Reports terminal source failure.
         *
         * [cause] is opaque best-effort diagnostic data. It is not rethrown merely because of its runtime type. While
         * ordinary session admission remains open, attachment offers the failure as
         * [ScreenCaptureProblem.InternalFailure]; an existing or higher-priority terminal resolution can supersede it
         * or make the evidence cleanup-only. Calls after failure are ignored by the session attachment.
         *
         * @param cause exact throwable supplied as diagnostic context; its type and mutable object graph are not
         * readiness or currentness evidence.
         */
        public fun onFailure(cause: Throwable)
    }

    /** Factory for built-in display-backed metrics sources. */
    public companion object {
        /**
         * Creates an immutable reusable source fixed to the exact supplied display object.
         *
         * The source normalizes and retains [context]'s application context. A current valid display with the same ID
         * from that application's [android.hardware.display.DisplayManager] is used only as association evidence; it
         * is never substituted for the retained [display]. Later loss reports `null`, and a later valid same-ID
         * association may recover.
         *
         * Every subscription registers independently. The returned observation handle may already be auto-closed
         * after an observation failure. Calling `close()` fences new ingress synchronously and observes or performs
         * the single unregister settlement; repeated or concurrent calls do not retry it or wait for callbacks,
         * worker work, or broader resource release.
         *
         * @param context context whose application context supplies display services.
         * @param display exact display object used as the metrics read target.
         * @return a reusable source whose subscriptions observe that fixed target.
         * @throws IllegalArgumentException if an application context or display service is unavailable, or if
         * [display] is provably invalid or unassociated with that display service.
         */
        public fun fromDisplay(context: Context, display: Display): CaptureMetricsSource {
            return BuiltInCaptureMetricsSource.forFixedDisplay(context, display, ProductionRuntime.workerDispatcher)
        }
    }
}

/**
 * Immutable positive capture dimensions and density reported by a [CaptureMetricsSource].
 *
 * On API levels 24 through 33, these dimensions and density are authoritative capture geometry. On API level 34 and
 * later, the dimensions are provisional until the first valid projection resize, while density remains
 * source-provided. Instances use structural equality and hashing.
 *
 * @property widthPx positive source width in pixels.
 * @property heightPx positive source height in pixels.
 * @property densityDpi positive logical density in dots per inch.
 * @throws IllegalArgumentException if any property is not positive.
 */
public class CaptureMetrics(
    public val widthPx: Int,
    public val heightPx: Int,
    public val densityDpi: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive" }
        require(heightPx > 0) { "heightPx must be positive" }
        require(densityDpi > 0) { "densityDpi must be positive" }
    }

    /** Returns whether [other] has the same width, height, and density. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureMetrics) return false
        return (widthPx == other.widthPx) && (heightPx == other.heightPx) && (densityDpi == other.densityDpi)
    }

    /** Returns the structural hash code for the width, height, and density. */
    public override fun hashCode(): Int {
        var result: Int = widthPx.hashCode()
        result = (31 * result) + heightPx.hashCode()
        result = (31 * result) + densityDpi.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug representation whose format is not an API contract. */
    public override fun toString(): String =
        "CaptureMetrics(widthPx=$widthPx, heightPx=$heightPx, densityDpi=$densityDpi)"
}
