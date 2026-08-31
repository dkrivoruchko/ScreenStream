package io.screenstream.capture

/**
 * An immutable lifecycle snapshot of a screen capture session.
 *
 * Each value is internally coherent and uses structural equality. Rapid [ScreenCaptureSession.state]
 * assignments may be conflated, and a state value is not an atomic snapshot with [ScreenCaptureSession.stats].
 * A terminal [Stopped] or [Failed] value ends capture authority but is not a receipt for physical cleanup.
 * Terminal selection prioritizes [ScreenCaptureStopReason.ProjectionStopped], then
 * [ScreenCaptureStopReason.Requested], then the first failure contender represented by [Failed].
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
     * observation is available. Visibility is `null` on API 24 through 33.
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
     * @property effectiveParameters the currently applied parameters and committed output geometry.
     * @property isCapturedContentVisible the latest informational visibility observation, or `null` when no
     * observation is available.
     */
    public class Active private constructor(
        public val effectiveParameters: ScreenCaptureEffectiveParameters,
        public override val isCapturedContentVisible: Boolean?,
    ) : Running {
        /**
         * The requested parameters, equal to [ScreenCaptureEffectiveParameters.appliedParameters] of
         * [effectiveParameters].
         */
        public override val requestedParameters: ScreenCaptureParameters
            get() = effectiveParameters.appliedParameters

        /** Returns whether [other] is an `Active` value with structurally equal public fields. */
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Active) return false

            return (effectiveParameters == other.effectiveParameters) && (isCapturedContentVisible == other.isCapturedContentVisible)
        }

        /** Returns a hash code consistent with structural equality. */
        public override fun hashCode(): Int {
            var result: Int = effectiveParameters.hashCode()
            result = (31 * result) + (isCapturedContentVisible?.hashCode() ?: 0)
            return result
        }

        /** Returns a bounded, non-sensitive debug string whose exact format is not an API contract. */
        public override fun toString(): String =
            "Active(effectiveParameters=$effectiveParameters, isCapturedContentVisible=$isCapturedContentVisible)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                effectiveParameters: ScreenCaptureEffectiveParameters,
                isCapturedContentVisible: Boolean?,
            ): Active = Active(effectiveParameters, isCapturedContentVisible)
        }
    }

    /**
     * Capture is reconciling a request or engine-observed change that invalidated the committed output plan.
     *
     * The engine publishes this state before the first resulting reconfiguration effect. Output production is
     * paused, although a previously admitted callback may still finish with its earlier immutable frame.
     *
     * @property requestedParameters the latest requested parameter snapshot being reconciled.
     * @property lastEffectiveParameters the historical last-committed output; it does not describe current
     * availability.
     * @property isCapturedContentVisible the latest informational visibility observation, or `null` when no
     * observation is available.
     */
    public class Reconfiguring private constructor(
        public override val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        public override val isCapturedContentVisible: Boolean?,
    ) : Running {
        /** Returns whether [other] is a `Reconfiguring` value with structurally equal public fields. */
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Reconfiguring) return false

            return (requestedParameters == other.requestedParameters) &&
                    (lastEffectiveParameters == other.lastEffectiveParameters) &&
                    (isCapturedContentVisible == other.isCapturedContentVisible)
        }

        /** Returns a hash code consistent with structural equality. */
        public override fun hashCode(): Int {
            var result: Int = requestedParameters.hashCode()
            result = (31 * result) + lastEffectiveParameters.hashCode()
            result = (31 * result) + (isCapturedContentVisible?.hashCode() ?: 0)
            return result
        }

        /** Returns a bounded, non-sensitive debug string whose exact format is not an API contract. */
        public override fun toString(): String =
            "Reconfiguring(" +
                    "requestedParameters=$requestedParameters, " +
                    "lastEffectiveParameters=$lastEffectiveParameters, " +
                    "isCapturedContentVisible=$isCapturedContentVisible)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                requestedParameters: ScreenCaptureParameters,
                lastEffectiveParameters: ScreenCaptureEffectiveParameters,
                isCapturedContentVisible: Boolean?,
            ): Reconfiguring = Reconfiguring(
                requestedParameters = requestedParameters,
                lastEffectiveParameters = lastEffectiveParameters,
                isCapturedContentVisible = isCapturedContentVisible,
            )
        }
    }

    /**
     * Capture is paused because of a recoverable problem and may resume when the problem is resolved.
     *
     * Startup never enters this state. [problem] is one of [ScreenCaptureProblem.InvalidRequest],
     * [ScreenCaptureProblem.CaptureUnavailable], or [ScreenCaptureProblem.ResourceExhausted].
     *
     * @property requestedParameters the latest requested parameter snapshot retained while paused.
     * @property problem the stable, caller-facing reason capture is currently unavailable.
     * @property lastEffectiveParameters the historical last-committed output; it does not describe current
     * availability.
     * @property isCapturedContentVisible the latest informational visibility observation, or `null` when no
     * observation is available.
     */
    public class Suspended private constructor(
        public override val requestedParameters: ScreenCaptureParameters,
        public val problem: ScreenCaptureProblem,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        public override val isCapturedContentVisible: Boolean?,
    ) : Running {
        /** Returns whether [other] is a `Suspended` value with structurally equal public fields. */
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Suspended) return false

            return (requestedParameters == other.requestedParameters) &&
                    (problem == other.problem) &&
                    (lastEffectiveParameters == other.lastEffectiveParameters) &&
                    (isCapturedContentVisible == other.isCapturedContentVisible)
        }

        /** Returns a hash code consistent with structural equality. */
        public override fun hashCode(): Int {
            var result: Int = requestedParameters.hashCode()
            result = (31 * result) + problem.hashCode()
            result = (31 * result) + lastEffectiveParameters.hashCode()
            result = (31 * result) + (isCapturedContentVisible?.hashCode() ?: 0)
            return result
        }

        /** Returns a bounded, non-sensitive debug string whose exact format is not an API contract. */
        public override fun toString(): String =
            "Suspended(" +
                    "requestedParameters=$requestedParameters, " +
                    "problem=$problem, " +
                    "lastEffectiveParameters=$lastEffectiveParameters, " +
                    "isCapturedContentVisible=$isCapturedContentVisible)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                requestedParameters: ScreenCaptureParameters,
                problem: ScreenCaptureProblem,
                lastEffectiveParameters: ScreenCaptureEffectiveParameters,
                isCapturedContentVisible: Boolean?,
            ): Suspended = Suspended(
                requestedParameters = requestedParameters,
                problem = problem,
                lastEffectiveParameters = lastEffectiveParameters,
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
     * @property lastEffectiveParameters the historical last-committed output, or `null` if none was committed.
     */
    public class Stopped private constructor(
        public val reason: ScreenCaptureStopReason,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ScreenCaptureState {
        /** Returns whether [other] is a `Stopped` value with structurally equal public fields. */
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Stopped) return false

            return (reason == other.reason) &&
                    (requestedParameters == other.requestedParameters) &&
                    (lastEffectiveParameters == other.lastEffectiveParameters)
        }

        /** Returns a hash code consistent with structural equality. */
        public override fun hashCode(): Int {
            var result: Int = reason.hashCode()
            result = (31 * result) + requestedParameters.hashCode()
            result = (31 * result) + (lastEffectiveParameters?.hashCode() ?: 0)
            return result
        }

        /** Returns a bounded, non-sensitive debug string whose exact format is not an API contract. */
        public override fun toString(): String =
            "Stopped(" +
                    "reason=$reason, " +
                    "requestedParameters=$requestedParameters, " +
                    "lastEffectiveParameters=$lastEffectiveParameters)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                reason: ScreenCaptureStopReason,
                requestedParameters: ScreenCaptureParameters,
                lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
            ): Stopped = Stopped(reason, requestedParameters, lastEffectiveParameters)
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
     * @property lastEffectiveParameters the historical last-committed output, or `null` if none was committed.
     */
    public class Failed private constructor(
        public val problem: ScreenCaptureProblem,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ScreenCaptureState {
        /** Returns whether [other] is a `Failed` value with structurally equal public fields. */
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Failed) return false

            return (problem == other.problem) &&
                    (requestedParameters == other.requestedParameters) &&
                    (lastEffectiveParameters == other.lastEffectiveParameters)
        }

        /** Returns a hash code consistent with structural equality. */
        public override fun hashCode(): Int {
            var result: Int = problem.hashCode()
            result = (31 * result) + requestedParameters.hashCode()
            result = (31 * result) + (lastEffectiveParameters?.hashCode() ?: 0)
            return result
        }

        /** Returns a bounded, non-sensitive debug string whose exact format is not an API contract. */
        public override fun toString(): String =
            "Failed(" +
                    "problem=$problem, " +
                    "requestedParameters=$requestedParameters, " +
                    "lastEffectiveParameters=$lastEffectiveParameters)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                problem: ScreenCaptureProblem,
                requestedParameters: ScreenCaptureParameters,
                lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
            ): Failed = Failed(problem, requestedParameters, lastEffectiveParameters)
        }
    }
}

/** The stable reason represented by a terminal [ScreenCaptureState.Stopped] value. */
public enum class ScreenCaptureStopReason {
    /**
     * Stop was requested by the session owner, including through [ScreenCaptureSession.stop] or cancellation of
     * an already accepted start.
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
     * A captured source buffer explicitly declared a color space that cannot be represented safely by the
     * required SDR/sRGB output.
     */
    UnsupportedColorSpace,
}

/**
 * An operation failure with stable [problem] semantics.
 *
 * The inherited message, cause, and suppressed throwables are optional best-effort diagnostic context. Their
 * presence, text, type, identity, object graph, order, and cardinality are not API guarantees.
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
