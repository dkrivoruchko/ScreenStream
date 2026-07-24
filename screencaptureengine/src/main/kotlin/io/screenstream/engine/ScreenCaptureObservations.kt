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

    public sealed interface Running : ScreenCaptureState {
        public val requestedParameters: ScreenCaptureParameters
        public val capturedContentVisible: Boolean?
    }

    public class Active private constructor(
        public val effectiveParameters: ScreenCaptureEffectiveParameters,
        public override val capturedContentVisible: Boolean?,
    ) : Running {
        public override val requestedParameters: ScreenCaptureParameters
            get() = effectiveParameters.appliedParameters

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Active) return false

            return effectiveParameters == other.effectiveParameters &&
                    capturedContentVisible == other.capturedContentVisible
        }

        public override fun hashCode(): Int {
            var result: Int = effectiveParameters.hashCode()
            result = 31 * result + (capturedContentVisible?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Active(" +
                    "effectiveParameters=$effectiveParameters, " +
                    "capturedContentVisible=$capturedContentVisible)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                effectiveParameters: ScreenCaptureEffectiveParameters,
                capturedContentVisible: Boolean?,
            ): Active = Active(effectiveParameters, capturedContentVisible)
        }
    }

    public class Reconfiguring private constructor(
        public override val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        public val lastKnownCaptureGeometry: CaptureGeometry?,
        public override val capturedContentVisible: Boolean?,
    ) : Running {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Reconfiguring) return false

            return requestedParameters == other.requestedParameters &&
                    lastEffectiveParameters == other.lastEffectiveParameters &&
                    lastKnownCaptureGeometry == other.lastKnownCaptureGeometry &&
                    capturedContentVisible == other.capturedContentVisible
        }

        public override fun hashCode(): Int {
            var result: Int = requestedParameters.hashCode()
            result = 31 * result + lastEffectiveParameters.hashCode()
            result = 31 * result + (lastKnownCaptureGeometry?.hashCode() ?: 0)
            result = 31 * result + (capturedContentVisible?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Reconfiguring(" +
                    "requestedParameters=$requestedParameters, " +
                    "lastEffectiveParameters=$lastEffectiveParameters, " +
                    "lastKnownCaptureGeometry=$lastKnownCaptureGeometry, " +
                    "capturedContentVisible=$capturedContentVisible)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                requestedParameters: ScreenCaptureParameters,
                lastEffectiveParameters: ScreenCaptureEffectiveParameters,
                lastKnownCaptureGeometry: CaptureGeometry?,
                capturedContentVisible: Boolean?,
            ): Reconfiguring = Reconfiguring(
                requestedParameters = requestedParameters,
                lastEffectiveParameters = lastEffectiveParameters,
                lastKnownCaptureGeometry = lastKnownCaptureGeometry,
                capturedContentVisible = capturedContentVisible,
            )
        }
    }

    public class Suspended private constructor(
        public override val requestedParameters: ScreenCaptureParameters,
        public val problem: ScreenCaptureProblem,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        public val lastKnownCaptureGeometry: CaptureGeometry?,
        public override val capturedContentVisible: Boolean?,
    ) : Running {
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Suspended) return false

            return requestedParameters == other.requestedParameters &&
                    problem == other.problem &&
                    lastEffectiveParameters == other.lastEffectiveParameters &&
                    lastKnownCaptureGeometry == other.lastKnownCaptureGeometry &&
                    capturedContentVisible == other.capturedContentVisible
        }

        public override fun hashCode(): Int {
            var result: Int = requestedParameters.hashCode()
            result = 31 * result + problem.hashCode()
            result = 31 * result + lastEffectiveParameters.hashCode()
            result = 31 * result + (lastKnownCaptureGeometry?.hashCode() ?: 0)
            result = 31 * result + (capturedContentVisible?.hashCode() ?: 0)
            return result
        }

        public override fun toString(): String =
            "Suspended(" +
                    "requestedParameters=$requestedParameters, " +
                    "problem=$problem, " +
                    "lastEffectiveParameters=$lastEffectiveParameters, " +
                    "lastKnownCaptureGeometry=$lastKnownCaptureGeometry, " +
                    "capturedContentVisible=$capturedContentVisible)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                requestedParameters: ScreenCaptureParameters,
                problem: ScreenCaptureProblem,
                lastEffectiveParameters: ScreenCaptureEffectiveParameters,
                lastKnownCaptureGeometry: CaptureGeometry?,
                capturedContentVisible: Boolean?,
            ): Suspended = Suspended(
                requestedParameters = requestedParameters,
                problem = problem,
                lastEffectiveParameters = lastEffectiveParameters,
                lastKnownCaptureGeometry = lastKnownCaptureGeometry,
                capturedContentVisible = capturedContentVisible,
            )
        }
    }

    public class Stopped private constructor(
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

        internal companion object {
            @JvmSynthetic
            internal fun create(
                reason: ScreenCaptureStopReason,
                requestedParameters: ScreenCaptureParameters,
                lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
            ): Stopped = Stopped(reason, requestedParameters, lastEffectiveParameters)
        }
    }

    public class Failed private constructor(
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

public enum class ScreenCaptureStopReason {
    OwnerStop,
    CaptureEnded,
}

public enum class ScreenCaptureProblem {
    InvalidRequest,
    CaptureUnavailable,
    ResourceExhausted,
    InternalFailure,
}

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

public class ScreenCaptureStats private constructor(
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
    init {
        require(framesEncoded >= 0L)
        require(framesProduced >= 0L)
        require(averageProducedFps.isFinite() && averageProducedFps >= 0.0)
        require(averageEncodeMs.isFinite() && averageEncodeMs >= 0.0)
        require(averageReadbackMs.isFinite() && averageReadbackMs >= 0.0)
        require(lastEncodedByteCount >= 0)
        require(averageEncodedByteCount >= 0)
        require((framesEncoded == 0L) == (lastEncodedByteCount == 0))
        require((framesEncoded == 0L) == (averageEncodedByteCount == 0))
    }

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

    internal companion object {
        @JvmSynthetic
        internal fun create(
            framesEncoded: Long,
            framesProduced: Long,
            droppedFrames: ScreenCaptureFrameDropStats,
            droppedDeliveries: ScreenCaptureDeliveryDropStats,
            averageProducedFps: Double,
            averageEncodeMs: Double,
            averageReadbackMs: Double,
            lastEncodedByteCount: Int,
            averageEncodedByteCount: Int,
        ): ScreenCaptureStats = ScreenCaptureStats(
            framesEncoded = framesEncoded,
            framesProduced = framesProduced,
            droppedFrames = droppedFrames,
            droppedDeliveries = droppedDeliveries,
            averageProducedFps = averageProducedFps,
            averageEncodeMs = averageEncodeMs,
            averageReadbackMs = averageReadbackMs,
            lastEncodedByteCount = lastEncodedByteCount,
            averageEncodedByteCount = averageEncodedByteCount,
        )
    }
}

public class ScreenCaptureFrameDropStats private constructor(
    public val byPipelineBusy: Long,
    public val byStaleWork: Long,
    public val byFailure: Long,
) {
    init {
        require(byPipelineBusy >= 0L)
        require(byStaleWork >= 0L)
        require(byFailure >= 0L)
    }

    public val total: Long
        get() = saturatingAdd(saturatingAdd(byPipelineBusy, byStaleWork), byFailure)

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureFrameDropStats) return false

        return byPipelineBusy == other.byPipelineBusy &&
                byStaleWork == other.byStaleWork &&
                byFailure == other.byFailure
    }

    public override fun hashCode(): Int {
        var result: Int = byPipelineBusy.hashCode()
        result = 31 * result + byStaleWork.hashCode()
        result = 31 * result + byFailure.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureFrameDropStats(" +
                "byPipelineBusy=$byPipelineBusy, " +
                "byStaleWork=$byStaleWork, " +
                "byFailure=$byFailure)"

    internal companion object {
        @JvmSynthetic
        internal fun create(
            byPipelineBusy: Long,
            byStaleWork: Long,
            byFailure: Long,
        ): ScreenCaptureFrameDropStats = ScreenCaptureFrameDropStats(byPipelineBusy, byStaleWork, byFailure)
    }
}

public class ScreenCaptureDeliveryDropStats private constructor(
    public val byConsumerBusy: Long,
    public val byCallbackFailure: Long,
) {
    init {
        require(byConsumerBusy >= 0L)
        require(byCallbackFailure >= 0L)
    }

    public val total: Long
        get() = saturatingAdd(byConsumerBusy, byCallbackFailure)

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureDeliveryDropStats) return false

        return byConsumerBusy == other.byConsumerBusy && byCallbackFailure == other.byCallbackFailure
    }

    public override fun hashCode(): Int {
        var result: Int = byConsumerBusy.hashCode()
        result = 31 * result + byCallbackFailure.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureDeliveryDropStats(" +
                "byConsumerBusy=$byConsumerBusy, " +
                "byCallbackFailure=$byCallbackFailure)"

    internal companion object {
        @JvmSynthetic
        internal fun create(
            byConsumerBusy: Long,
            byCallbackFailure: Long,
        ): ScreenCaptureDeliveryDropStats = ScreenCaptureDeliveryDropStats(byConsumerBusy, byCallbackFailure)
    }
}

public class ScreenCaptureDiagnosticEvent private constructor(
    public val sequence: Long,
    public val timestampEpochMillis: Long,
    public val source: String,
    public val label: String,
    public val message: String,
    public val cause: Throwable?,
) {
    init {
        require(sequence > 0L)
    }

    internal companion object {
        @JvmSynthetic
        internal fun create(
            sequence: Long,
            timestampEpochMillis: Long,
            source: String,
            label: String,
            message: String,
            cause: Throwable? = null,
        ): ScreenCaptureDiagnosticEvent = ScreenCaptureDiagnosticEvent(
            sequence = sequence,
            timestampEpochMillis = timestampEpochMillis,
            source = source,
            label = label,
            message = message,
            cause = cause,
        )
    }
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
