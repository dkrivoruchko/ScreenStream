package io.screenstream.capture

import android.content.Context
import android.view.Display
import androidx.annotation.IntRange
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
 * source by identity and invokes [subscribe] at most once. [subscribe] and the returned handle's
 * [AutoCloseable.close] may run on an engine worker thread. The source should return promptly, publish its current
 * value, and report later changes asynchronously; there is no callback deadline. The session adopts only the exact
 * non-null handle returned normally, and positive metrics cannot make the attachment ready before that adoption. It
 * makes at most one handle-close attempt after completion, failure, or retirement, including for a handle returned
 * after retirement. A live close [Exception] may fail the session while failure admission remains open; an attempted
 * close does not prove closure, and callbacks after completion, failure, or retirement are ignored.
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
     * and represents this exact observation. A normally thrown [Exception] may fail the session with
     * [ScreenCaptureProblem.InternalFailure] while failure admission remains open; other throwables retain ordinary
     * Kotlin/JVM propagation. If this function throws before returning a handle, the source remains responsible for
     * resources it allocated but did not hand off.
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
         * Current positive metrics remain usable after completion, even before handle close settles. Completion with
         * no current positive metrics remains unavailable and cannot later revive. The session attempts to close the
         * handle at most once, but closure is independent and not guaranteed. Calls after completion are ignored.
         */
        public fun onComplete()

        /**
         * Reports terminal source failure.
         *
         * [cause] is opaque best-effort diagnostic data. It is not rethrown merely because of its runtime type. While
         * failure admission remains open, the session may fail with [ScreenCaptureProblem.InternalFailure]. Calls
         * after failure are ignored.
         *
         * @param cause exact throwable supplied as optional diagnostic context, not readiness or currentness evidence.
         */
        public fun onFailure(cause: Throwable)
    }

    /** Factory for built-in display-backed metrics sources. */
    public companion object {
        /**
         * Creates an immutable reusable source fixed to the exact supplied display object.
         *
         * The source normalizes and retains [context]'s application context and the exact [display] object. The
         * application's [android.hardware.display.DisplayManager] must currently report a valid display with the same
         * ID, but that reported object never replaces [display]. Later association loss reports `null`; a later valid
         * same-ID association may recover.
         *
         * Every subscription registers independently. Calling `close()` fences new ingress synchronously and performs
         * or observes the subscription's single unregister attempt; repeated or concurrent calls do not retry it or
         * wait for callbacks, worker work, or broader resource release. A live close [Exception] may fail the session.
         * A handle returned after that session has retired is still given one close attempt, and callbacks after
         * retirement are ignored.
         *
         * @param context context whose application context supplies display services.
         * @param display exact display object used as the metrics read target.
         * @return a reusable source whose subscriptions observe that fixed target.
         * @throws IllegalArgumentException if an application context or display service is unavailable, or if
         *     [display] is provably invalid or unassociated with that display service.
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
 * later, width and height are provisional until the first valid projection resize. After that, projection resize
 * callbacks determine width and height, including later resizes; source dimensions do not override them. Source
 * density and availability remain necessary. The session cannot become [ScreenCaptureState.Active] before that
 * resize. Instances use structural equality and hashing.
 *
 * @property widthPx positive source width in pixels.
 * @property heightPx positive source height in pixels.
 * @property densityDpi positive logical density in dots per inch.
 * @throws IllegalArgumentException if any property is not positive.
 */
public class CaptureMetrics(
    @param:IntRange(from = 1) @get:IntRange(from = 1) public val widthPx: Int,
    @param:IntRange(from = 1) @get:IntRange(from = 1) public val heightPx: Int,
    @param:IntRange(from = 1) @get:IntRange(from = 1) public val densityDpi: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive" }
        require(heightPx > 0) { "heightPx must be positive" }
        require(densityDpi > 0) { "densityDpi must be positive" }
    }

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureMetrics) return false
        return (widthPx == other.widthPx) && (heightPx == other.heightPx) && (densityDpi == other.densityDpi)
    }

    public override fun hashCode(): Int {
        var result: Int = widthPx.hashCode()
        result = (31 * result) + heightPx.hashCode()
        result = (31 * result) + densityDpi.hashCode()
        return result
    }

    public override fun toString(): String =
        "CaptureMetrics(widthPx=$widthPx, heightPx=$heightPx, densityDpi=$densityDpi)"
}
