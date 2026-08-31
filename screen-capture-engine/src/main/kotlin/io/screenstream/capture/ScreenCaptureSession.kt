package io.screenstream.capture

import android.media.projection.MediaProjection
import io.screenstream.capture.internal.session.SessionCoordinator
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single-use screen-capture lifecycle and its observations.
 *
 * A session accepts at most one [MediaProjection], can accept parameter changes while running, and can have at most
 * one unresolved frame-consumer registration. A terminal session cannot restart; create a new session with fresh
 * consent and projection authority for later capture. Terminal state ends capture authority and new work but is not
 * a receipt for physical cleanup. Each Flow property retains and returns one stable read-only facade, and those
 * flows remain open for the lifetime of this object.
 *
 * [start] and [FrameConsumerRegistration.unregister] are main-safe suspending operations. [updateParameters],
 * [registerFrameConsumer], and [stop] are thread-safe, synchronous, and nonblocking relative to capture, encoding,
 * callbacks, and cleanup. Flow getters may be accessed from any thread and do not start capture.
 *
 * [ScreenCaptureProblem] is the only stable failure classification. Any attached throwable, message, or suppressed
 * throwable is optional best-effort diagnostic context. Uncontained throwables retain ordinary Kotlin/JVM
 * propagation and do not gain a fabricated session problem or recovery guarantee.
 */
public class ScreenCaptureSession private constructor(private val coordinator: SessionCoordinator) {
    /**
     * Latest lifecycle state, initially [ScreenCaptureState.NotStarted].
     *
     * Rapid assignments may be conflated. This flow is independent from [stats] and [diagnosticEvents], so there is
     * no cross-flow ordering or atomic combined snapshot. Collectors must remain nonblocking and must not
     * synchronously reenter an operation that is awaiting publication; an immediate dispatcher may run collector
     * code before the assigning operation returns.
     */
    public val state: StateFlow<ScreenCaptureState>
        get() = coordinator.state

    /**
     * Latest coherent capture statistics, initially all zero.
     *
     * Rapid assignments may be conflated. This flow is independent from [state] and [diagnosticEvents], so there is
     * no cross-flow ordering or atomic combined snapshot. Collectors have the same nonblocking and non-reentrant
     * responsibilities as [state] collectors.
     */
    public val stats: StateFlow<ScreenCaptureStats>
        get() = coordinator.stats

    /**
     * Best-effort, non-authoritative diagnostic events for this session.
     *
     * This flow has no replay and uses bounded lossy publication. Event presence, text, cause, count, order, or loss
     * is not correctness evidence or a lifecycle or cleanup receipt. Sources and event names are extensible.
     */
    public val diagnosticEvents: SharedFlow<ScreenCaptureDiagnosticEvent>
        get() = coordinator.diagnosticEvents

    /**
     * Transfers fresh projection authority to this session and starts capture.
     *
     * The host must obtain fresh user consent and satisfy the platform's media-projection foreground-service and
     * permission requirements before calling this function. In particular, hosts targeting Android 14 or later
     * require one-time projection consent/authority for each capture session. Only an accepted call transfers
     * [mediaProjection]; a rejected or pre-acceptance-cancelled call does not touch it.
     *
     * Hosts targeting Android 9 or later declare `FOREGROUND_SERVICE`. For hosts targeting Android 10 or later,
     * capture and projection acquisition occur while a running foreground service declares the `mediaProjection`
     * type. Hosts targeting Android 14 or later also declare `FOREGROUND_SERVICE_MEDIA_PROJECTION` and order work as
     * consent, typed-service start/promotion, projection acquisition, then this call. The host must comply with
     * background-start restrictions for its target SDK and, when targeting Android 15 or later, must not start the
     * media-projection service from `BOOT_COMPLETED`.
     *
     * Normal return occurs only after [ScreenCaptureState.Active] has been assigned. It does not wait for a source
     * frame, encoded JPEG, consumer callback, or physical cleanup. First-active eligibility uses a 10-second
     * elapsed-realtime window sampled before admission; expiration is observed only when current session work can
     * arbitrate it and is not an unconditional publication deadline.
     *
     * If caller cancellation is observed before admission, the session and projection remain untouched. Cancellation
     * after acceptance requests owner stop and still propagates to this caller.
     *
     * @param mediaProjection fresh authority obtained for this capture session.
     * @param initialParameters initial desired parameters. Defaults to [ScreenCaptureParameters] constructor defaults.
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled; after acceptance this also requests
     * session stop.
     * @throws IllegalStateException if this session has already accepted a start, is terminal, or loses a concurrent
     * start race.
     * @throws ScreenCaptureException if startup terminates before becoming active. Its
     * [ScreenCaptureException.problem] is the stable failure meaning; message, cause, and suppressed throwables are
     * optional best-effort diagnostics.
     */
    public suspend fun start(
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters(),
    ): Unit = coordinator.start(mediaProjection, initialParameters)

    /**
     * Durably requests the newest parameters for a running session.
     *
     * Local parameter validation has already occurred during value construction and is never clamped. Geometry-
     * dependent invalidity is later reported through state as [ScreenCaptureProblem.InvalidRequest]. A request equal
     * to the current desire is a no-op after admission. Return acknowledges the accepted desire, not state assignment,
     * flow delivery, or convergence to active output.
     *
     * @param parameters deeply immutable desired capture parameters.
     * @throws IllegalStateException if the session is not in a nonterminal running state or public admission is closed.
     * @throws ScreenCaptureException with [ScreenCaptureProblem.InternalFailure] if the request cannot receive a
     * required session identity.
     */
    public fun updateParameters(parameters: ScreenCaptureParameters): Unit =
        coordinator.updateParameters(parameters)

    /**
     * Registers the session's one current frame consumer.
     *
     * Registration is legal before start and while the session is nonterminal, and creates no capture work. The
     * callback may enter on an engine-selected thread before this function returns. Calls are serialized: at most one
     * callback invocation or unresolved submission is outstanding for the registration, with no callback deadline.
     * The received [EncodedImageFrame] is borrowed and may be accessed only during that invocation on the callback
     * thread; copy its bytes to retain them.
     *
     * When ordinary callback closure settles safely, a callback [Exception] is contained as a delivery failure and
     * leaves the registration active. Definite rejection while submitting a current handoff is
     * [ScreenCaptureProblem.InternalFailure]. After accepted work, failure to report physical closure can instead
     * leave delivery unresolved without a retry or guaranteed session problem. Other uncontained throwables follow
     * ordinary Kotlin/JVM propagation on the engine-selected hosting mechanism; they do not acquire a stable session
     * failure meaning.
     *
     * @param consumer callback that should return promptly to avoid busy-delivery drops and must not retain or access
     * the borrowed frame afterward.
     * @return the identity registration used to stop and await delivery for this consumer.
     * @throws IllegalStateException if another registration remains unresolved or the session is terminal.
     * @throws ScreenCaptureException with [ScreenCaptureProblem.InternalFailure] if a required registration identity
     * cannot be allocated.
     */
    public fun registerFrameConsumer(consumer: (EncodedImageFrame) -> Unit): FrameConsumerRegistration =
        FrameConsumerRegistration.create(coordinator.registerFrameConsumer(consumer))

    /**
     * Idempotently requests terminal stop and closes new public work before returning.
     *
     * Terminal state publication, outstanding operation settlement, callback completion, and physical cleanup may
     * occur asynchronously after this function returns. Calling this before [start] terminally stops the session.
     */
    public fun stop(): Unit = coordinator.stop()

    internal companion object {
        @JvmSynthetic
        internal fun create(coordinator: SessionCoordinator): ScreenCaptureSession = ScreenCaptureSession(coordinator)
    }
}

/**
 * Identity handle for one frame-consumer registration.
 *
 * Unregistering this handle never stops capture. Only successful unregister permits a replacement consumer.
 */
public class FrameConsumerRegistration private constructor(
    private val unregisterAction: suspend () -> Unit,
) {
    /**
     * Closes new delivery for this registration and awaits its outstanding callback handoff unless terminal session
     * resolution wins.
     *
     * A successful call is idempotent. Caller cancellation does not reopen delivery or fabricate completion, and a
     * later call observes the same monotone result. Calling from inside this registration's entered callback is
     * illegal.
     *
     * @throws IllegalStateException if invoked from this registration's entered callback.
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled or terminal stop resolves an
     * outstanding unregister; terminal-stop cancellation can occur even while the caller's Job remains active.
     * @throws ScreenCaptureException with the terminal session's authoritative [ScreenCaptureException.problem] if
     * failure wins before unregister completes.
     */
    public suspend fun unregister(): Unit = unregisterAction()

    internal companion object {
        @JvmSynthetic
        internal fun create(unregisterAction: suspend () -> Unit): FrameConsumerRegistration =
            FrameConsumerRegistration(unregisterAction)
    }
}
