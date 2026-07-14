package io.screenstream.engine

public sealed interface ScreenCaptureState {
    public object NotStarted : ScreenCaptureState {
        public override fun equals(other: Any?): Boolean = this === other

        public override fun hashCode(): Int = "NotStarted".hashCode()

        public override fun toString(): String = "NotStarted"
    }

    public object Starting : ScreenCaptureState {
        public override fun equals(other: Any?): Boolean = this === other

        public override fun hashCode(): Int = "Starting".hashCode()

        public override fun toString(): String = "Starting"
    }

    public class Running internal constructor(
        public val requestedParameters: ScreenCaptureParameters,
        public val runningState: ScreenCaptureRunningState,
        public val capturedContentVisible: Boolean?,
    ) : ScreenCaptureState {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Running) return false

            return requestedParameters == other.requestedParameters &&
                    runningState == other.runningState &&
                    capturedContentVisible == other.capturedContentVisible
        }

        public override fun hashCode(): Int {
            var result: Int = requestedParameters.hashCode()
            result = 31 * result + runningState.hashCode()
            result = 31 * result + (capturedContentVisible?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Running(" +
                    "requestedParameters=$requestedParameters, " +
                    "runningState=$runningState, " +
                    "capturedContentVisible=$capturedContentVisible)"
    }

    public class Stopped internal constructor(
        public val reason: ScreenCaptureStopReason,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ScreenCaptureState {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Stopped) return false

            return reason == other.reason &&
                    requestedParameters == other.requestedParameters &&
                    lastEffectiveParameters == other.lastEffectiveParameters
        }

        public override fun hashCode(): Int {
            var result: Int = reason.hashCode()
            result = 31 * result + requestedParameters.hashCode()
            result = 31 * result + (lastEffectiveParameters?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Stopped(" +
                    "reason=$reason, " +
                    "requestedParameters=$requestedParameters, " +
                    "lastEffectiveParameters=$lastEffectiveParameters)"
    }

    public class Failed internal constructor(
        public val problem: ScreenCaptureProblem,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ScreenCaptureState {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Failed) return false

            return problem == other.problem &&
                    requestedParameters == other.requestedParameters &&
                    lastEffectiveParameters == other.lastEffectiveParameters
        }

        public override fun hashCode(): Int {
            var result: Int = problem.hashCode()
            result = 31 * result + requestedParameters.hashCode()
            result = 31 * result + (lastEffectiveParameters?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Failed(" +
                    "problem=$problem, " +
                    "requestedParameters=$requestedParameters, " +
                    "lastEffectiveParameters=$lastEffectiveParameters)"
    }
}

public sealed interface ScreenCaptureRunningState {
    public class Active internal constructor(
        public val effectiveParameters: ScreenCaptureEffectiveParameters,
    ) : ScreenCaptureRunningState {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Active) return false

            return effectiveParameters == other.effectiveParameters
        }

        public override fun hashCode(): Int = effectiveParameters.hashCode()

        public override fun toString(): String = "Active(effectiveParameters=$effectiveParameters)"
    }

    public class Suspended internal constructor(
        public val problem: ScreenCaptureProblem,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        public val lastKnownCaptureGeometry: CaptureGeometry?,
    ) : ScreenCaptureRunningState {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Suspended) return false

            return problem == other.problem &&
                    lastEffectiveParameters == other.lastEffectiveParameters &&
                    lastKnownCaptureGeometry == other.lastKnownCaptureGeometry
        }

        public override fun hashCode(): Int {
            var result: Int = problem.hashCode()
            result = 31 * result + lastEffectiveParameters.hashCode()
            result = 31 * result + (lastKnownCaptureGeometry?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Suspended(" +
                    "problem=$problem, " +
                    "lastEffectiveParameters=$lastEffectiveParameters, " +
                    "lastKnownCaptureGeometry=$lastKnownCaptureGeometry)"
    }
}

public enum class ScreenCaptureStopReason {
    OwnerStop,
    CaptureEnded,
}

public enum class ScreenCaptureProblem {
    InvalidRequest,
    CaptureUnavailable,
    Reconfiguring,
    ResourceExhausted,
    InternalFailure,
}

public class ScreenCaptureException internal constructor(
    public val problem: ScreenCaptureProblem,
    cause: Throwable? = null,
) : Exception(problem.name, cause)

public class ScreenCaptureStats internal constructor(
    public val framesEncoded: Long,
    public val framesProduced: Long,
    public val droppedFrames: ScreenCaptureFrameDropStats,
    public val droppedDeliveries: ScreenCaptureDeliveryDropStats,
    public val averageProducedFps: Double,
    public val averageEncodeMs: Double,
    public val averageReadbackMs: Double,
    public val lastEncodedByteCount: Int,
    public val averageEncodedByteCount: Int,
) {
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureStats) return false

        return framesEncoded == other.framesEncoded &&
                framesProduced == other.framesProduced &&
                droppedFrames == other.droppedFrames &&
                droppedDeliveries == other.droppedDeliveries &&
                averageProducedFps.compareTo(other.averageProducedFps) == 0 &&
                averageEncodeMs.compareTo(other.averageEncodeMs) == 0 &&
                averageReadbackMs.compareTo(other.averageReadbackMs) == 0 &&
                lastEncodedByteCount == other.lastEncodedByteCount &&
                averageEncodedByteCount == other.averageEncodedByteCount
    }

    public override fun hashCode(): Int {
        var result: Int = framesEncoded.hashCode()
        result = 31 * result + framesProduced.hashCode()
        result = 31 * result + droppedFrames.hashCode()
        result = 31 * result + droppedDeliveries.hashCode()
        result = 31 * result + averageProducedFps.hashCode()
        result = 31 * result + averageEncodeMs.hashCode()
        result = 31 * result + averageReadbackMs.hashCode()
        result = 31 * result + lastEncodedByteCount.hashCode()
        result = 31 * result + averageEncodedByteCount.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureStats(" +
                "framesEncoded=$framesEncoded, " +
                "framesProduced=$framesProduced, " +
                "droppedFrames=$droppedFrames, " +
                "droppedDeliveries=$droppedDeliveries, " +
                "averageProducedFps=$averageProducedFps, " +
                "averageEncodeMs=$averageEncodeMs, " +
                "averageReadbackMs=$averageReadbackMs, " +
                "lastEncodedByteCount=$lastEncodedByteCount, " +
                "averageEncodedByteCount=$averageEncodedByteCount)"
}

public class ScreenCaptureFrameDropStats internal constructor(
    public val byRateLimit: Long,
    public val byPipelineBusy: Long,
    public val byStaleWork: Long,
    public val byFailure: Long,
) {
    public val total: Long
        get() =
            saturatingAdd(
                saturatingAdd(saturatingAdd(byRateLimit, byPipelineBusy), byStaleWork),
                byFailure,
            )

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureFrameDropStats) return false

        return byRateLimit == other.byRateLimit &&
                byPipelineBusy == other.byPipelineBusy &&
                byStaleWork == other.byStaleWork &&
                byFailure == other.byFailure
    }

    public override fun hashCode(): Int {
        var result: Int = byRateLimit.hashCode()
        result = 31 * result + byPipelineBusy.hashCode()
        result = 31 * result + byStaleWork.hashCode()
        result = 31 * result + byFailure.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureFrameDropStats(" +
                "byRateLimit=$byRateLimit, " +
                "byPipelineBusy=$byPipelineBusy, " +
                "byStaleWork=$byStaleWork, " +
                "byFailure=$byFailure)"
}

public class ScreenCaptureDeliveryDropStats internal constructor(
    public val byConsumerBusy: Long,
    public val byDispatchFailure: Long,
    public val byCallbackFailure: Long,
) {
    public val total: Long
        get() = saturatingAdd(saturatingAdd(byConsumerBusy, byDispatchFailure), byCallbackFailure)

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureDeliveryDropStats) return false

        return byConsumerBusy == other.byConsumerBusy &&
                byDispatchFailure == other.byDispatchFailure &&
                byCallbackFailure == other.byCallbackFailure
    }

    public override fun hashCode(): Int {
        var result: Int = byConsumerBusy.hashCode()
        result = 31 * result + byDispatchFailure.hashCode()
        result = 31 * result + byCallbackFailure.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureDeliveryDropStats(" +
                "byConsumerBusy=$byConsumerBusy, " +
                "byDispatchFailure=$byDispatchFailure, " +
                "byCallbackFailure=$byCallbackFailure)"
}

public class ScreenCaptureDiagnosticEvent internal constructor(
    public val sequence: Long,
    public val timestampEpochMillis: Long,
    public val source: String,
    public val label: String,
    public val message: String,
    public val cause: Throwable? = null,
)

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
