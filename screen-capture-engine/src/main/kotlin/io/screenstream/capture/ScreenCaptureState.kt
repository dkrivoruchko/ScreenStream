package io.screenstream.capture

/**
 * An immutable lifecycle snapshot of a screen capture session.
 *
 * Each value is internally coherent and uses structural equality. Rapid [ScreenCaptureSession.state]
 * assignments may be conflated, and a state value is not an atomic snapshot with [ScreenCaptureSession.stats].
 * A terminal [Stopped] or [Failed] value ends capture authority but is not a receipt for physical cleanup.
 * Before terminal state freezes, selection prioritizes [ScreenCaptureStopReason.ProjectionStopped], then
 * [ScreenCaptureStopReason.Requested], then the first failure contender represented by [Failed]. The frozen terminal
 * value is immutable.
 */
public sealed interface ScreenCaptureState {
    /** The initial state of a session for which capture has not been accepted. */
    public data object NotStarted : ScreenCaptureState

    /**
     * A session whose start was accepted and which has not yet reached its first [Active] or a terminal state.
     *
     * The engine assigns this state exactly once for an accepted start. This intermediate value may be conflated.
     * It does not mean that a source frame has arrived, a JPEG has been encoded, or a consumer has received a
     * frame.
     */
    public data object Starting : ScreenCaptureState

    /**
     * A nonterminal session that has reached [Active] at least once.
     *
     * @property requestedParameters the requested parameter snapshot represented by this state.
     * @property isCapturedContentVisible the latest informational visibility observation, or `null` when no
     *     observation is available. Visibility is `null` on API 24 through 33. A false value does not itself pause or
     *     stop capture.
     */
    public sealed interface Running : ScreenCaptureState {
        public val requestedParameters: ScreenCaptureParameters

        public val isCapturedContentVisible: Boolean?
    }

    /**
     * Healthy capture with a compatible, usable effective output plan.
     *
     * This state does not imply that a frame has already been produced or delivered. A visibility-only update
     * may publish another structurally different `Active` snapshot without changing capture or statistics.
     *
     * @property outputInfo the currently applied parameters and committed output geometry.
     * @property isCapturedContentVisible the latest informational visibility observation, or `null` when no
     *     observation is available.
     */
    public class Active private constructor(
        public val outputInfo: CaptureOutputInfo,
        public override val isCapturedContentVisible: Boolean?,
    ) : Running {
        /**
         * The requested parameters, equal to [CaptureOutputInfo.parameters] of
         * [outputInfo].
         */
        public override val requestedParameters: ScreenCaptureParameters
            get() = outputInfo.parameters

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Active) return false

            return (outputInfo == other.outputInfo) && (isCapturedContentVisible == other.isCapturedContentVisible)
        }

        public override fun hashCode(): Int {
            var result: Int = outputInfo.hashCode()
            result = (31 * result) + (isCapturedContentVisible?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Active(outputInfo=$outputInfo, isCapturedContentVisible=$isCapturedContentVisible)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                outputInfo: CaptureOutputInfo,
                isCapturedContentVisible: Boolean?,
            ): Active = Active(outputInfo, isCapturedContentVisible)
        }
    }

    /**
     * Capture is reconciling a request or engine-observed change that invalidated the committed output plan.
     *
     * The engine publishes this state before the first resulting reconfiguration effect. Output production is
     * paused, although a previously admitted callback may still finish with its earlier immutable frame.
     *
     * @property requestedParameters the latest requested parameter snapshot being reconciled.
     * @property lastOutputInfo the historical last-committed output; it does not describe current
     *     availability.
     * @property isCapturedContentVisible the latest informational visibility observation, or `null` when no
     *     observation is available.
     */
    public class Reconfiguring private constructor(
        public override val requestedParameters: ScreenCaptureParameters,
        public val lastOutputInfo: CaptureOutputInfo,
        public override val isCapturedContentVisible: Boolean?,
    ) : Running {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Reconfiguring) return false

            return (requestedParameters == other.requestedParameters) &&
                    (lastOutputInfo == other.lastOutputInfo) &&
                    (isCapturedContentVisible == other.isCapturedContentVisible)
        }

        public override fun hashCode(): Int {
            var result: Int = requestedParameters.hashCode()
            result = (31 * result) + lastOutputInfo.hashCode()
            result = (31 * result) + (isCapturedContentVisible?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Reconfiguring(" +
                    "requestedParameters=$requestedParameters, " +
                    "lastOutputInfo=$lastOutputInfo, " +
                    "isCapturedContentVisible=$isCapturedContentVisible)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                requestedParameters: ScreenCaptureParameters,
                lastOutputInfo: CaptureOutputInfo,
                isCapturedContentVisible: Boolean?,
            ): Reconfiguring = Reconfiguring(
                requestedParameters = requestedParameters,
                lastOutputInfo = lastOutputInfo,
                isCapturedContentVisible = isCapturedContentVisible,
            )
        }
    }

    /**
     * Capture is paused because of a recoverable problem and may resume when the problem is resolved.
     *
     * This state occurs only after the first [Active] assignment; [ScreenCaptureSession.start] may still be pending.
     * [problem] is one of [ScreenCaptureProblem.InvalidRequest],
     * [ScreenCaptureProblem.CaptureUnavailable], or [ScreenCaptureProblem.ResourceExhausted]. There is no retry timer;
     * changed metrics, a projection resize, or [ScreenCaptureSession.updateParameters] may request reevaluation. An
     * equal settled request can request one reevaluation under the conditions documented by
     * [ScreenCaptureSession.updateParameters]. A callback already entered with an earlier immutable frame may finish.
     *
     * @property requestedParameters the latest requested parameter snapshot retained while paused.
     * @property problem the stable, caller-facing reason capture is currently unavailable.
     * @property lastOutputInfo the historical last-committed output; it does not describe current
     *     availability.
     * @property isCapturedContentVisible the latest informational visibility observation, or `null` when no
     *     observation is available.
     */
    public class Suspended private constructor(
        public override val requestedParameters: ScreenCaptureParameters,
        public val problem: ScreenCaptureProblem,
        public val lastOutputInfo: CaptureOutputInfo,
        public override val isCapturedContentVisible: Boolean?,
    ) : Running {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Suspended) return false

            return (requestedParameters == other.requestedParameters) &&
                    (problem == other.problem) &&
                    (lastOutputInfo == other.lastOutputInfo) &&
                    (isCapturedContentVisible == other.isCapturedContentVisible)
        }

        public override fun hashCode(): Int {
            var result: Int = requestedParameters.hashCode()
            result = (31 * result) + problem.hashCode()
            result = (31 * result) + lastOutputInfo.hashCode()
            result = (31 * result) + (isCapturedContentVisible?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Suspended(" +
                    "requestedParameters=$requestedParameters, " +
                    "problem=$problem, " +
                    "lastOutputInfo=$lastOutputInfo, " +
                    "isCapturedContentVisible=$isCapturedContentVisible)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                requestedParameters: ScreenCaptureParameters,
                problem: ScreenCaptureProblem,
                lastOutputInfo: CaptureOutputInfo,
                isCapturedContentVisible: Boolean?,
            ): Suspended = Suspended(
                requestedParameters = requestedParameters,
                problem = problem,
                lastOutputInfo = lastOutputInfo,
                isCapturedContentVisible = isCapturedContentVisible,
            )
        }
    }

    /**
     * A permanent, non-failure terminal state.
     *
     * The value freezes the latest durably accepted request and the last committed output history, even if no
     * preceding [Running] value exposed that request. It ends capture authority but does not confirm callback
     * return, resource release, or other physical cleanup.
     *
     * @property reason the stable reason the session stopped.
     * @property requestedParameters the latest durably accepted requested parameters at terminal selection.
     * @property lastOutputInfo the historical last-committed output, or `null` if none was committed.
     */
    public class Stopped private constructor(
        public val reason: ScreenCaptureStopReason,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastOutputInfo: CaptureOutputInfo?,
    ) : ScreenCaptureState {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Stopped) return false

            return (reason == other.reason) &&
                    (requestedParameters == other.requestedParameters) &&
                    (lastOutputInfo == other.lastOutputInfo)
        }

        public override fun hashCode(): Int {
            var result: Int = reason.hashCode()
            result = (31 * result) + requestedParameters.hashCode()
            result = (31 * result) + (lastOutputInfo?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Stopped(" +
                    "reason=$reason, " +
                    "requestedParameters=$requestedParameters, " +
                    "lastOutputInfo=$lastOutputInfo)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                reason: ScreenCaptureStopReason,
                requestedParameters: ScreenCaptureParameters,
                lastOutputInfo: CaptureOutputInfo?,
            ): Stopped = Stopped(reason, requestedParameters, lastOutputInfo)
        }
    }

    /**
     * A permanent terminal state selected because capture failed.
     *
     * The value freezes the latest durably accepted request and the last committed output history, even if no
     * preceding [Running] value exposed that request. It ends capture authority but does not confirm callback
     * return, resource release, or other physical cleanup.
     *
     * @property problem the stable, caller-facing failure semantics.
     * @property requestedParameters the latest durably accepted requested parameters at terminal selection.
     * @property lastOutputInfo the historical last-committed output, or `null` if none was committed.
     */
    public class Failed private constructor(
        public val problem: ScreenCaptureProblem,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastOutputInfo: CaptureOutputInfo?,
    ) : ScreenCaptureState {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Failed) return false

            return (problem == other.problem) &&
                    (requestedParameters == other.requestedParameters) &&
                    (lastOutputInfo == other.lastOutputInfo)
        }

        public override fun hashCode(): Int {
            var result: Int = problem.hashCode()
            result = (31 * result) + requestedParameters.hashCode()
            result = (31 * result) + (lastOutputInfo?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Failed(" +
                    "problem=$problem, " +
                    "requestedParameters=$requestedParameters, " +
                    "lastOutputInfo=$lastOutputInfo)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                problem: ScreenCaptureProblem,
                requestedParameters: ScreenCaptureParameters,
                lastOutputInfo: CaptureOutputInfo?,
            ): Failed = Failed(problem, requestedParameters, lastOutputInfo)
        }
    }
}

/** The stable reason represented by a terminal [ScreenCaptureState.Stopped] value. */
public enum class ScreenCaptureStopReason {
    /**
     * Stop was requested by the session owner, including through [ScreenCaptureSession.stop],
     * [ScreenCaptureSession.requestStop], or cancellation
     * observed by an entered fresh or already admitted [ScreenCaptureSession.start] invocation.
     */
    Requested,

    /**
     * The Android projection-stop callback reported that the projection session was stopped.
     *
     * This reason has priority over [Requested] and a competing [ScreenCaptureState.Failed] outcome.
     */
    ProjectionStopped,
}

/**
 * Stable caller-facing failure semantics for screen capture operations and states.
 *
 * Callers should branch on these values rather than on optional diagnostic messages or [Throwable] context.
 */
public enum class ScreenCaptureProblem {
    /** The current geometry and parameters cannot produce the requested result. */
    InvalidRequest,

    /** The projection, capture source, or required capture metrics are unavailable. */
    CaptureUnavailable,

    /** A deterministic capacity or required creation or allocation boundary denied the request. */
    ResourceExhausted,

    /** Platform, rendering, JPEG, ownership, or engine evidence became unsafe or inconsistent. */
    InternalFailure,

    /**
     * On API 33 or later, an observed captured buffer explicitly declared Display P3, which the nominal SDR/sRGB
     * output rejects before readback. This classification does not imply generic gamut or HDR validation.
     */
    UnsupportedColorSpace,
}

/**
 * An operation failure with stable [problem] semantics.
 *
 * Inherited throwable details are optional best-effort diagnostic context with no stable content guarantee.
 *
 * @property problem the authoritative caller-facing failure classification.
 */
public class ScreenCaptureException private constructor(
    public val problem: ScreenCaptureProblem,
    cause: Throwable?,
) : Exception(problem.name, cause) {
    internal companion object {
        @JvmSynthetic
        internal fun create(problem: ScreenCaptureProblem, cause: Throwable? = null): ScreenCaptureException =
            ScreenCaptureException(problem, cause)
    }
}
