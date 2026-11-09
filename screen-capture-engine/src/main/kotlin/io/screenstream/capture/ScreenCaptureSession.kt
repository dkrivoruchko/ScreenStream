package io.screenstream.capture

import android.media.projection.MediaProjection
import io.screenstream.capture.internal.session.SessionCoordinator
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single-use screen-capture lifecycle and its observations.
 *
 * A session accepts at most one [MediaProjection], can accept parameter changes while running, and can have at most
 * one unresolved frame-consumer registration. A terminal session cannot restart; create a new session with
 * host-provided projection authority for later capture. Terminal state ends capture authority and new work but is not
 * a receipt for physical cleanup. Call [stop] when the session is no longer needed. Each Flow property retains and
 * returns one stable read-only facade, and those flows remain open for the lifetime of this object.
 *
 * [start], [stop], and [FrameConsumerRegistration.unregister] are main-safe suspending operations. [updateParameters],
 * [registerFrameConsumer], and [requestStop] are thread-safe, synchronous, and nonblocking relative to capture,
 * encoding, callbacks, and cleanup. Flow getters may be accessed from any thread and do not start capture.
 *
 * [ScreenCaptureProblem] is the only stable failure classification. Throwable details are optional best-effort
 * diagnostic context. Uncontained throwables retain ordinary Kotlin/JVM propagation and do not gain a fabricated
 * session problem or recovery guarantee.
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
     * Starts capture with the projection authority transferred by [ScreenCaptureEngine.createSession].
     *
     * The host must obtain projection authority and satisfy the platform's media-projection foreground-service and
     * permission requirements before creating the session. A successful [ScreenCaptureEngine.createSession] return
     * transfers projection ownership; the host must call [stop] for every created session, including one that never
     * starts. Use [requestStop] when the calling context cannot await completion.
     *
     * Normal return occurs only after [ScreenCaptureState.Active] has been assigned and startup success has been
     * settled. Observing [ScreenCaptureState.Active] alone does not establish completion: the engine rechecks that
     * capture remains usable after publication. If settings or capture conditions invalidate that check, this call
     * remains pending until a later usable [ScreenCaptureState.Active] or a terminal outcome. Once success has been
     * settled, a later stop cannot revoke it merely because the caller has not yet resumed.
     *
     * This operation does not wait for a source frame, encoded JPEG, consumer callback, or physical cleanup.
     * First-active eligibility uses a 10-second elapsed-realtime window sampled before admission; expiration is
     * observed only when current session work can arbitrate it and is not an unconditional publication deadline.
     * The window does not restart if a published [ScreenCaptureState.Active] becomes unusable before startup success
     * is settled.
     *
     * If this invocation enters while the session is fresh and observes caller cancellation before admission, it
     * atomically requests owner stop and propagates cancellation. Cancellation after admission likewise requests
     * owner stop and propagates to this caller. A cancelled repeated or losing invocation has no authority to stop the
     * admitted run. If the invocation never enters, the lifecycle owner still must stop the created session.
     * A clock or deadline arithmetic failure before admission is a genuine startup failure; it does not return
     * projection ownership, so the lifecycle owner must still stop the session.
     *
     * @param initialParameters initial desired parameters. Defaults to [ScreenCaptureParameters] constructor defaults.
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled, or if a normal stop or Android
     *     projection stop resolves startup before success has been settled, even if [ScreenCaptureState.Active] was
     *     already observed. A caller cancellation observed by an entered fresh or admitted invocation also requests
     *     session stop; a terminal operation cancellation can be caught while the caller's Job remains active.
     * @throws IllegalStateException if this session has already accepted a start, is terminal, or loses a concurrent
     *     start race.
     * @throws ScreenCaptureException for a genuine preparation or startup failure. Failure before public start
     *     admission can leave [state] at [ScreenCaptureState.NotStarted] and does not return projection ownership. The
     *     [ScreenCaptureException.problem] is the stable failure meaning; throwable details are optional best-effort
     *     diagnostics. Normal Requested or ProjectionStopped terminal outcomes cancel startup only while its success
     *     remains unsettled.
     */
    public suspend fun start(
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters(),
    ): Unit = coordinator.start(initialParameters)

    /**
     * Durably requests the newest parameters for a running session.
     *
     * This operation is accepted once the engine enters its running phase and before terminal admission closes.
     * The published [state] can briefly still be [ScreenCaptureState.Starting] when update admission opens; a state
     * snapshot is not an atomic admission check. Callers should await successful [start] or observe
     * [ScreenCaptureState.Running] before submitting updates, and still handle a shutdown race. A state-based wait
     * should also handle an early [ScreenCaptureState.Stopped] or [ScreenCaptureState.Failed] without updating.
     *
     * [parameters] is an immutable whole desired snapshot. Local validation has already occurred during value
     * construction and is never clamped. Geometry-dependent invalidity is later reported through state as
     * [ScreenCaptureProblem.InvalidRequest]. A request equal to the latest accepted desire is normally a no-op. If
     * that desire is currently [ScreenCaptureState.Suspended] and no newer request or reevaluation is pending,
     * resubmitting an equal value admits one fresh normal reevaluation. It does not create a retry loop or promise a
     * physical attempt: a later request or terminal outcome may supersede it first, and a persistent problem may
     * suspend the same desire again. Return acknowledges the accepted desire or reevaluation, not state assignment,
     * flow delivery, or convergence to active output.
     *
     * @param parameters deeply immutable desired capture parameters.
     * @throws IllegalStateException if the engine has not entered its running phase or terminal admission is closed.
     * @throws ScreenCaptureException with [ScreenCaptureProblem.InternalFailure] if the request cannot receive a
     *     required session identity.
     */
    public fun updateParameters(parameters: ScreenCaptureParameters): Unit =
        coordinator.updateParameters(parameters)

    /**
     * Registers the session's one current frame consumer.
     *
     * Registration is legal before start and while the session is nonterminal, and creates no capture work. The
     * callback may enter on an engine-selected thread before this function returns. Calls are serialized: at most one
     * callback invocation or unresolved submission is outstanding for the registration, with no callback deadline.
     * The received [EncodedFrame] is borrowed and may be accessed only during that invocation on the callback
     * thread; copy its bytes and read immutable metadata or scalar values inside the callback before retaining them.
     * Production continues with no registered consumer. While Active, a new consumer may first receive a compatible
     * cached commit with its original sequence, timestamp, and [CaptureOutputInfo], but through a new borrowed
     * [EncodedFrame] wrapper. That delivery performs no new encode or output commit and increments neither count.
     * Delivery opportunities while the consumer is busy are dropped, not queued, so the first or next observed
     * sequence may contain gaps.
     *
     * When ordinary callback closure settles safely, a callback [Exception] is contained as a delivery failure and
     * leaves the registration active. Definite rejection while submitting a current handoff is
     * [ScreenCaptureProblem.InternalFailure]. After accepted work, failure to report physical closure can instead
     * leave delivery unresolved without a retry or guaranteed session problem. Other uncontained throwables follow
     * ordinary Kotlin/JVM propagation on the engine-selected hosting mechanism; they do not acquire a stable session
     * failure meaning.
     *
     * @param consumer callback that should return promptly to avoid busy-delivery drops and must not retain or access
     *     the borrowed frame afterward.
     * @return the identity registration used to stop and await delivery for this consumer.
     * @throws IllegalStateException if another registration remains unresolved or the session is terminal.
     * @throws ScreenCaptureException with [ScreenCaptureProblem.InternalFailure] if a required registration identity
     *     cannot be allocated.
     */
    public fun registerFrameConsumer(consumer: (EncodedFrame) -> Unit): FrameConsumerRegistration =
        FrameConsumerRegistration.create(coordinator.registerFrameConsumer(consumer))

    /**
     * Requests shutdown and awaits logical session completion without blocking the calling thread.
     *
     * Normal return means terminal [state] and final [stats] are assigned, startup is settled, and new session work
     * and new frame delivery are closed. The session's projection stop call has returned normally and will not repeat.
     * A session already in [ScreenCaptureState.Failed] can still stop successfully.
     *
     * This does not await all resource cleanup or an entered consumer callback; use
     * [FrameConsumerRegistration.unregister] for exact consumer completion.
     *
     * Repeated callers share the same shutdown result. Each entered invocation requests stop even if already
     * cancelled; cancellation affects only that caller's wait. Required work that never completes can leave the wait
     * pending indefinitely.
     *
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled.
     * @throws ScreenCaptureException with [ScreenCaptureProblem.InternalFailure] if required shutdown dispatch or
     *     projection stop fails, independently of the capture outcome.
     */
    public suspend fun stop(): Unit = coordinator.stop()

    /**
     * Requests stop without awaiting completion when a suspending [stop] call is unsuitable.
     *
     * This function is idempotent and closes new public work before returning.
     *
     * Terminal state publication, outstanding operation settlement, callback completion, and physical cleanup may
     * occur asynchronously after this function returns. Calling this before [start] terminally stops the session.
     */
    public fun requestStop(): Unit = coordinator.requestStop()

    internal companion object {
        @JvmSynthetic
        internal fun create(coordinator: SessionCoordinator): ScreenCaptureSession = ScreenCaptureSession(coordinator)
    }
}

/**
 * Identity handle for one frame-consumer registration.
 *
 * Unregistering this handle never stops capture. Exact registration completion permits a replacement consumer while
 * the session remains nonterminal, even if the caller that was awaiting it was cancelled. Callback completion is not
 * a receipt for other physical session cleanup.
 */
public class FrameConsumerRegistration private constructor(
    private val unregisterAction: suspend () -> Unit,
) {
    /**
     * Closes new delivery for this registration and awaits its exact callback completion.
     *
     * A successful return is reliable proof that exact registration completion was observed and is idempotent and
     * repeatable. It is not a receipt for other physical session cleanup. Caller cancellation cancels only that
     * caller's wait; it does not reopen delivery or cancel the registration's independent completion. Session stop or
     * failure does not settle this wait exceptionally, there is no callback deadline, and an entered callback must
     * still return. Calling from inside this registration's entered callback is illegal.
     *
     * @throws IllegalStateException if invoked from this registration's entered callback.
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled.
     */
    public suspend fun unregister(): Unit = unregisterAction()

    internal companion object {
        @JvmSynthetic
        internal fun create(unregisterAction: suspend () -> Unit): FrameConsumerRegistration =
            FrameConsumerRegistration(unregisterAction)
    }
}
