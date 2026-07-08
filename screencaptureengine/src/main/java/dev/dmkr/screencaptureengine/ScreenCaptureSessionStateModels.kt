package dev.dmkr.screencaptureengine

/** Result of an atomic parameter update request. */
public sealed interface ScreenCaptureParameterUpdateResult {
    /** New parameters were fully applied. */
    public data object Applied : ScreenCaptureParameterUpdateResult

    /** New parameters were rejected; previous active parameters remain in use. */
    public class Rejected public constructor(public val problem: ScreenCaptureProblem) : ScreenCaptureParameterUpdateResult {
        public override fun equals(other: Any?): Boolean = other is Rejected && problem == other.problem

        public override fun hashCode(): Int = problem.hashCode()
    }
}

/**
 * Public lifecycle state of a session.
 *
 * [Running] means the projection/session is alive. Its output can still be [ScreenCaptureOutputState.Suspended], in which case no new frames are published
 * until parameters or geometry become valid again.
 */
public sealed interface ScreenCaptureSessionState {
    /** Session is alive; output is either active or suspended. */
    public class Running public constructor(
        public val output: ScreenCaptureOutputState,

        /** Visibility of captured content when the platform reports it, otherwise null. */
        public val capturedContentVisible: Boolean?,
    ) : ScreenCaptureSessionState {
        public override fun equals(other: Any?): Boolean =
            other is Running && output == other.output && capturedContentVisible == other.capturedContentVisible

        public override fun hashCode(): Int = 31 * output.hashCode() + (capturedContentVisible?.hashCode() ?: 0)
    }

    /** Session ended normally from owner/system capture stop. Terminal and non-restartable. */
    public class Stopped public constructor(
        public val reason: ScreenCaptureStopReason,
        public val problem: ScreenCaptureProblem?,
    ) : ScreenCaptureSessionState {
        public override fun equals(other: Any?): Boolean = other is Stopped && reason == other.reason && problem == other.problem

        public override fun hashCode(): Int = 31 * reason.hashCode() + (problem?.hashCode() ?: 0)
    }

    /** Session ended due to a fatal engine/platform problem. Terminal and non-restartable. */
    public class Failed public constructor(public val problem: ScreenCaptureProblem) : ScreenCaptureSessionState {
        public override fun equals(other: Any?): Boolean = other is Failed && problem == other.problem

        public override fun hashCode(): Int = problem.hashCode()
    }
}

/** Current output status while the session is running. */
public sealed interface ScreenCaptureOutputState {
    /** Output plan is active and may publish frames. */
    public class Active public constructor(public val effectiveParameters: ScreenCaptureEffectiveParameters) : ScreenCaptureOutputState {
        public override fun equals(other: Any?): Boolean = other is Active && effectiveParameters == other.effectiveParameters

        public override fun hashCode(): Int = effectiveParameters.hashCode()
    }

    /**
     * Output plan is unavailable; no new frames are published until resumed.
     *
     * The projection remains alive. Existing latest output is retired for new deliveries, and
     * [previousEffectiveParameters] describes the last active plan.
     */
    public class Suspended public constructor(
        public val problem: ScreenCaptureProblem,
        public val previousEffectiveParameters: ScreenCaptureEffectiveParameters,
        public val currentCaptureGeometry: CaptureGeometry,
    ) : ScreenCaptureOutputState {
        public override fun equals(other: Any?): Boolean =
            other is Suspended && problem == other.problem && previousEffectiveParameters == other.previousEffectiveParameters &&
                    currentCaptureGeometry == other.currentCaptureGeometry

        public override fun hashCode(): Int = 31 * (31 * problem.hashCode() + previousEffectiveParameters.hashCode()) + currentCaptureGeometry.hashCode()
    }
}

/**
 * Structured problem used in exceptions, state, update results, and diagnostics.
 *
 * [kind] is stable, but severity is contextual: the same kind can appear in startup failure,
 * rejected parameter update, running suspension, terminal failure, or diagnostics.
 */
public class ScreenCaptureProblem public constructor(
    /** Monotonic problem sequence for ordering diagnostics. */
    public val sequence: Long,

    /** Stable technical classification. */
    public val kind: ScreenCaptureProblemKind,

    /** Optional diagnostic text, not a parsing contract. */
    public val message: String? = null,

    /** Optional underlying cause. */
    public val cause: Throwable? = null,
) {
    init {
        require(sequence >= 0L) { "sequence must be non-negative, was $sequence" }
    }

    public override fun equals(other: Any?): Boolean =
        other is ScreenCaptureProblem && sequence == other.sequence && kind == other.kind && message == other.message && cause == other.cause

    public override fun hashCode(): Int = 31 * (31 * (31 * sequence.hashCode() + kind.hashCode()) + (message?.hashCode() ?: 0)) + (cause?.hashCode() ?: 0)
}

/**
 * Stable technical classification for public problems.
 *
 * This enum does not encode severity. Owners should interpret it together with the surface carrying the problem: startup exception, rejected update,
 * suspended output, failed state, or diagnostic event.
 */
public enum class ScreenCaptureProblemKind {
    ProjectionInvalidOrStopped,
    ProjectionPermissionDenied,
    ProjectionSessionReused,
    VirtualDisplayCreateFailed,
    StartupGeometryUnavailable,

    MetricsUnavailableOrInvalid,
    OutputPlanInvalid,
    OutputLimitsExceeded,

    SurfaceCreateOrResizeFailed,
    GlInitializationFailed,
    GlResourceFailure,
    GlInvariantViolation,

    ReadbackUnavailable,
    ReadbackRepeatedFailure,
    PboValidationOrInvariantFailure,

    EncoderUnavailable,
    EncoderValidationFailed,
    EncodeRepeatedFailure,
    EncodedSizeLimitExceeded,

    AllocationFailed,
    MemoryPressure,

    FrameDeliveryFailed,
    FrameCallbackThrew,

    InternalInvariantViolation,
}

/** Terminal stop reason for non-failed session completion. */
public enum class ScreenCaptureStopReason {
    /** Owner called stop or close. */
    OwnerStop,

    /** Platform/user ended the projection. */
    CaptureEnded,
}
